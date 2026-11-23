<script setup lang="ts">
import { DEFAULT_CHART_SETTINGS, type ChartSettings } from '@/utils/chartSettings'

/**
 * K 线设置，对照 klinecharts/pro 的 setting-modal：蜡烛图类型、价格标记、指标最新值、价格轴、网格，修改即生效
 */
const visible = defineModel<boolean>({ required: true })
const settings = defineModel<ChartSettings>('settings', { required: true })

const UP_COLORS = [
  { value: 'red', label: '红涨绿跌' },
  { value: 'green', label: '绿涨红跌' },
]

const CANDLE_TYPES = [
  { value: 'candle_solid', label: '全实心' },
  { value: 'candle_stroke', label: '全空心' },
  { value: 'candle_up_stroke', label: '涨空心' },
  { value: 'candle_down_stroke', label: '跌空心' },
  { value: 'ohlc', label: 'OHLC' },
  { value: 'area', label: '面积图' },
]

const AXIS_TYPES = [
  { value: 'normal', label: '线性轴' },
  { value: 'percentage', label: '百分比轴' },
  { value: 'logarithm', label: '对数轴' },
]

function update<K extends keyof ChartSettings>(key: K, value: ChartSettings[K]) {
  settings.value = { ...settings.value, [key]: value }
}

function restoreDefault() {
  settings.value = { ...DEFAULT_CHART_SETTINGS }
  visible.value = false
}
</script>

<template>
  <el-dialog v-model="visible" title="设置" width="560px" append-to-body>
    <div class="content">
      <span>蜡烛图类型</span>
      <el-select :model-value="settings.candleType" size="small" style="width: 120px" @update:model-value="update('candleType', $event)">
        <el-option v-for="t in CANDLE_TYPES" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
      <span>最新价显示</span>
      <el-switch :model-value="settings.lastPrice" @update:model-value="update('lastPrice', !!$event)" />
      <span>最高价显示</span>
      <el-switch :model-value="settings.highPrice" @update:model-value="update('highPrice', !!$event)" />
      <span>最低价显示</span>
      <el-switch :model-value="settings.lowPrice" @update:model-value="update('lowPrice', !!$event)" />
      <span>指标最新值显示</span>
      <el-switch :model-value="settings.indicatorLastValue" @update:model-value="update('indicatorLastValue', !!$event)" />
      <span>价格轴类型</span>
      <el-select :model-value="settings.axisType" size="small" style="width: 120px" @update:model-value="update('axisType', $event)">
        <el-option v-for="t in AXIS_TYPES" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
      <span>倒置坐标</span>
      <el-switch :model-value="settings.reverse" @update:model-value="update('reverse', !!$event)" />
      <span>网格线显示</span>
      <el-switch :model-value="settings.grid" @update:model-value="update('grid', !!$event)" />
      <span>K线颜色</span>
      <el-select :model-value="settings.upColor" size="small" style="width: 120px" @update:model-value="update('upColor', $event)">
        <el-option v-for="c in UP_COLORS" :key="c.value" :label="c.label" :value="c.value" />
      </el-select>
    </div>
    <template #footer>
      <el-button size="small" @click="restoreDefault">恢复默认</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 与 Pro 一致：两组“名称 + 控件”一行 */
.content {
  display: grid;
  grid-template-columns: auto auto auto auto;
  row-gap: 20px;
  column-gap: 12px;
  align-items: center;
  margin: 8px 0 12px;
}
</style>
