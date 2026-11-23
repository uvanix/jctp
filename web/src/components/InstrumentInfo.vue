<script setup lang="ts">
import { ref, watch } from 'vue'
import { api } from '@/api/http'
import type { Instrument, InstrumentRates } from '@/api/types'
import { EXCHANGES, PRODUCT_CLASS } from '@/utils/dict'
import { errorText, percent } from '@/utils/format'

const props = defineProps<{ instrument: Instrument | null }>()

const rates = ref<InstrumentRates | null>(null)
const ratesError = ref('')
const loading = ref(false)
let seq = 0

// 费率查询走柜台（每秒一次），切换合约时按需加载
watch(
  () => props.instrument?.instrumentID,
  async (id) => {
    rates.value = null
    ratesError.value = ''
    if (!id) return
    const current = ++seq
    loading.value = true
    try {
      const result = await api.rates(id)
      if (current === seq) rates.value = result
    } catch (e) {
      if (current === seq) ratesError.value = errorText(e)
    } finally {
      if (current === seq) loading.value = false
    }
  },
  { immediate: true },
)

/** 按金额（比例）+ 按手数（元）两部分组合显示 */
function fee(byMoney?: number, byVolume?: number): string {
  const parts: string[] = []
  if (byMoney) parts.push(percent(byMoney, 4))
  if (byVolume) parts.push(`${byVolume} 元/手`)
  return parts.length ? parts.join(' + ') : '0'
}
</script>

<template>
  <div class="panel info">
    <div class="panel-title">合约信息</div>
    <div class="panel-body">
      <el-descriptions v-if="instrument" :column="1" size="small" border>
        <el-descriptions-item label="合约">{{ instrument.instrumentID }} {{ instrument.instrumentName }}</el-descriptions-item>
        <el-descriptions-item label="交易所">{{ EXCHANGES[instrument.exchangeID] ?? instrument.exchangeID }}</el-descriptions-item>
        <el-descriptions-item label="品种">
          {{ instrument.productID }} · {{ PRODUCT_CLASS[instrument.productClass] ?? instrument.productClass }}
        </el-descriptions-item>
        <el-descriptions-item label="合约乘数">{{ instrument.volumeMultiple }}</el-descriptions-item>
        <el-descriptions-item label="最小变动">{{ instrument.priceTick }}</el-descriptions-item>
        <template v-if="instrument.optionsType">
          <el-descriptions-item label="期权类型">{{ instrument.optionsType === 'Call' ? '看涨' : '看跌' }}</el-descriptions-item>
          <el-descriptions-item label="标的">{{ instrument.underlyingInstrID }}</el-descriptions-item>
          <el-descriptions-item label="行权价">{{ instrument.strikePrice }}</el-descriptions-item>
        </template>
        <el-descriptions-item label="上市日">{{ instrument.openDate ?? '--' }}</el-descriptions-item>
        <el-descriptions-item label="到期日">{{ instrument.expireDate ?? '--' }}</el-descriptions-item>
        <el-descriptions-item label="交割">
          {{ instrument.deliveryYear ? `${instrument.deliveryYear}-${String(instrument.deliveryMonth).padStart(2, '0')}` : '--' }}
          <span v-if="instrument.startDelivDate" class="muted">（{{ instrument.startDelivDate }} ~ {{ instrument.endDelivDate }}）</span>
        </el-descriptions-item>
        <el-descriptions-item label="限价单量">{{ instrument.minLimitOrderVolume }} ~ {{ instrument.maxLimitOrderVolume }}</el-descriptions-item>
        <el-descriptions-item label="市价单量">{{ instrument.minMarketOrderVolume }} ~ {{ instrument.maxMarketOrderVolume }}</el-descriptions-item>
        <el-descriptions-item label="交易所保证金">
          多 {{ percent(instrument.longMarginRatio) }} / 空 {{ percent(instrument.shortMarginRatio) }}
        </el-descriptions-item>
        <el-descriptions-item label="账户保证金">
          <span v-if="loading" class="muted">查询中…</span>
          <span v-else-if="rates?.margin">
            多 {{ fee(rates.margin.longMarginRatioByMoney, rates.margin.longMarginRatioByVolume) }} /
            空 {{ fee(rates.margin.shortMarginRatioByMoney, rates.margin.shortMarginRatioByVolume) }}
          </span>
          <span v-else class="muted">{{ ratesError || '--' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="开仓手续费">
          <span v-if="rates?.commission">{{ fee(rates.commission.openRatioByMoney, rates.commission.openRatioByVolume) }}</span>
          <span v-else class="muted">{{ loading ? '查询中…' : '--' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="平仓手续费">
          <span v-if="rates?.commission">{{ fee(rates.commission.closeRatioByMoney, rates.commission.closeRatioByVolume) }}</span>
          <span v-else class="muted">--</span>
        </el-descriptions-item>
        <el-descriptions-item label="平今手续费">
          <span v-if="rates?.commission">
            {{ fee(rates.commission.closeTodayRatioByMoney, rates.commission.closeTodayRatioByVolume) }}
          </span>
          <span v-else class="muted">--</span>
        </el-descriptions-item>
      </el-descriptions>
      <div v-else class="muted placeholder">请选择合约</div>
    </div>
  </div>
</template>

<style scoped>
.placeholder {
  padding: 24px;
  text-align: center;
}

:deep(.el-descriptions__label) {
  width: 84px;
  white-space: nowrap;
}
</style>
