<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { Direction, Instrument, OffsetFlag, OrderPreset, OrderType } from '@/api/types'
import { useMarketStore } from '@/stores/market'
import { useTradingStore } from '@/stores/trading'
import { DIRECTION, OFFSET, closeTodaySeparately } from '@/utils/dict'
import { change, decimals, errorText, int, price, trendClass } from '@/utils/format'
import { useStorage } from '@/utils/storage'

const props = defineProps<{ instrument: Instrument | null; preset: OrderPreset | null }>()

type PriceMode = 'limit' | 'opponent' | 'queue' | 'last' | 'market'
const PRICE_MODES: { value: PriceMode; label: string }[] = [
  { value: 'limit', label: '限价' },
  { value: 'opponent', label: '对手价' },
  { value: 'queue', label: '排队价' },
  { value: 'last', label: '最新价' },
  { value: 'market', label: '市价' },
]
const CONFIRM_KEY = 'jctp.confirmOrder'

const market = useMarketStore()
const trading = useTradingStore()
const form = reactive({
  offsetFlag: 'Open' as OffsetFlag,
  priceMode: 'limit' as PriceMode,
  price: 0,
  volume: 1,
  orderType: 'LIMIT' as Exclude<OrderType, 'MARKET'>,
})
const confirm = useStorage(CONFIRM_KEY, true)
const submitting = ref<Direction | null>(null)

const quote = computed(() => (props.instrument ? market.quotes[props.instrument.instrumentID] : undefined))
const digits = computed(() => decimals(props.instrument?.priceTick))
const tick = computed(() => props.instrument?.priceTick || 1)
const separateToday = computed(() => !!props.instrument && closeTodaySeparately(props.instrument.exchangeID))
const offsets = computed<OffsetFlag[]>(() =>
  separateToday.value ? ['Open', 'CloseToday', 'CloseYesterday'] : ['Open', 'Close'],
)

const depth = computed(() => {
  const q = quote.value
  if (!q) return { asks: [], bids: [] }
  const level = (side: 'ask' | 'bid', n: number) => ({
    label: `${side === 'ask' ? '卖' : '买'}${'一二三四五'[n - 1]}`,
    price: q[`${side}Price${n}` as keyof typeof q] as number | null,
    volume: q[`${side}Volume${n}` as keyof typeof q] as number,
  })
  return {
    asks: [5, 4, 3, 2, 1].map((n) => level('ask', n)).filter((l) => l.price != null),
    bids: [1, 2, 3, 4, 5].map((n) => level('bid', n)).filter((l) => l.price != null),
  }
})

const positionSummary = computed(() => {
  if (!props.instrument) return []
  return trading.positions.filter((p) => p.instrumentID === props.instrument!.instrumentID)
})

/** 平仓方向对应的持仓：买平平空头，卖平平多头 */
const CLOSE_SIDE: Record<Direction, { direction: string; label: string }> = {
  Buy: { direction: 'Short', label: '空头' },
  Sell: { direction: 'Long', label: '多头' },
}

/** 当前开平下该方向可平手数（按平今/平昨/平仓取今仓、昨仓、总仓），开仓时为 null */
function closable(direction: Direction): number | null {
  if (form.offsetFlag === 'Open') return null
  return positionSummary.value
    .filter((p) => p.direction === CLOSE_SIDE[direction].direction)
    .reduce((sum, p) => {
      if (form.offsetFlag === 'CloseToday') return sum + p.todayPosition
      if (form.offsetFlag === 'CloseYesterday') return sum + p.ydPosition
      return sum + p.position
    }, 0)
}

/** 从持仓一键平仓时建议的方向，改为开仓或换合约后取消 */
const suggested = ref<Direction | null>(null)

// 切换合约：价格取最新价（无行情时取昨结算），开平回到开仓
watch(
  () => props.instrument?.instrumentID,
  () => {
    const q = quote.value
    form.price = q?.lastPrice ?? q?.preSettlementPrice ?? 0
    if (!offsets.value.includes(form.offsetFlag)) form.offsetFlag = 'Open'
    suggested.value = null
  },
  { immediate: true },
)

watch(
  () => form.offsetFlag,
  (flag) => {
    if (flag === 'Open') suggested.value = null
  },
)

// 首笔行情到达时补上价格
watch(quote, (q) => {
  if (q && !form.price) form.price = q.lastPrice ?? q.preSettlementPrice ?? 0
})

watch(
  () => props.preset?.nonce,
  () => {
    const p = props.preset
    if (!p) return
    if (p.offsetFlag) form.offsetFlag = offsets.value.includes(p.offsetFlag) ? p.offsetFlag : 'Close'
    suggested.value = p.offsetFlag && p.offsetFlag !== 'Open' ? (p.direction ?? null) : null
    if (p.volume) form.volume = p.volume
    if (p.price != null) {
      form.price = p.price
      form.priceMode = 'limit'
    }
  },
)

