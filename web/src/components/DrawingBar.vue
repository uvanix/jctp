<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import type { Chart, Overlay, OverlayCreate, OverlayMode, Point } from 'klinecharts'
import { DRAWING_ICONS } from '@/utils/drawingIcons'
import { OPEN_ENDED_OVERLAYS, registerDrawingOverlays } from '@/utils/drawingOverlays'
import { useStorage } from '@/utils/storage'

/**
 * 画图工具栏，布局与交互对照 klinecharts/pro 的 drawing-bar：
 * 单线、多线、多边形、斐波那契、波浪五组（点图标画当前工具，点右侧箭头展开），磁铁、锁定、显示、删除。
 * 另外按合约保存画图，切换合约、刷新页面后恢复。
 */
const props = defineProps<{ chart: Chart | null; instrumentId: string | null }>()

const GROUP_ID = 'drawing_tools'
/** 展开箭头（取自 Pro） */
const ARROW =
  'M1.07298,0.159458C0.827521,-0.0531526,0.429553,-0.0531526,0.184094,0.159458C-0.0613648,0.372068,-0.0613648,0.716778,0.184094,0.929388L2.61275,3.03303L0.260362,5.07061C0.0149035,5.28322,0.0149035,5.62793,0.260362,5.84054C0.505822,6.05315,0.903789,6.05315,1.14925,5.84054L3.81591,3.53075C4.01812,3.3556,4.05374,3.0908,3.92279,2.88406C3.93219,2.73496,3.87113,2.58315,3.73964,2.46925L1.07298,0.159458Z'
const STORAGE_KEY = 'jctp.drawings'

interface Option {
  key: string
  text: string
}

const GROUPS: { key: string; options: Option[] }[] = [
  {
    key: 'singleLine',
    options: [
      { key: 'horizontalStraightLine', text: '水平直线' },
      { key: 'horizontalRayLine', text: '水平射线' },
      { key: 'horizontalSegment', text: '水平线段' },
      { key: 'verticalStraightLine', text: '垂直直线' },
      { key: 'verticalRayLine', text: '垂直射线' },
      { key: 'verticalSegment', text: '垂直线段' },
      { key: 'straightLine', text: '直线' },
      { key: 'rayLine', text: '射线' },
      { key: 'segment', text: '线段' },
      { key: 'arrow', text: '箭头' },
      { key: 'priceLine', text: '价格线' },
    ],
  },
  {
    key: 'moreLine',
    options: [
      { key: 'priceChannelLine', text: '价格通道线' },
      { key: 'parallelStraightLine', text: '平行直线' },
    ],
  },
  {
    key: 'polygon',
    options: [
      { key: 'circle', text: '圆' },
      { key: 'rect', text: '矩形' },
      { key: 'parallelogram', text: '平行四边形' },
      { key: 'triangle', text: '三角形' },
    ],
  },
  {
    key: 'fibonacci',
    options: [
      { key: 'fibonacciLine', text: '斐波那契回调直线' },
      { key: 'fibonacciSegment', text: '斐波那契回调线段' },
      { key: 'fibonacciCircle', text: '斐波那契圆环' },
      { key: 'fibonacciSpiral', text: '斐波那契螺旋' },
      { key: 'fibonacciSpeedResistanceFan', text: '斐波那契速度阻力扇' },
      { key: 'fibonacciExtension', text: '斐波那契趋势扩展' },
      { key: 'gannBox', text: '江恩箱' },
    ],
  },
  {
    key: 'wave',
    options: [
      { key: 'xabcd', text: 'XABCD形态' },
      { key: 'abcd', text: 'ABCD形态' },
      { key: 'threeWaves', text: '三浪' },
      { key: 'fiveWaves', text: '五浪' },
      { key: 'eightWaves', text: '八浪' },
      { key: 'anyWaves', text: '任意浪（右键结束）' },
    ],
  },
]

const MAGNETS: Option[] = [
  { key: 'weak_magnet', text: '弱磁模式' },
  { key: 'strong_magnet', text: '强磁模式' },
]

/** 持久化的画图：只保存时间、价格，恢复时重新创建 */
interface Saved {
  name: string
  points: Partial<Point>[]
  extendData?: unknown
}

const saved = useStorage<Record<string, Saved[]>>(STORAGE_KEY, {})
/** 各组当前显示的工具：默认值与 Pro 相同 */
const icons = reactive<Record<string, string>>({
  singleLine: 'horizontalStraightLine',
  moreLine: 'priceChannelLine',
  polygon: 'circle',
  fibonacci: 'fibonacciLine',
  wave: 'xabcd',
})
const modeIcon = ref<OverlayMode>('weak_magnet')
const mode = ref<OverlayMode>('normal')
const lock = ref(false)
const visible = ref(true)
const popoverKey = ref('')
const selectedId = ref<string | null>(null)
/** 程序批量删除、重建时不触发保存 */
let suspended = false
/** 画面上的画图所属合约：切换合约时先清空，加载完数据后再恢复 */
let shownFor: string | null = null

