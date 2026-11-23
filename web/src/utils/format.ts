/** 价格按最小变动价位的小数位数显示 */
export function decimals(priceTick: number | undefined): number {
  if (!priceTick || priceTick >= 1) return 0
  const text = priceTick.toString()
  if (text.includes('e-')) return Number(text.split('e-')[1])
  return text.split('.')[1]?.length ?? 0
}

export function price(value: number | null | undefined, digits = 2): string {
  return value === null || value === undefined || Number.isNaN(value) ? '--' : value.toFixed(digits)
}

export function money(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '--'
  return value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export function int(value: number | null | undefined): string {
  return value === null || value === undefined ? '--' : value.toLocaleString('zh-CN')
}

export function percent(value: number | null | undefined, digits = 2): string {
  return value === null || value === undefined || !Number.isFinite(value) ? '--' : `${(value * 100).toFixed(digits)}%`
}

/** 涨跌相对昨结算 */
export function change(last: number | null | undefined, base: number | null | undefined): number | null {
  if (last === null || last === undefined || !base) return null
  return last - base
}

/** catch 到的异常转为提示文字（异常不一定是 Error） */
export function errorText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/** 红涨绿跌 */
export function trendClass(value: number | null | undefined): string {
  if (value === null || value === undefined || value === 0) return ''
  return value > 0 ? 'up' : 'down'
}