/** 按价格类型计算某方向的委托价，null 表示无可用价格 */
function priceFor(direction: Direction): number | null {
  const q = quote.value
  switch (form.priceMode) {
    case 'limit':
      return form.price > 0 ? form.price : null
    case 'opponent':
      return (direction === 'Buy' ? q?.askPrice1 : q?.bidPrice1) ?? null
    case 'queue':
      return (direction === 'Buy' ? q?.bidPrice1 : q?.askPrice1) ?? null
    case 'last':
      return q?.lastPrice ?? null
    case 'market':
      return 0
  }
}

function buttonPrice(direction: Direction): string {
  if (form.priceMode === 'market') return '市价'
  return price(priceFor(direction), digits.value)
}

function pickPrice(value: number | null) {
  if (value == null) return
  form.priceMode = 'limit'
  form.price = value
}

async function place(direction: Direction) {
  const instrument = props.instrument
  if (!instrument) return
  const p = priceFor(direction)
  if (p === null) {
    ElMessage.warning('当前没有可用的委托价格')
    return
  }
  if (!Number.isInteger(form.volume) || form.volume < 1) {
    ElMessage.warning('委托数量须为正整数')
    return
  }
  const orderType: OrderType = form.priceMode === 'market' ? 'MARKET' : form.orderType
  const text = `${DIRECTION[direction]}${OFFSET[form.offsetFlag]} ${instrument.instrumentID} ${form.volume} 手 @ ${
    orderType === 'MARKET' ? '市价' : price(p, digits.value)
  }${orderType === 'FAK' || orderType === 'FOK' ? ` (${orderType})` : ''}`
  // 可平不足（多为点错买卖方向）时无论是否开启确认都提示，柜台会拒绝该报单
  const available = closable(direction)
  if (available !== null && available < form.volume) {
    const side = CLOSE_SIDE[direction].label
    const reason =
      available === 0
        ? `没有可${OFFSET[form.offsetFlag]}的${side}持仓。${DIRECTION[direction]}${OFFSET[form.offsetFlag]}用于平${side}仓位，平${direction === 'Buy' ? '多头请用卖' : '空头请用买'}${OFFSET[form.offsetFlag]}。`
        : `${side}可${OFFSET[form.offsetFlag]}仅 ${available} 手。`
    try {
      await ElMessageBox.confirm(`${reason}<br>${text}，仍要下单？`, '可平数量不足', {
        confirmButtonText: '仍要下单',
        cancelButtonText: '取消',
        type: 'warning',
        dangerouslyUseHTMLString: true,
      })
    } catch {
      return
    }
  } else if (confirm.value) {
    try {
      await ElMessageBox.confirm(text, '确认下单', {
        confirmButtonText: '下单',
        cancelButtonText: '取消',
        type: direction === 'Buy' ? 'error' : 'success',
      })
    } catch {
      return
    }
  }
  submitting.value = direction
  try {
    const order = await trading.submit({
      instrumentId: instrument.instrumentID,
      direction,
      offsetFlag: form.offsetFlag,
      price: p,
      volume: form.volume,
      orderType,
    })
    if (order.submitStatus === 'InsertRejected') ElMessage.error(`报单被拒：${order.statusMsg}`)
    else ElMessage.success(`已报单：${text}`)
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    submitting.value = null
  }
}
</script>

