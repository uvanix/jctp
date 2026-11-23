/**
 * K 线指标：可选列表、中文名称与参数配置，对照 klinecharts/pro 的 indicator-modal 与
 * indicator-setting-modal/data.ts（Apache License 2.0）。MACD 第三个参数的名称原为“参数2”，已更正。
 */

/** 主图指标（叠加在 K 线上，可多选） */
export const MAIN_INDICATORS = ['MA', 'EMA', 'SMA', 'BOLL', 'SAR', 'BBI']

/** 副图指标（每个一个窗格） */
export const SUB_INDICATORS = [
  'MA', 'EMA', 'VOL', 'MACD', 'BOLL', 'KDJ',
  'RSI', 'BIAS', 'BRAR', 'CCI', 'DMI',
  'CR', 'PSY', 'DMA', 'TRIX', 'OBV',
  'VR', 'WR', 'MTM', 'EMV', 'SAR',
  'SMA', 'ROC', 'PVT', 'BBI', 'AO',
]

export const INDICATOR_NAMES: Record<string, string> = {
  MA: 'MA(移动平均线)',
  EMA: 'EMA(指数平滑移动平均线)',
  SMA: 'SMA',
  BOLL: 'BOLL(布林线)',
  BBI: 'BBI(多空指数)',
  SAR: 'SAR(停损点指向指标)',
  VOL: 'VOL(成交量)',
  MACD: 'MACD(指数平滑异同移动平均线)',
  KDJ: 'KDJ(随机指标)',
  RSI: 'RSI(相对强弱指标)',
  BIAS: 'BIAS(乖离率)',
  BRAR: 'BRAR(情绪指标)',
  CCI: 'CCI(顺势指标)',
  DMI: 'DMI(动向指标)',
  CR: 'CR(能量指标)',
  PSY: 'PSY(心理线)',
  DMA: 'DMA(平行线差指标)',
  TRIX: 'TRIX(三重指数平滑平均线)',
  OBV: 'OBV(能量潮指标)',
  VR: 'VR(成交量变异率)',
  WR: 'WR(威廉指标)',
  MTM: 'MTM(动量指标)',
  EMV: 'EMV(简易波动指标)',
  ROC: 'ROC(变动率指标)',
  PVT: 'PVT(价量趋势指标)',
  AO: 'AO(动量震荡指标)',
}

/**
 * 一个计算参数。没有缺省值的参数可以留空（多条均线类指标，留空即少画一条）
 */
export interface IndicatorParam {
  label: string
  precision: number
  min: number
  default?: number
}

/** 5 条可选的周期线：MA1…MA5 等 */
function lines(prefix: string): IndicatorParam[] {
  return [1, 2, 3, 4, 5].map((i) => ({ label: `${prefix}${i}`, precision: 0, min: 1 }))
}

/** 带缺省值的整数参数：参数1、参数2… */
function params(...defaults: number[]): IndicatorParam[] {
  return defaults.map((d, i) => ({ label: `参数${i + 1}`, precision: 0, min: 1, default: d }))
}

export const INDICATOR_PARAMS: Record<string, IndicatorParam[]> = {
  AO: params(5, 34),
  BIAS: lines('BIAS'),
  BOLL: [
    { label: '周期', precision: 0, min: 1, default: 20 },
    { label: '标准差', precision: 2, min: 1, default: 2 },
  ],
  BRAR: [{ label: '周期', precision: 0, min: 1, default: 26 }],
  BBI: params(3, 6, 12, 24),
  CCI: params(20),
  CR: params(26, 10, 20, 40, 60),
  DMA: params(10, 50, 10),
  DMI: params(14, 6),
  EMV: params(14, 9),
  EMA: lines('EMA'),
  MTM: params(12, 6),
  MA: lines('MA'),
  MACD: params(12, 26, 9),
  OBV: params(30),
  PVT: [],
  PSY: params(12, 6),
  ROC: params(12, 6),
  RSI: lines('RSI'),
  SMA: params(12, 2),
  KDJ: params(9, 3, 3),
  SAR: params(2, 2, 20),
  TRIX: params(12, 9),
  VOL: lines('MA'),
  VR: params(26, 6),
  WR: lines('WR'),
}

/**
 * 按配置整理用户输入：留空的参数取缺省值，没有缺省值的（可选周期线）去掉
 */
export function normalizeParams(name: string, values: (number | null | undefined)[]): number[] {
  const config = INDICATOR_PARAMS[name] ?? []
  const result: number[] = []
  config.forEach((param, i) => {
    const value = values[i]
    if (value != null && !Number.isNaN(value)) result.push(value)
    else if (param.default != null) result.push(param.default)
  })
  return result
}
