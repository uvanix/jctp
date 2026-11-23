/**
 * 画图工具扩展，移植自 klinecharts/pro 的 src/extension（Apache License 2.0），适配 klinecharts 10：
 * - v10 的 createPointFigures 不再传入 precision，价格精度取自 chart.getSymbol()
 * - 平行四边形前两点同价：原实现写的是 points[].price（无此字段，未生效），改为 value
 *
 * klinecharts 自带直线、射线、线段、价格线、通道、斐波那契回调直线，这里补充其余 17 个。
 */
import {
  registerOverlay,
  utils,
  type Bounding,
  type Chart,
  type CircleAttrs,
  type Coordinate,
  type LineAttrs,
  type OverlayTemplate,
  type PolygonAttrs,
  type TextAttrs,
} from 'klinecharts'

const FILL = 'rgba(22, 119, 255, 0.15)'

function pricePrecision(chart: Chart): number {
  return chart.getSymbol()?.pricePrecision ?? 2
}

function getRotateCoordinate(coordinate: Coordinate, target: Coordinate, angle: number): Coordinate {
  return {
    x: (coordinate.x - target.x) * Math.cos(angle) - (coordinate.y - target.y) * Math.sin(angle) + target.x,
    y: (coordinate.x - target.x) * Math.sin(angle) + (coordinate.y - target.y) * Math.cos(angle) + target.y,
  }
}

function getRayLine(coordinates: Coordinate[], bounding: Bounding): LineAttrs[] {
  if (coordinates.length < 2) return []
  const [a, b] = coordinates as [Coordinate, Coordinate]
  let end: Coordinate
  if (a.x === b.x && a.y !== b.y) {
    end = { x: a.x, y: a.y < b.y ? bounding.height : 0 }
  } else if (a.x > b.x) {
    end = { x: 0, y: utils.getLinearYFromCoordinates(a, b, { x: 0, y: a.y }) }
  } else {
    end = { x: bounding.width, y: utils.getLinearYFromCoordinates(a, b, { x: bounding.width, y: a.y }) }
  }
  return [{ coordinates: [a, end] }]
}

function getDistance(a: Coordinate, b: Coordinate): number {
  return Math.hypot(a.x - b.x, a.y - b.y)
}

/** 由 a 指向 b 的方向角 */
function offsetAngle(a: Coordinate, b: Coordinate): number {
  const kb = utils.getLinearSlopeIntercept(a, b)
  if (kb) return Math.atan(kb[0]!) + Math.PI * (b.x > a.x ? 0 : 1)
  return b.y > a.y ? Math.PI / 2 : (Math.PI / 2) * 3
}

const DEFAULTS = { needDefaultPointFigure: true, needDefaultXAxisFigure: true, needDefaultYAxisFigure: true }

const arrow: OverlayTemplate = {
  name: 'arrow',
  totalStep: 3,
  ...DEFAULTS,
  createPointFigures: ({ coordinates }) => {
    if (coordinates.length < 2) return []
    const [a, b] = coordinates as [Coordinate, Coordinate]
    const angle = offsetAngle(a, b)
    const wing1 = getRotateCoordinate({ x: b.x - 8, y: b.y + 4 }, b, angle)
    const wing2 = getRotateCoordinate({ x: b.x - 8, y: b.y - 4 }, b, angle)
    return [
      { type: 'line', attrs: { coordinates } },
      { type: 'line', ignoreEvent: true, attrs: { coordinates: [wing1, b, wing2] } },
    ]
  },
}

const circle: OverlayTemplate = {
  name: 'circle',
  totalStep: 3,
  ...DEFAULTS,
  styles: { circle: { color: FILL } },
  createPointFigures: ({ coordinates }) => {
    if (coordinates.length < 2) return []
    const [a, b] = coordinates as [Coordinate, Coordinate]
    return { type: 'circle', attrs: { ...a, r: getDistance(a, b) }, styles: { style: 'stroke_fill' } }
  },
}

const rect: OverlayTemplate = {
  name: 'rect',
  totalStep: 3,
  ...DEFAULTS,
  styles: { polygon: { color: FILL } },
  createPointFigures: ({ coordinates }) => {
    if (coordinates.length < 2) return []
    const [a, b] = coordinates as [Coordinate, Coordinate]
    return [
      {
        type: 'polygon',
        attrs: { coordinates: [a, { x: b.x, y: a.y }, b, { x: a.x, y: b.y }] },
        styles: { style: 'stroke_fill' },
      },
    ]
  },
}

const triangle: OverlayTemplate = {
  name: 'triangle',
  totalStep: 4,
  ...DEFAULTS,
  styles: { polygon: { color: FILL } },
  createPointFigures: ({ coordinates }) => [{ type: 'polygon', attrs: { coordinates }, styles: { style: 'stroke_fill' } }],
}

