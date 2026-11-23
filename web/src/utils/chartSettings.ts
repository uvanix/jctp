import type { CandleType, Chart } from 'klinecharts'

/** K 线图设置项，对照 klinecharts/pro 的 setting-modal */
export interface ChartSettings {
  /** K 线涨跌配色：红涨绿跌（国内习惯）或绿涨红跌 */
  upColor: 'red' | 'green'
  candleType: CandleType
  lastPrice: boolean
  highPrice: boolean
  lowPrice: boolean
  indicatorLastValue: boolean
  /** 价格轴：线性、百分比、对数（klinecharts 10 的对数轴名为 logarithm） */
  axisType: 'normal' | 'percentage' | 'logarithm'
  reverse: boolean
  grid: boolean
}

/** 与 klinecharts 10 的缺省样式一致，“恢复默认”即回到这里 */
export const DEFAULT_CHART_SETTINGS: ChartSettings = {
  upColor: 'red',
  candleType: 'candle_solid',
  lastPrice: true,
  highPrice: true,
  lowPrice: true,
  indicatorLastValue: false,
  axisType: 'normal',
  reverse: false,
  grid: true,
}

/**
 * 应用到图表。样式部分须在主题样式之后调用（切换主题会整体替换样式）；
 * 价格轴类型与倒置在 klinecharts 10 中属于主图 Y 轴而不是样式
 */
export function applyChartSettings(chart: Chart, settings: ChartSettings, candlePaneId: string) {
  chart.setStyles({
    candle: {
      type: settings.candleType,
      priceMark: {
        last: { show: settings.lastPrice },
        high: { show: settings.highPrice },
        low: { show: settings.lowPrice },
      },
    },
    indicator: { lastValueMark: { show: settings.indicatorLastValue } },
    grid: { show: settings.grid },
  })
  chart.overrideYAxis({ paneId: candlePaneId, name: settings.axisType, reverse: settings.reverse })
}
