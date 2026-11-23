<script setup lang="ts">
import { computed } from 'vue'
import type { Instrument, MarketData } from '@/api/types'
import { useMarketStore } from '@/stores/market'
import { change, decimals, int, percent, price, trendClass } from '@/utils/format'

const props = defineProps<{ instruments: Instrument[]; selected: string | null; loading?: boolean }>()
const emit = defineEmits<{ select: [id: string]; open: [id: string] }>()

const market = useMarketStore()

interface Row {
  instrument: Instrument
  quote: MarketData | undefined
  digits: number
  change: number | null
  /** 中间价：买一、卖一都有挂单时取均值 */
  mid: number | null
}

function midPrice(quote: MarketData | undefined): number | null {
  const bid = quote?.bidPrice1
  const ask = quote?.askPrice1
  return bid != null && ask != null && !Number.isNaN(bid) && !Number.isNaN(ask) ? (bid + ask) / 2 : null
}

const rows = computed<Row[]>(() =>
  props.instruments.map((instrument) => {
    const quote = market.quotes[instrument.instrumentID]
    return {
      instrument,
      quote,
      digits: decimals(instrument.priceTick),
      change: change(quote?.lastPrice, quote?.preSettlementPrice),
      mid: midPrice(quote),
    }
  }),
)

/** 中间价落在半个最小变动价位上时多显示一位小数 */
function midText(row: Row): string {
  if (row.mid == null) return '--'
  const tick = row.instrument.priceTick
  const onTick = tick > 0 && Math.abs(row.mid / tick - Math.round(row.mid / tick)) < 1e-6
  return price(row.mid, onTick ? row.digits : row.digits + 1)
}

function vs(row: Row, value: number | null | undefined) {
  return trendClass(change(value, row.quote?.preSettlementPrice))
}

function rowClass({ row }: { row: Row }) {
  return row.instrument.instrumentID === props.selected ? 'current-row' : ''
}
</script>

<template>
  <el-table
    :data="rows"
    :row-key="(r: Row) => r.instrument.instrumentID"
    :row-class-name="rowClass"
    height="100%"
    v-loading="loading"
    empty-text="请选择品种"
    @row-click="(r: Row) => emit('select', r.instrument.instrumentID)"
    @row-dblclick="(r: Row) => emit('open', r.instrument.instrumentID)"
  >
    <el-table-column label="合约" width="90" fixed>
      <template #default="{ row }">
        <span class="code">{{ row.instrument.instrumentID }}</span>
      </template>
    </el-table-column>
    <el-table-column label="名称" width="96" show-overflow-tooltip>
      <template #default="{ row }">{{ row.instrument.instrumentName }}</template>
    </el-table-column>
    <el-table-column label="最新价" width="80" align="right">
      <template #default="{ row }">
        <span class="num" :class="trendClass(row.change)">{{ price(row.quote?.lastPrice, row.digits) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="买一价" width="80" align="right">
      <template #default="{ row }">
        <span class="num" :class="vs(row, row.quote?.bidPrice1)">{{ price(row.quote?.bidPrice1, row.digits) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="买一量" width="60" align="right">
      <template #default="{ row }"><span class="num">{{ int(row.quote?.bidVolume1) }}</span></template>
    </el-table-column>
    <el-table-column label="中间价" width="86" align="right">
      <template #default="{ row }">
        <span class="num" :class="vs(row, row.mid)">{{ midText(row) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="卖一价" width="80" align="right">
      <template #default="{ row }">
        <span class="num" :class="vs(row, row.quote?.askPrice1)">{{ price(row.quote?.askPrice1, row.digits) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="卖一量" width="60" align="right">
      <template #default="{ row }"><span class="num">{{ int(row.quote?.askVolume1) }}</span></template>
    </el-table-column>
    <el-table-column label="涨停价" width="80" align="right">
      <template #default="{ row }">
        <span class="num up">{{ price(row.quote?.upperLimitPrice, row.digits) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="跌停价" width="80" align="right">
      <template #default="{ row }">
        <span class="num down">{{ price(row.quote?.lowerLimitPrice, row.digits) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="涨跌" width="70" align="right">
      <template #default="{ row }">
        <span class="num" :class="trendClass(row.change)">{{ price(row.change, row.digits) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="涨跌幅" width="70" align="right">
      <template #default="{ row }">
        <span class="num" :class="trendClass(row.change)">
          {{ row.quote?.preSettlementPrice ? percent((row.change ?? NaN) / row.quote.preSettlementPrice) : '--' }}
        </span>
      </template>
    </el-table-column>
    <el-table-column label="成交量" width="86" align="right">
      <template #default="{ row }"><span class="num">{{ int(row.quote?.volume) }}</span></template>
    </el-table-column>
    <el-table-column label="持仓量" width="86" align="right">
      <template #default="{ row }"><span class="num">{{ int(row.quote?.openInterest) }}</span></template>
    </el-table-column>
    <el-table-column label="日增仓" width="74" align="right">
      <template #default="{ row }">
        <span class="num" :class="trendClass(row.quote ? row.quote.openInterest - (row.quote.preOpenInterest ?? 0) : null)">
          {{ row.quote?.preOpenInterest != null ? int(row.quote.openInterest - row.quote.preOpenInterest) : '--' }}
        </span>
      </template>
    </el-table-column>
    <el-table-column label="今开盘" width="80" align="right">
      <template #default="{ row }">
        <span class="num" :class="vs(row, row.quote?.openPrice)">{{ price(row.quote?.openPrice, row.digits) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="最高价" width="80" align="right">
      <template #default="{ row }">
        <span class="num" :class="vs(row, row.quote?.highestPrice)">{{ price(row.quote?.highestPrice, row.digits) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="最低价" width="80" align="right">
      <template #default="{ row }">
        <span class="num" :class="vs(row, row.quote?.lowestPrice)">{{ price(row.quote?.lowestPrice, row.digits) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="今收盘" width="80" align="right">
      <template #default="{ row }">
        <span class="num" :class="vs(row, row.quote?.closePrice)">{{ price(row.quote?.closePrice, row.digits) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="昨收盘" width="80" align="right">
      <template #default="{ row }">
        <span class="num">{{ price(row.quote?.preClosePrice, row.digits) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="结算价" width="80" align="right">
      <template #default="{ row }">
        <span class="num" :class="vs(row, row.quote?.settlementPrice)">{{ price(row.quote?.settlementPrice, row.digits) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="昨结算" width="80" align="right">
      <template #default="{ row }">
        <span class="num">{{ price(row.quote?.preSettlementPrice, row.digits) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="时间" width="76">
      <template #default="{ row }">
        <span class="num muted">{{ row.quote?.actionTime?.slice(0, 8) ?? '--' }}</span>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.code {
  font-weight: 600;
  color: var(--code-color);
}
</style>