/** 前两点同价构成水平底边，第三点确定对边 */
const parallelogram: OverlayTemplate = {
  name: 'parallelogram',
  totalStep: 4,
  ...DEFAULTS,
  styles: { polygon: { color: FILL } },
  createPointFigures: ({ coordinates }) => {
    if (coordinates.length === 2) {
      return [{ type: 'line', ignoreEvent: true, attrs: { coordinates } }]
    }
    if (coordinates.length === 3) {
      const [a, b, c] = coordinates as [Coordinate, Coordinate, Coordinate]
      return [
        {
          type: 'polygon',
          attrs: { coordinates: [a, b, c, { x: a.x + (c.x - b.x), y: c.y }] },
          styles: { style: 'stroke_fill' },
        },
      ]
    }
    return []
  },
  performEventPressedMove: ({ points, performPointIndex, performPoint }) => {
    if (performPointIndex < 2) {
      points[0]!.value = performPoint.value
      points[1]!.value = performPoint.value
    }
  },
  performEventMoveForDrawing: ({ currentStep, points, performPoint }) => {
    if (currentStep === 2) points[0]!.value = performPoint.value
  },
}

const fibonacciSegment: OverlayTemplate = {
  name: 'fibonacciSegment',
  totalStep: 3,
  ...DEFAULTS,
  createPointFigures: ({ chart, coordinates, overlay }) => {
    const lines: LineAttrs[] = []
    const texts: TextAttrs[] = []
    const [p0, p1] = overlay.points
    if (coordinates.length > 1 && p0?.value != null && p1?.value != null) {
      const [a, b] = coordinates as [Coordinate, Coordinate]
      const textX = Math.min(a.x, b.x)
      const yDif = a.y - b.y
      const valueDif = p0.value - p1.value
      const digits = pricePrecision(chart)
      for (const percent of [1, 0.786, 0.618, 0.5, 0.382, 0.236, 0]) {
        const y = b.y + yDif * percent
        const price = (p1.value + valueDif * percent).toFixed(digits)
        lines.push({ coordinates: [{ x: a.x, y }, { x: b.x, y }] })
        texts.push({ x: textX, y, text: `${price} (${(percent * 100).toFixed(1)}%)`, baseline: 'bottom' })
      }
    }
    return [
      { type: 'line', attrs: lines },
      { type: 'text', ignoreEvent: true, attrs: texts },
    ]
  },
}

const fibonacciCircle: OverlayTemplate = {
  name: 'fibonacciCircle',
  totalStep: 3,
  ...DEFAULTS,
  createPointFigures: ({ coordinates }) => {
    if (coordinates.length < 2) return []
    const [a, b] = coordinates as [Coordinate, Coordinate]
    const radius = getDistance(a, b)
    const circles: CircleAttrs[] = []
    const texts: TextAttrs[] = []
    for (const percent of [0.236, 0.382, 0.5, 0.618, 0.786, 1]) {
      const r = radius * percent
      circles.push({ ...a, r })
      texts.push({ x: a.x, y: a.y + r + 6, text: `${(percent * 100).toFixed(1)}%` })
    }
    return [
      { type: 'circle', attrs: circles, styles: { style: 'stroke' } },
      { type: 'text', ignoreEvent: true, attrs: texts },
    ]
  },
}

const fibonacciSpiral: OverlayTemplate = {
  name: 'fibonacciSpiral',
  totalStep: 3,
  ...DEFAULTS,
  createPointFigures: ({ coordinates, bounding }) => {
    if (coordinates.length < 2) return []
    const [a, b] = coordinates as [Coordinate, Coordinate]
    const startRadius = getDistance(a, b) / Math.sqrt(24)
    const angle = offsetAngle(a, b)
    const arcs = [
      {
        ...getRotateCoordinate({ x: a.x - startRadius, y: a.y }, a, angle),
        r: startRadius,
        startAngle: angle,
        endAngle: angle + Math.PI / 2,
      },
      {
        ...getRotateCoordinate({ x: a.x - startRadius, y: a.y - startRadius }, a, angle),
        r: startRadius * 2,
        startAngle: angle + Math.PI / 2,
        endAngle: angle + Math.PI,
      },
    ]
    let x = a.x - startRadius
    let y = a.y - startRadius
    for (let i = 2; i < 9; i++) {
      const r = arcs[i - 2]!.r + arcs[i - 1]!.r
      let startAngle = 0
      switch (i % 4) {
        case 0:
          startAngle = angle
          x -= arcs[i - 2]!.r
          break
        case 1:
          startAngle = angle + Math.PI / 2
          y -= arcs[i - 2]!.r
          break
        case 2:
          startAngle = angle + Math.PI
          x += arcs[i - 2]!.r
          break
        case 3:
          startAngle = angle + (Math.PI / 2) * 3
          y += arcs[i - 2]!.r
          break
      }
      arcs.push({ ...getRotateCoordinate({ x, y }, a, angle), r, startAngle, endAngle: startAngle + Math.PI / 2 })
    }
    return [
      { type: 'arc', attrs: arcs },
      { type: 'line', attrs: getRayLine(coordinates, bounding) },
    ]
  },
}

