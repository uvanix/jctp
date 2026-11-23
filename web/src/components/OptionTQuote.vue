<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Instrument, MarketData } from '@/api/types'
import { useMarketStore } from '@/stores/market'
import { change, decimals, int, price, trendClass } from '@/utils/format'

/** 期权 T 型报价：左看涨、中行权价、右看跌，按标的合约分月份 */
const props = defineProps<{ instruments: Instrument[]; selected: string | null; loading?: boolean }>()
const emit = defineEmits<{ select: [id: string]; open: [id: string] }>()

const market = useMarketStore()

const underlyings = computed(() =>
  [...new Set(props.instruments.map((i) => i.underlyingInstrID).filter(Boolean))].sort(),
)
const underlying = ref('')

watch(
  underlyings,
  (list) => {
    if (!list.includes(underlying.value)) underlying.value = list[0] ?? ''
  },
  { immediate: true },
)

interface Row {
  strike: number
  call?: Instrument
  put?: Instrument
}

const rows = computed<Row[]>(() => {
  const byStrike = new Map<number, Row>()
  for (const i of props.instruments) {
    if (i.underlyingInstrID !== underlying.value) continue
    const row = byStrike.get(i.strikePrice) ?? { strike: i.strikePrice }
    if (i.optionsType === 'Call') row.call = i
    else if (i.optionsType === 'Put') row.put = i
    byStrike.set(i.strikePrice, row)
  }
  return [...byStrike.values()].sort((a, b) => a.strike - b.strike)
})

// 只订阅当前月份的期权与标的
watch(
  rows,
  (list) => {
    const ids = list.flatMap((r) => [r.call?.instrumentID, r.put?.instrumentID]).filter((x): x is string => !!x)
    market.watch('list', [...ids, underlying.value])
  },
  { immediate: true },
)

const underlyingQuote = computed(() => market.quotes[underlying.value])
const digits = computed(() => decimals(props.instruments[0]?.priceTick))

/** 平值：行权价最接近标的最新价 */
const atmStrike = computed(() => {
  const last = underlyingQuote.value?.lastPrice
  if (last == null) return null
  let best: number | null = null
  for (const r of rows.value) {
    if (best === null || Math.abs(r.strike - last) < Math.abs(best - last)) best = r.strike
  }
  return best
})

function q(i?: Instrument): MarketData | undefined {
  return i ? market.quotes[i.instrumentID] : undefined
}

function diff(i?: Instrument): number | null {
  const quote = q(i)
  return change(quote?.lastPrice, quote?.preSettlementPrice)
}

function cellClass(i?: Instrument) {
  return { option: !!i, active: !!i && i.instrumentID === props.selected }
}

function rowClass({ row }: { row: Row }) {
  return row.strike === atmStrike.value ? 'atm' : ''
}
</script>

<template>
  <div class="tquote">
    <div class="bar">
      <span class="muted">标的</span>
      <el-select v-model="underlying" style="width: 120px">
        <el-option v-for="u in underlyings" :key="u" :label="u" :value="u" />
      </el-select>
      <template v-if="underlyingQuote">
        <span class="num" :class="trendClass(change(underlyingQuote.lastPrice, underlyingQuote.preSettlementPrice))">
          {{ price(underlyingQuote.lastPrice, digits) }}
        </span>
        <span class="muted">持仓 {{ int(underlyingQuote.openInterest) }}</span>
      </template>
      <span class="muted legend">左：看涨 · 右：看跌 · 双击打开 K 线</span>
    </div>
    <el-table :data="rows" height="100%" :row-class-name="rowClass" v-loading="loading" empty-text="无期权合约">
      <el-table-column label="看涨" align="center">
        <el-table-column label="持仓量" width="72" align="right">
          <template #default="{ row }"><span class="num">{{ int(q(row.call)?.openInterest) }}</span></template>
        </el-table-column>
        <el-table-column label="成交量" width="68" align="right">
          <template #default="{ row }"><span class="num">{{ int(q(row.call)?.volume) }}</span></template>
        </el-table-column>
        <el-table-column label="涨跌" width="64" align="right">
          <template #default="{ row }">
            <span class="num" :class="trendClass(diff(row.call))">{{ price(diff(row.call), digits) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="买价" width="70" align="right">
          <template #default="{ row }"><span class="num">{{ price(q(row.call)?.bidPrice1, digits) }}</span></template>
        </el-table-column>
        <el-table-column label="卖价" width="70" align="right">
          <template #default="{ row }"><span class="num">{{ price(q(row.call)?.askPrice1, digits) }}</span></template>
        </el-table-column>
        <el-table-column label="最新价" width="72" align="right">
          <template #default="{ row }">
            <span
              class="num"
              :class="[trendClass(diff(row.call)), cellClass(row.call)]"
              @click="row.call && emit('select', row.call.instrumentID)"
              @dblclick="row.call && emit('open', row.call.instrumentID)"
            >
              {{ price(q(row.call)?.lastPrice, digits) }}
            </span>
          </template>
        </el-table-column>
      </el-table-column>
      <el-table-column label="行权价" width="90" align="center">
        <template #default="{ row }"><span class="strike num">{{ row.strike }}</span></template>
      </el-table-column>
      <el-table-column label="看跌" align="center">
        <el-table-column label="最新价" width="72" align="right">
          <template #default="{ row }">
            <span
              class="num"
              :class="[trendClass(diff(row.put)), cellClass(row.put)]"
              @click="row.put && emit('select', row.put.instrumentID)"
              @dblclick="row.put && emit('open', row.put.instrumentID)"
            >
              {{ price(q(row.put)?.lastPrice, digits) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="买价" width="70" align="right">
          <template #default="{ row }"><span class="num">{{ price(q(row.put)?.bidPrice1, digits) }}</span></template>
        </el-table-column>
        <el-table-column label="卖价" width="70" align="right">
          <template #default="{ row }"><span class="num">{{ price(q(row.put)?.askPrice1, digits) }}</span></template>
        </el-table-column>
        <el-table-column label="涨跌" width="64" align="right">
          <template #default="{ row }">
            <span class="num" :class="trendClass(diff(row.put))">{{ price(diff(row.put), digits) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="成交量" width="68" align="right">
          <template #default="{ row }"><span class="num">{{ int(q(row.put)?.volume) }}</span></template>
        </el-table-column>
        <el-table-column label="持仓量" width="72" align="right">
          <template #default="{ row }"><span class="num">{{ int(q(row.put)?.openInterest) }}</span></template>
        </el-table-column>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.tquote {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border-bottom: 1px solid var(--panel-border);
}

.legend {
  margin-left: auto;
}

.strike {
  font-weight: 600;
  color: var(--code-color);
}

.option {
  cursor: pointer;
  padding: 0 4px;
  border-radius: 2px;
}

.option:hover {
  background: var(--row-active-bg);
}

.option.active {
  outline: 1px solid var(--el-color-primary);
}

:deep(.atm td) {
  background: var(--atm-bg) !important;
}
</style>
