<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import {
  dispose,
  init,
  type Chart,
  type DataLoaderGetBarsParams,
  type Indicator,
  type IndicatorCreate,
  type IndicatorTooltipData,
  type KLineData,
  type Period,
  type TooltipFeatureStyle,
} from 'klinecharts'
import { api } from '@/api/http'
import type { BarPeriod, Instrument, MarketData } from '@/api/types'
import DrawingBar from '@/components/DrawingBar.vue'
import ChartSettingDialog from '@/components/ChartSettingDialog.vue'
import IndicatorDialog from '@/components/IndicatorDialog.vue'
import IndicatorSettingDialog from '@/components/IndicatorSettingDialog.vue'
import { useMarketStore } from '@/stores/market'
import { useThemeStore } from '@/stores/theme'
import { decimals } from '@/utils/format'
import { applyChartSettings, DEFAULT_CHART_SETTINGS, type ChartSettings } from '@/utils/chartSettings'
import { useStorage } from '@/utils/storage'

const props = defineProps<{ instrument: Instrument | null }>()

const PERIODS: { value: BarPeriod; label: string; minutes: number; period: Period }[] = [
  { value: 'M1', label: '1分', minutes: 1, period: { type: 'minute', span: 1 } },
  { value: 'M5', label: '5分', minutes: 5, period: { type: 'minute', span: 5 } },
  { value: 'M15', label: '15分', minutes: 15, period: { type: 'minute', span: 15 } },
  { value: 'M30', label: '30分', minutes: 30, period: { type: 'minute', span: 30 } },
  { value: 'H1', label: '1时', minutes: 60, period: { type: 'hour', span: 1 } },
  { value: 'D1', label: '日线', minutes: 0, period: { type: 'day', span: 1 } },
]
const CANDLE_PANE = 'candle_pane'
const SUB_PANE_HEIGHT = 80
const INDICATOR_KEY = 'jctp.indicators'
const SETTINGS_KEY = 'jctp.chartSettings'

/** 指标标题上的图标（12px 内的描边路径）：显示/隐藏、设置、关闭 */
const FEATURE_PATHS = {
  visible: 'M1 6Q6 -1 11 6Q6 13 1 6ZM7.6 6A1.6 1.6 0 1 1 4.4 6A1.6 1.6 0 1 1 7.6 6Z',
  invisible: 'M1 6Q6 -1 11 6Q6 13 1 6ZM2 1L10 11',
  setting: 'M1 3H11M1 6H11M1 9H11M4 1.5V4.5M8 4.5V7.5M5 7.5V10.5',
  close: 'M2 2L10 10M10 2L2 10',
}
const FLAT = '#888888'
const OFFSET = 8 * 3600_000

const market = useMarketStore()
const theme = useThemeStore()
const container = ref<HTMLDivElement>()
const period = ref<BarPeriod>('M1')
/** 选择的主图、副图指标与各指标的计算参数，保存在本地 */
const indicators = useStorage<{ main: string[]; sub: string[]; params: Record<string, number[]> }>(INDICATOR_KEY, {
  main: ['MA'],
  sub: ['VOL'],
  params: {},
})
const indicatorDialog = ref(false)
/** K 线设置（蜡烛图类型、价格标记、价格轴等），保存在本地 */
const settings = useStorage<ChartSettings>(SETTINGS_KEY, { ...DEFAULT_CHART_SETTINGS })
const settingDialog = ref(false)
const setting = ref({ visible: false, name: '', calcParams: [] as number[] })
const loading = ref(false)
const empty = ref(false)
let chart: Chart | null = null
/** 交给画图工具栏的图表实例 */
const chartRef = shallowRef<Chart | null>(null)
const drawingBar = ref<InstanceType<typeof DrawingBar>>()
let resizeObserver: ResizeObserver | null = null
let offTick: (() => void) | null = null
let loadSeq = 0
/** 图表订阅实时数据后给出的回调：行情推送合成当前 K 线后经它写入图表 */
let pushBar: ((bar: KLineData) => void) | null = null