const fibonacciSpeedResistanceFan: OverlayTemplate = {
  name: 'fibonacciSpeedResistanceFan',
  totalStep: 3,
  ...DEFAULTS,
  createPointFigures: ({ coordinates, bounding }) => {
    const lines1: LineAttrs[] = []
    let lines2: LineAttrs[] = []
    const texts: TextAttrs[] = []
    if (coordinates.length > 1) {
      const [a, b] = coordinates as [Coordinate, Coordinate]
      const xOffset = b.x > a.x ? -38 : 4
      const yOffset = b.y > a.y ? -2 : 20
      const xDistance = b.x - a.x
      const yDistance = b.y - a.y
      for (const percent of [1, 0.75, 0.618, 0.5, 0.382, 0.25, 0]) {
        const x = b.x - xDistance * percent
        const y = b.y - yDistance * percent
        lines1.push({ coordinates: [{ x, y: a.y }, { x, y: b.y }] })
        lines1.push({ coordinates: [{ x: a.x, y }, { x: b.x, y }] })
        lines2 = lines2.concat(getRayLine([a, { x, y: b.y }], bounding))
        lines2 = lines2.concat(getRayLine([a, { x: b.x, y }], bounding))
        texts.unshift({ x: a.x + xOffset, y: y + 10, text: percent.toFixed(3) })
        texts.unshift({ x: x - 18, y: a.y + yOffset, text: percent.toFixed(3) })
      }
    }
    return [
      { type: 'line', attrs: lines1 },
      { type: 'line', attrs: lines2 },
      { type: 'text', ignoreEvent: true, attrs: texts },
    ]
  },
}

const fibonacciExtension: OverlayTemplate = {
  name: 'fibonacciExtension',
  totalStep: 4,
  ...DEFAULTS,
  createPointFigures: ({ chart, coordinates, overlay }) => {
    const fbLines: LineAttrs[] = []
    const texts: TextAttrs[] = []
    const [p0, p1, p2] = overlay.points
    if (coordinates.length > 2 && p0?.value != null && p1?.value != null && p2?.value != null) {
      const [a, b, c] = coordinates as [Coordinate, Coordinate, Coordinate]
      const valueDif = p1.value - p0.value
      const yDif = b.y - a.y
      const textX = Math.min(b.x, c.x)
      const digits = pricePrecision(chart)
      for (const percent of [0, 0.236, 0.382, 0.5, 0.618, 0.786, 1]) {
        const y = c.y + yDif * percent
        const price = (p2.value + valueDif * percent).toFixed(digits)
        fbLines.push({ coordinates: [{ x: b.x, y }, { x: c.x, y }] })
        texts.push({ x: textX, y, text: `${price} (${(percent * 100).toFixed(1)}%)`, baseline: 'bottom' })
      }
    }
    return [
      { type: 'line', attrs: { coordinates }, styles: { style: 'dashed' } },
      { type: 'line', attrs: fbLines },
      { type: 'text', ignoreEvent: true, attrs: texts },
    ]
  },
}