function drawings(): Overlay[] {
  return props.chart?.getOverlays({ groupId: GROUP_ID }) ?? []
}

/** 画完（currentStep 为 -1）的画图写入当前合约 */
function save() {
  if (suspended || !shownFor) return
  const list = drawings()
    .filter((o) => o.currentStep === -1)
    .map((o) => ({
      name: o.name,
      points: o.points.map((p) => ({ timestamp: p.timestamp, value: p.value })),
      extendData: o.extendData,
    }))
  if (list.length) saved.value[shownFor] = list
  else delete saved.value[shownFor]
}

function withoutSaving(action: () => void) {
  suspended = true
  try {
    action()
  } finally {
    suspended = false
  }
}

function overlayOptions(): Partial<OverlayCreate> {
  return {
    groupId: GROUP_ID,
    visible: visible.value,
    lock: lock.value,
    mode: mode.value,
    onDrawEnd: () => save(),
    onPressedMoveEnd: () => save(),
    onRemoved: ({ overlay }) => {
      if (overlay.id === selectedId.value) selectedId.value = null
      save()
    },
    onSelected: ({ overlay }) => {
      selectedId.value = overlay.id
    },
    onDeselected: ({ overlay }) => {
      if (overlay.id === selectedId.value) selectedId.value = null
    },
    // 任意浪点数不限：绘制中右键结束（去掉跟随鼠标的末点），不足两点时按默认删除
    onRightClick: ({ overlay, chart, preventDefault }) => {
      if (!OPEN_ENDED_OVERLAYS.includes(overlay.name) || overlay.currentStep === -1) return
      const points = overlay.points.slice(0, -1)
      if (points.length < 2) return
      preventDefault?.()
      chart.overrideOverlay({ id: overlay.id, points })
      save()
    },
  }
}

function onDrawingItemClick(name: string) {
  const chart = props.chart
  if (!chart || !props.instrumentId) return
  // 换工具时丢弃未画完的
  withoutSaving(() =>
    drawings()
      .filter((o) => o.currentStep !== -1)
      .forEach((o) => chart.removeOverlay({ id: o.id })),
  )
  chart.createOverlay({ name, ...overlayOptions() })
}

function togglePopover(key: string) {
  popoverKey.value = popoverKey.value === key ? '' : key
}

function onModeChange(value: OverlayMode) {
  mode.value = value
  props.chart?.overrideOverlay({ groupId: GROUP_ID, mode: value })
}

function onLockChange() {
  lock.value = !lock.value
  props.chart?.overrideOverlay({ groupId: GROUP_ID, lock: lock.value })
}

function onVisibleChange() {
  visible.value = !visible.value
  props.chart?.overrideOverlay({ groupId: GROUP_ID, visible: visible.value })
}

function onRemoveClick() {
  props.chart?.removeOverlay({ groupId: GROUP_ID })
}

/**
 * 重新画出当前合约保存的画图。图表加载完 K 线（切换合约、周期）后调用：先清掉画面上的再重建，避免重复
 */
function restore() {
  const chart = props.chart
  if (!chart) return
  withoutSaving(() => {
    chart.removeOverlay({ groupId: GROUP_ID })
    shownFor = props.instrumentId
    for (const item of (shownFor && saved.value[shownFor]) || []) {
      chart.createOverlay({ name: item.name, points: item.points, extendData: item.extendData, ...overlayOptions() })
    }
  })
}

defineExpose({ restore })

// 切换合约先清空，数据加载完由图表调用 restore
watch(
  () => props.instrumentId,
  () => {
    shownFor = null
    withoutSaving(() => props.chart?.removeOverlay({ groupId: GROUP_ID }))
  },
)

/** 选中的画图按 Delete/Backspace 删除（右键删除为 klinecharts 默认行为） */
function onKeydown(e: KeyboardEvent) {
  if ((e.key !== 'Delete' && e.key !== 'Backspace') || !selectedId.value) return
  const target = e.target as HTMLElement | null
  if (target && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))) return
  props.chart?.removeOverlay({ id: selectedId.value })
}