/** 与服务端 BarPeriod 相同的分桶规则：分钟周期按北京时间对齐，日线按交易日 */
function bucket(tick: MarketData): number {
  if (period.value === 'D1') {
    const [y, m, d] = (tick.tradingDay ?? '').split('-').map(Number)
    if (y && m && d) return Date.UTC(y, m - 1, d) - OFFSET
  }
  const minutes = PERIODS.find((p) => p.value === period.value)?.minutes || 1440
  const size = minutes * 60_000
  const local = tick.actionTimestamp + OFFSET
  return local - (((local % size) + size) % size) - OFFSET
}

function onTick(tick: MarketData) {
  if (!chart || !pushBar || !props.instrument || tick.instrumentID !== props.instrument.instrumentID) return
  const p = tick.lastPrice
  if (p == null || p <= 0) return
  const time = bucket(tick)
  const list = chart.getDataList()
  const last = list[list.length - 1]
  const volume = Math.max(tick.volumeDelta, 0)
  if (last && time === last.timestamp) {
    pushBar({
      ...last,
      high: Math.max(last.high, p),
      low: Math.min(last.low, p),
      close: p,
      volume: (last.volume ?? 0) + volume,
    })
  } else if (!last || time > last.timestamp) {
    pushBar({ timestamp: time, open: p, high: p, low: p, close: p, volume })
    empty.value = false
  }
}

/**
 * 图表在设置合约或周期后请求历史数据（type 为 init）。服务端一次返回全部 K 线，没有更早或更新的分页。
 * 切换过快时图表不会丢弃过期应答，这里按序号丢弃，只回调最新一次请求。
 */