const gannBox: OverlayTemplate = {
  name: 'gannBox',
  totalStep: 3,
  ...DEFAULTS,
  styles: { polygon: { color: FILL } },
  createPointFigures: ({ coordinates }) => {
    if (coordinates.length < 2) return []
    const [a, b] = coordinates as [Coordinate, Coordinate]
    const quarterYDis = (b.y - a.y) / 4
    const xDis = b.x - a.x
    const dashedLines = [
      { coordinates: [a, { x: b.x, y: b.y - quarterYDis }] },
      { coordinates: [a, { x: b.x, y: b.y - quarterYDis * 2 }] },
      { coordinates: [{ x: a.x, y: b.y }, { x: b.x, y: a.y + quarterYDis }] },
      { coordinates: [{ x: a.x, y: b.y }, { x: b.x, y: a.y + quarterYDis * 2 }] },
      { coordinates: [{ ...a }, { x: a.x + xDis * 0.236, y: b.y }] },
      { coordinates: [{ ...a }, { x: a.x + xDis * 0.5, y: b.y }] },
      { coordinates: [{ x: a.x, y: b.y }, { x: a.x + xDis * 0.236, y: a.y }] },
      { coordinates: [{ x: a.x, y: b.y }, { x: a.x + xDis * 0.5, y: a.y }] },
    ]
    const solidLines = [
      { coordinates: [a, b] },
      { coordinates: [{ x: a.x, y: b.y }, { x: b.x, y: a.y }] },
    ]
    return [
      {
        type: 'line',
        attrs: [
          { coordinates: [a, { x: b.x, y: a.y }] },
          { coordinates: [{ x: b.x, y: a.y }, b] },
          { coordinates: [b, { x: a.x, y: b.y }] },
          { coordinates: [{ x: a.x, y: b.y }, a] },
        ],
      },
      {
        type: 'polygon',
        ignoreEvent: true,
        attrs: { coordinates: [a, { x: b.x, y: a.y }, b, { x: a.x, y: b.y }] },
        styles: { style: 'fill' },
      },
      { type: 'line', attrs: dashedLines, styles: { style: 'dashed' } },
      { type: 'line', attrs: solidLines },
    ]
  },
}

/** 三浪、五浪、八浪、任意浪：折线并按序号标注 */
function waves(name: string, totalStep: number): OverlayTemplate {
  return {
    name,
    totalStep,
    ...DEFAULTS,
    createPointFigures: ({ coordinates }) => [
      { type: 'line', attrs: { coordinates } },
      {
        type: 'text',
        ignoreEvent: true,
        attrs: coordinates.map((c, i) => ({ ...c, text: `(${i})`, baseline: 'bottom' })),
      },
    ],
  }
}

const abcd: OverlayTemplate = {
  name: 'abcd',
  totalStep: 5,
  ...DEFAULTS,
  createPointFigures: ({ coordinates }) => {
    let ac: Coordinate[] = []
    let bd: Coordinate[] = []
    const tags = ['A', 'B', 'C', 'D']
    const texts = coordinates.map((c, i) => ({ ...c, baseline: 'bottom', text: `(${tags[i]})` }))
    if (coordinates.length > 2) {
      ac = [coordinates[0]!, coordinates[2]!]
      if (coordinates.length > 3) bd = [coordinates[1]!, coordinates[3]!]
    }
    return [
      { type: 'line', attrs: { coordinates } },
      { type: 'line', attrs: [{ coordinates: ac }, { coordinates: bd }], styles: { style: 'dashed' } },
      { type: 'text', ignoreEvent: true, attrs: texts },
    ]
  },
}

const xabcd: OverlayTemplate = {
  name: 'xabcd',
  totalStep: 6,
  ...DEFAULTS,
  styles: { polygon: { color: FILL } },
  createPointFigures: ({ coordinates }) => {
    const dashedLines: LineAttrs[] = []
    const polygons: PolygonAttrs[] = []
    const tags = ['X', 'A', 'B', 'C', 'D']
    const texts = coordinates.map((c, i) => ({ ...c, baseline: 'bottom', text: `(${tags[i]})` }))
    const [x, a, b, c, d] = coordinates
    if (x && a && b) {
      dashedLines.push({ coordinates: [x, b] })
      polygons.push({ coordinates: [x, a, b] })
      if (c) {
        dashedLines.push({ coordinates: [a, c] })
        if (d) {
          dashedLines.push({ coordinates: [b, d] })
          polygons.push({ coordinates: [b, c, d] })
        }
      }
    }
    return [
      { type: 'line', attrs: { coordinates } },
      { type: 'line', attrs: dashedLines, styles: { style: 'dashed' } },
      { type: 'polygon', ignoreEvent: true, attrs: polygons },
      { type: 'text', ignoreEvent: true, attrs: texts },
    ]
  },
}

/** 任意浪点数不限，绘制中右键结束（见 DrawingBar） */
export const OPEN_ENDED_OVERLAYS = ['anyWaves']

let registered = false

/** 注册扩展画图工具，重复调用无副作用 */
export function registerDrawingOverlays() {
  if (registered) return
  registered = true
  ;[
    arrow,
    circle,
    rect,
    triangle,
    parallelogram,
    fibonacciCircle,
    fibonacciSegment,
    fibonacciSpiral,
    fibonacciSpeedResistanceFan,
    fibonacciExtension,
    gannBox,
    waves('threeWaves', 5),
    waves('fiveWaves', 7),
    waves('eightWaves', 10),
    waves('anyWaves', Number.MAX_SAFE_INTEGER),
    abcd,
    xabcd,
  ].forEach((template) => registerOverlay(template))
}