onMounted(() => {
  registerDrawingOverlays()
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="drawing-bar" :class="{ disabled: !instrumentId }">
    <div v-for="g in GROUPS" :key="g.key" class="item" tabindex="0" @blur="popoverKey = ''">
      <span class="icon-wrap" :title="g.options.find((o) => o.key === icons[g.key])?.text" @click="onDrawingItemClick(icons[g.key]!)">
        <svg class="icon-overlay" viewBox="0 0 22 22" v-html="DRAWING_ICONS[icons[g.key]!]" />
      </span>
      <div class="icon-arrow" @click="togglePopover(g.key)">
        <svg :class="{ rotate: popoverKey === g.key }" viewBox="0 0 4 6"><path :d="ARROW" /></svg>
      </div>
      <ul v-if="popoverKey === g.key" class="list">
        <li
          v-for="o in g.options"
          :key="o.key"
          @click="
            () => {
              icons[g.key] = o.key
              onDrawingItemClick(o.key)
              popoverKey = ''
            }
          "
        >
          <svg class="icon-overlay" viewBox="0 0 22 22" v-html="DRAWING_ICONS[o.key]" />
          <span class="text">{{ o.text }}</span>
        </li>
      </ul>
    </div>

    <span class="split-line" />

    <div class="item" tabindex="0" @blur="popoverKey = ''">
      <span
        class="icon-wrap"
        :title="MAGNETS.find((m) => m.key === modeIcon)?.text"
        @click="onModeChange(mode !== 'normal' ? 'normal' : modeIcon)"
      >
        <svg class="icon-overlay" :class="{ selected: mode === modeIcon }" viewBox="0 0 22 22" v-html="DRAWING_ICONS[modeIcon]" />
      </span>
      <div class="icon-arrow" @click="togglePopover('mode')">
        <svg :class="{ rotate: popoverKey === 'mode' }" viewBox="0 0 4 6"><path :d="ARROW" /></svg>
      </div>
      <ul v-if="popoverKey === 'mode'" class="list">
        <li
          v-for="m in MAGNETS"
          :key="m.key"
          @click="
            () => {
              modeIcon = m.key as OverlayMode
              onModeChange(m.key as OverlayMode)
              popoverKey = ''
            }
          "
        >
          <svg class="icon-overlay" viewBox="0 0 22 22" v-html="DRAWING_ICONS[m.key]" />
          <span class="text">{{ m.text }}</span>
        </li>
      </ul>
    </div>
    <div class="item">
      <span class="icon-wrap" :title="lock ? '解锁' : '锁定'" @click="onLockChange">
        <svg class="icon-overlay" viewBox="0 0 22 22" v-html="DRAWING_ICONS[lock ? 'lock' : 'unlock']" />
      </span>
    </div>
    <div class="item">
      <span class="icon-wrap" :title="visible ? '隐藏' : '显示'" @click="onVisibleChange">
        <svg class="icon-overlay" viewBox="0 0 22 22" v-html="DRAWING_ICONS[visible ? 'visible' : 'invisible']" />
      </span>
    </div>

    <span class="split-line" />

    <div class="item">
      <span class="icon-wrap" title="删除全部画图" @click="onRemoveClick">
        <svg class="icon-overlay" viewBox="0 0 22 22" v-html="DRAWING_ICONS.remove" />
      </span>
    </div>
  </div>
</template>

<style scoped>
/* 布局对照 klinecharts/pro drawing-bar，整体按约 0.8 缩小；颜色取本项目主题变量 */
.drawing-bar {
  --bar-width: 40px;
  --icon-size: 26px;
  --gap: 5px;
  --menu-item-height: 32px;
  width: var(--bar-width);
  height: 100%;
  box-sizing: border-box;
  border-right: 1px solid var(--panel-border);
  background: var(--panel-bg);
}

.drawing-bar.disabled {
  opacity: 0.45;
  pointer-events: none;
}

.item {
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  width: 100%;
  margin-top: var(--gap);
  cursor: pointer;
  outline: none;
  color: var(--text-secondary);
  fill: var(--text-secondary);
  stroke: var(--text-secondary);
}

.icon-wrap {
  width: var(--icon-size);
  height: var(--icon-size);
}

.icon-overlay {
  width: var(--icon-size);
  height: var(--icon-size);
  border-radius: 2px;
  transition: all 0.2s;
}

.icon-wrap .icon-overlay:hover {
  background-color: var(--row-hover-bg);
}

.icon-overlay.selected {
  background-color: var(--row-active-bg) !important;
  fill: var(--el-color-primary);
  stroke: var(--el-color-primary);
}

.icon-arrow {
  display: flex;
  align-items: center;
  justify-content: center;
  position: absolute;
  top: 0;
  right: 0;
  height: var(--icon-size);
  width: 8px;
  opacity: 0;
  transition: all 0.2s;
  border-top-left-radius: 2px;
  border-bottom-left-radius: 2px;
  z-index: 10;
}

.icon-arrow svg {
  width: 4px;
  height: 6px;
  transition: all 0.2s;
}

.icon-arrow svg.rotate {
  transform: rotate(180deg);
}

.icon-arrow:hover {
  background-color: var(--row-hover-bg);
}

.item:hover .icon-arrow {
  opacity: 1;
}

.list {
  position: absolute;
  top: 0;
  left: calc(100% + 1px);
  z-index: 99;
  margin: 0;
  padding: 0;
  list-style: none;
  white-space: nowrap;
  max-height: calc(var(--menu-item-height) * 8);
  overflow-y: auto;
  font-size: 13px;
  color: var(--text-color);
  background-color: var(--panel-bg);
  box-shadow: 0 6px 12px 0 rgba(0, 0, 0, 0.3);
}

.list li {
  display: flex;
  align-items: center;
  height: var(--menu-item-height);
  padding: 0 16px 0 12px;
  box-sizing: border-box;
}

.list li:hover {
  background-color: var(--row-hover-bg);
}

.list .text {
  padding-left: 8px;
}

.split-line {
  display: block;
  width: 100%;
  height: 1px;
  margin-top: var(--gap);
  background-color: var(--panel-border);
}
</style>