<template>
  <div class="panel order-panel">
    <div class="panel-title">
      下单
      <span v-if="instrument" class="code">{{ instrument.instrumentID }}</span>
      <el-switch v-model="confirm" size="small" active-text="确认" style="margin-left: auto" />
    </div>
    <div class="panel-body content">
      <template v-if="instrument">
        <div class="summary">
          <span class="num big" :class="trendClass(change(quote?.lastPrice, quote?.preSettlementPrice))">
            {{ price(quote?.lastPrice, digits) }}
          </span>
          <span class="num" :class="trendClass(change(quote?.lastPrice, quote?.preSettlementPrice))">
            {{ price(change(quote?.lastPrice, quote?.preSettlementPrice), digits) }}
          </span>
          <span class="muted">量 {{ int(quote?.volume) }}</span>
        </div>
        <div class="depth">
          <div v-for="l in depth.asks" :key="l.label" class="level" @click="pickPrice(l.price)">
            <span class="muted">{{ l.label }}</span>
            <span class="num" :class="trendClass(change(l.price, quote?.preSettlementPrice))">{{ price(l.price, digits) }}</span>
            <span class="num">{{ l.volume }}</span>
          </div>
          <div class="divider" />
          <div v-for="l in depth.bids" :key="l.label" class="level" @click="pickPrice(l.price)">
            <span class="muted">{{ l.label }}</span>
            <span class="num" :class="trendClass(change(l.price, quote?.preSettlementPrice))">{{ price(l.price, digits) }}</span>
            <span class="num">{{ l.volume }}</span>
          </div>
          <div v-if="!quote" class="muted no-quote">暂无行情</div>
        </div>
        <div class="limits">
          <span>涨停 <span class="num up" @click="pickPrice(quote?.upperLimitPrice ?? null)">{{ price(quote?.upperLimitPrice, digits) }}</span></span>
          <span>跌停 <span class="num down" @click="pickPrice(quote?.lowerLimitPrice ?? null)">{{ price(quote?.lowerLimitPrice, digits) }}</span></span>
        </div>

        <el-form label-width="44px" size="small" class="form" @submit.prevent>
          <el-form-item label="开平">
            <el-radio-group v-model="form.offsetFlag">
              <el-radio-button v-for="o in offsets" :key="o" :value="o">{{ OFFSET[o] }}</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="价格">
            <div class="price-row">
              <el-select v-model="form.priceMode" style="width: 82px">
                <el-option v-for="m in PRICE_MODES" :key="m.value" :label="m.label" :value="m.value" />
              </el-select>
              <el-input-number
                v-if="form.priceMode === 'limit'"
                v-model="form.price"
                :step="tick"
                :precision="digits"
                :min="0"
                controls-position="right"
                style="flex: 1"
              />
            </div>
          </el-form-item>
          <el-form-item label="数量">
            <el-input-number v-model="form.volume" :min="1" :step="1" :precision="0" controls-position="right" style="width: 100%" />
          </el-form-item>
          <el-form-item v-if="form.priceMode !== 'market'" label="条件">
            <el-radio-group v-model="form.orderType">
              <el-radio-button value="LIMIT">普通</el-radio-button>
              <el-radio-button value="FAK">FAK</el-radio-button>
              <el-radio-button value="FOK">FOK</el-radio-button>
            </el-radio-group>
          </el-form-item>
        </el-form>

        <div class="buttons">
          <button
            v-for="d in (['Buy', 'Sell'] as Direction[])"
            :key="d"
            class="trade"
            :class="[d === 'Buy' ? 'buy' : 'sell', { suggested: suggested === d, idle: closable(d) === 0 }]"
            :disabled="!!submitting"
            @click="place(d)"
          >
            <span class="num">{{ buttonPrice(d) }}</span>
            <span class="label">
              {{ submitting === d ? '提交中…' : `${DIRECTION[d]}${OFFSET[form.offsetFlag]}` }}
              <small v-if="closable(d) !== null && submitting !== d" class="closable">可平 {{ closable(d) }}</small>
            </span>
          </button>
        </div>
        <div v-if="positionSummary.length" class="positions muted">
          <span v-for="p in positionSummary" :key="p.direction + p.hedgeFlag">
            {{ p.direction === 'Long' ? '多' : p.direction === 'Short' ? '空' : '净' }} {{ p.position }}
            （昨 {{ p.ydPosition }} / 今 {{ p.todayPosition }}）
          </span>
        </div>
      </template>
      <div v-else class="muted placeholder">请选择合约</div>
    </div>
  </div>
</template>

<style scoped>
.code {
  color: var(--code-color);
}

.content {
  padding: 6px 8px;
}

.summary {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 4px;
}

.big {
  font-size: 20px;
  font-weight: 600;
}

.depth {
  border: 1px solid var(--panel-border);
  padding: 2px 0;
}

.level {
  display: grid;
  grid-template-columns: 40px 1fr 60px;
  padding: 1px 8px;
  cursor: pointer;
}

.level:hover {
  background: var(--row-hover-bg);
}

.level > span:nth-child(2),
.level > span:nth-child(3) {
  text-align: right;
}

.divider {
  border-top: 1px dashed var(--divider-color);
  margin: 2px 0;
}

.no-quote {
  padding: 8px;
  text-align: center;
}

.limits {
  display: flex;
  justify-content: space-between;
  padding: 4px 2px;
}

.limits .num {
  cursor: pointer;
}

.form {
  margin-top: 4px;
}

.form :deep(.el-form-item) {
  margin-bottom: 6px;
}

.price-row {
  display: flex;
  gap: 4px;
  width: 100%;
}

.buttons {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-top: 6px;
}

.trade {
  height: 52px;
  border: none;
  border-radius: 4px;
  color: #fff;
  font-size: 14px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
}

.trade:disabled {
  opacity: 0.6;
  cursor: wait;
}

/* 一键平仓建议的方向 */
.trade.suggested {
  outline: 2px solid var(--el-color-warning);
  outline-offset: 2px;
}

/* 没有可平的持仓 */
.trade.idle {
  opacity: 0.55;
}

/* 可平量与按钮文字同一行，开平切换时按钮高度不变 */
.label {
  display: flex;
  align-items: baseline;
  gap: 6px;
  white-space: nowrap;
}

.closable {
  font-size: 11px;
  opacity: 0.85;
}

.buy {
  background: #c23a50;
}

.buy:hover:not(:disabled) {
  background: #d9445c;
}

.sell {
  background: #22914a;
}

.sell:hover:not(:disabled) {
  background: #29a856;
}

.positions {
  margin-top: 6px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.placeholder {
  padding: 24px;
  text-align: center;
}
</style>