async function getBars({ type, callback }: DataLoaderGetBarsParams) {
  if (type !== 'init') {
    callback([], false)
    return
  }
  const instrument = props.instrument
  const seq = ++loadSeq
  empty.value = false
  if (!instrument) {
    callback([], false)
    return
  }
  loading.value = true
  try {
    const bars = await api.bars(instrument.instrumentID, period.value)
    if (seq !== loadSeq) return
    const data: KLineData[] = bars.map((b) => ({
      timestamp: b.time,
      open: b.open,
      high: b.high,
      low: b.low,
      close: b.close,
      volume: b.volume,
      turnover: b.turnover,
    }))
    callback(data, false)
    empty.value = data.length === 0
    // 画图按时间定位，K 线就绪后再画出该合约保存的画图
    drawingBar.value?.restore()
  } catch (e) {
    // 图表不处理 getBars 的异常，必须回调，否则一直处于加载中
    console.error('K 线加载失败', e)
    if (seq === loadSeq) callback([], false)
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

/** 切换合约：设置合约与精度后图表自动重新加载；未选合约时清空 */
function load() {
  if (!chart) return
  const instrument = props.instrument
  if (instrument) {
    chart.setSymbol({ ticker: instrument.instrumentID, pricePrecision: decimals(instrument.priceTick), volumePrecision: 0 })
  } else {
    chart.resetData()
  }
}

function subPane(name: string): string {
  return `sub_${name}`
}

function feature(id: keyof typeof FEATURE_PATHS): TooltipFeatureStyle {
  return {
    id,
    position: 'middle',
    marginLeft: 6,
    marginTop: 1,
    marginRight: 0,
    marginBottom: 0,
    paddingLeft: 1,
    paddingTop: 1,
    paddingRight: 1,
    paddingBottom: 1,
    size: 12,
    color: css('--muted-color'),
    activeColor: css('--text-strong'),
    backgroundColor: 'transparent',
    activeBackgroundColor: css('--row-hover-bg'),
    borderRadius: 2,
    type: 'path',
    content: { path: FEATURE_PATHS[id], style: 'stroke', lineWidth: 1.2 },
  }
}

/** 指标标题后的图标，与 Pro 一致：显示时为“隐藏”，隐藏时为“显示”，以及设置、关闭 */
function tooltipFeatures({ indicator }: { indicator: Indicator }): IndicatorTooltipData {
  // 只覆盖 features，名称、参数与数值沿用默认
  return {
    features: [feature(indicator.visible ? 'visible' : 'invisible'), feature('setting'), feature('close')],
  } as unknown as IndicatorTooltipData
}

function indicatorCreate(name: string, paneId: string): IndicatorCreate {
  const calcParams = indicators.value.params[name]
  return { name, paneId, ...(calcParams ? { calcParams } : {}), createTooltipDataSource: tooltipFeatures }
}

function addMain(name: string) {
  chart?.createIndicator(indicatorCreate(name, CANDLE_PANE), true)
}

function addSub(name: string) {
  if (!chart) return
  chart.createIndicator(indicatorCreate(name, subPane(name)))
  chart.setPaneOptions({ id: subPane(name), height: SUB_PANE_HEIGHT })
}

function toggleMain(name: string) {
  const list = indicators.value.main
  if (list.includes(name)) {
    chart?.removeIndicator({ paneId: CANDLE_PANE, name })
    indicators.value.main = list.filter((n) => n !== name)
  } else {
    addMain(name)
    indicators.value.main = [...list, name]
  }
}

function toggleSub(name: string) {
  const list = indicators.value.sub
  if (list.includes(name)) {
    chart?.removeIndicator({ paneId: subPane(name) })
    indicators.value.sub = list.filter((n) => n !== name)
  } else {
    addSub(name)
    indicators.value.sub = [...list, name]
  }
}

/** 同名指标（主图与副图）共用一组参数 */
function applyParams(calcParams: number[]) {
  const name = setting.value.name
  indicators.value.params[name] = calcParams
  chart?.getIndicators({ name }).forEach((i) => chart?.overrideIndicator({ name, paneId: i.paneId, calcParams }))
}

function onFeatureClick(data: unknown) {
  const { paneId, feature: f, indicator } = data as { paneId: string; feature: { id: string }; indicator?: Indicator }
  if (!chart || !indicator) return
  switch (f.id) {
    case 'visible':
    case 'invisible':
      chart.overrideIndicator({ name: indicator.name, paneId, visible: f.id === 'invisible' })
      break
    case 'setting':
      setting.value = { visible: true, name: indicator.name, calcParams: [...(indicator.calcParams as number[])] }
      break
    case 'close':
      if (paneId === CANDLE_PANE) toggleMain(indicator.name)
      else toggleSub(indicator.name)
      break
  }
}

/** 读取 style.css 中当前主题的颜色变量 */
function css(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

/** #rrggbb → rgba */
function alpha(hex: string, a: number): string {
  const n = parseInt(hex.slice(1), 16)
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${a})`
}

/** 先套用 klinecharts 内置的明/暗主题，再覆盖网格与涨跌颜色（取自 CSS 变量，随主题变化） */
function applyStyles() {
  if (!chart) return
  chart.setStyles(theme.theme)
  // 主题变量 --up 为红、--down 为绿；设置为绿涨红跌时对调
  const greenUp = settings.value.upColor === 'green'
  const UP = css(greenUp ? '--down' : '--up')
  const DOWN = css(greenUp ? '--up' : '--down')
  chart.setStyles({
    grid: { horizontal: { color: css('--chart-grid') }, vertical: { color: css('--chart-grid') } },
    candle: {
      bar: {
        upColor: UP,
        downColor: DOWN,
        noChangeColor: FLAT,
        upBorderColor: UP,
        downBorderColor: DOWN,
        noChangeBorderColor: FLAT,
        upWickColor: UP,
        downWickColor: DOWN,
        noChangeWickColor: FLAT,
      },
      priceMark: { last: { upColor: UP, downColor: DOWN, noChangeColor: FLAT } },
    },
    indicator: {
      ohlc: { upColor: UP, downColor: DOWN, noChangeColor: FLAT },
      bars: [
        {
          style: 'fill',
          borderStyle: 'solid',
          borderSize: 1,
          borderDashedValue: [2, 2],
          upColor: alpha(UP, 0.7),
          downColor: alpha(DOWN, 0.7),
          noChangeColor: FLAT,
        },
      ],
    },
  })
  // 主题样式会整体替换，设置项须在其后重新应用
  applyChartSettings(chart, settings.value, CANDLE_PANE)
}

function createChart() {
  chart = init(container.value!, { locale: 'zh-CN', timezone: 'Asia/Shanghai' })
  if (!chart) return
  chartRef.value = chart
  applyStyles()
  indicators.value.main.forEach(addMain)
  indicators.value.sub.forEach(addSub)
  chart.subscribeAction('onIndicatorTooltipFeatureClick', onFeatureClick)
  chart.setPeriod(PERIODS.find((p) => p.value === period.value)!.period)
  chart.setDataLoader({
    getBars,
    subscribeBar: ({ callback }) => {
      pushBar = callback
    },
    unsubscribeBar: () => {
      pushBar = null
    },
  })
  offTick = market.onTick(onTick)
  load()
}

onMounted(() => {
  // 分割面板挂载后才计算尺寸，此时容器还是 0×0；在 0 尺寸上初始化，主图的坐标轴与可视范围会按 0 计算而不显示，
  // 直到重新加载数据才恢复。因此等容器有了尺寸再创建图表，之后的尺寸变化再 resize（忽略折叠为 0 的情况）
  resizeObserver = new ResizeObserver(([entry]) => {
    if (!entry?.contentRect.width || !entry.contentRect.height) return
    if (chart) chart.resize()
    else createChart()
  })
  resizeObserver.observe(container.value!)
})

onBeforeUnmount(() => {
  offTick?.()
  resizeObserver?.disconnect()
  if (container.value) dispose(container.value)
  chart = null
  chartRef.value = null
})

watch(() => props.instrument?.instrumentID, load)
watch(period, (value) => chart?.setPeriod(PERIODS.find((p) => p.value === value)!.period))
// 涨跌配色在 applyStyles 中设置，它最后也会应用其余设置项
watch(settings, applyStyles, { deep: true })
// html 上的 dark 类在主题 store 的侦听器里切换，post 保证读取 CSS 变量时已是新主题
watch(() => theme.theme, applyStyles, { flush: 'post' })
</script>

<template>
  <div class="panel chart-panel">
    <div class="panel-title">
      <span v-if="instrument" class="name">{{ instrument.instrumentID }} {{ instrument.instrumentName }}</span>
      <span v-else class="muted">双击行情列表中的合约查看 K 线</span>
      <el-radio-group v-model="period" size="small" class="periods">
        <el-radio-button v-for="p in PERIODS" :key="p.value" :value="p.value">{{ p.label }}</el-radio-button>
      </el-radio-group>
      <el-button size="small" @click="indicatorDialog = true">指标</el-button>
      <el-button size="small" class="setting-button" @click="settingDialog = true">设置</el-button>
    </div>
    <IndicatorDialog
      v-model="indicatorDialog"
      :main="indicators.main"
      :sub="indicators.sub"
      @toggle-main="toggleMain"
      @toggle-sub="toggleSub"
    />
    <ChartSettingDialog v-model="settingDialog" v-model:settings="settings" />
    <IndicatorSettingDialog
      v-model="setting.visible"
      :name="setting.name"
      :calc-params="setting.calcParams"
      @confirm="applyParams"
    />
    <div class="body">
      <DrawingBar ref="drawingBar" :chart="chartRef" :instrument-id="instrument?.instrumentID ?? null" />
      <div class="chart-area" v-loading="loading" element-loading-background="var(--mask-bg)">
        <div ref="container" class="chart" />
        <div v-if="empty && instrument" class="empty muted">
          暂无 K 线：K 线由服务端从实时行情合成，仅包含订阅之后的数据，收到行情后自动生成
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chart-panel {
  height: 100%;
}

.name {
  color: var(--code-color);
}

.periods {
  margin-left: auto;
}

/* 与“指标”按钮紧挨 */
.setting-button {
  margin-left: 4px !important;
}

.body {
  flex: 1;
  min-height: 0;
  display: flex;
}

.chart-area {
  position: relative;
  flex: 1;
  min-width: 0;
}

.chart {
  position: absolute;
  inset: 0;
}

.empty {
  position: absolute;
  top: 40%;
  left: 0;
  right: 0;
  text-align: center;
  pointer-events: none;
}
</style>
