import type {
  ContingentCondition,
  CtpOrderType,
  Direction,
  ForceCloseReason,
  HedgeFlag,
  OffsetFlag,
  OrderPriceType,
  OrderSource,
  OrderStatus,
  OrderSubmitStatus,
  PosiDirection,
  PriceSource,
  ProductClass,
  TimeCondition,
  TradeSource,
  TradeType,
  TradingRole,
  VolumeCondition,
} from '@/api/types'

export const EXCHANGES: Record<string, string> = {
  CFFEX: '中金所',
  SHFE: '上期所',
  INE: '能源中心',
  DCE: '大商所',
  CZCE: '郑商所',
  GFEX: '广期所',
}

export const EXCHANGE_ORDER = ['CFFEX', 'SHFE', 'INE', 'DCE', 'CZCE', 'GFEX']

export const PRODUCT_CLASS: Record<ProductClass, string> = {
  Futures: '期货',
  Options: '期权',
  Combination: '组合',
  Spot: '即期',
  EFP: '期转现',
  SpotOption: '现货期权',
  TAS: 'TAS',
  MI: '指数',
}

export const DIRECTION: Record<Direction, string> = { Buy: '买', Sell: '卖' }

export const POSI_DIRECTION: Record<PosiDirection, string> = { Net: '净', Long: '多', Short: '空' }

export const OFFSET: Record<OffsetFlag, string> = {
  Open: '开仓',
  Close: '平仓',
  ForceClose: '强平',
  CloseToday: '平今',
  CloseYesterday: '平昨',
  ForceOff: '强减',
  LocalForceClose: '本地强平',
}

export const ORDER_STATUS: Record<OrderStatus, string> = {
  AllTraded: '全部成交',
  PartTradedQueueing: '部分成交',
  PartTradedNotQueueing: '部成部撤',
  NoTradeQueueing: '未成交',
  NoTradeNotQueueing: '已撤单',
  Canceled: '已撤单',
  Unknown: '已提交',
  NotTouched: '未触发',
  Touched: '已触发',
}

export const SUBMIT_STATUS: Record<OrderSubmitStatus, string> = {
  InsertSubmitted: '已提交',
  CancelSubmitted: '撤单已提交',
  ModifySubmitted: '改单已提交',
  Accepted: '已接受',
  InsertRejected: '已拒绝',
  CancelRejected: '撤单被拒',
  ModifyRejected: '改单被拒',
}

export const HEDGE_FLAG: Record<HedgeFlag, string> = {
  Speculation: '投机',
  Arbitrage: '套利',
  Hedge: '套保',
  MarketMaker: '做市商',
  SpecHedge: '第一腿投机第二腿套保',
  HedgeSpec: '第一腿套保第二腿投机',
}

export const PRICE_TYPE: Record<OrderPriceType, string> = {
  AnyPrice: '任意价',
  LimitPrice: '限价',
  BestPrice: '最优价',
  LastPrice: '最新价',
  AskPrice1: '卖一价',
  BidPrice1: '买一价',
  FiveLevelPrice: '五档价',
}

export const TIME_CONDITION: Record<TimeCondition, string> = {
  IOC: '立即完成，否则撤销',
  GFS: '本节有效',
  GFD: '当日有效',
  GTD: '指定日期前有效',
  GTC: '撤销前有效',
  GFA: '集合竞价有效',
}

export const VOLUME_CONDITION: Record<VolumeCondition, string> = {
  AV: '任何数量',
  MV: '最小数量',
  CV: '全部数量',
}

export const TRADE_TYPE: Record<TradeType, string> = {
  SplitCombination: '组合持仓拆分为单一持仓',
  Common: '普通成交',
  OptionsExecution: '期权执行',
  OTC: 'OTC成交',
  EFPDerived: '期转现衍生成交',
  CombinationDerived: '组合衍生成交',
  BlockTrade: '大宗交易成交',
}

export const CONTINGENT_CONDITION: Record<ContingentCondition, string> = {
  Immediately: '立即',
  Touch: '止损',
  TouchProfit: '止赢',
  ParkedOrder: '预埋单',
  LastPriceGreaterThanStopPrice: '最新价大于条件价',
  LastPriceGreaterEqualStopPrice: '最新价大于等于条件价',
  LastPriceLesserThanStopPrice: '最新价小于条件价',
  LastPriceLesserEqualStopPrice: '最新价小于等于条件价',
  AskPriceGreaterThanStopPrice: '卖一价大于条件价',
  AskPriceGreaterEqualStopPrice: '卖一价大于等于条件价',
  AskPriceLesserThanStopPrice: '卖一价小于条件价',
  AskPriceLesserEqualStopPrice: '卖一价小于等于条件价',
  BidPriceGreaterThanStopPrice: '买一价大于条件价',
  BidPriceGreaterEqualStopPrice: '买一价大于等于条件价',
  BidPriceLesserThanStopPrice: '买一价小于条件价',
  BidPriceLesserEqualStopPrice: '买一价小于等于条件价',
}

export const FORCE_CLOSE_REASON: Record<ForceCloseReason, string> = {
  NotForceClose: '非强平',
  LackDeposit: '资金不足',
  ClientOverPositionLimit: '客户超仓',
  MemberOverPositionLimit: '会员超仓',
  NotMultiple: '持仓非整数倍',
  Violation: '违规',
  Other: '其它',
  PersonDeliv: '自然人临近交割',
}

export const ORDER_SOURCE: Record<OrderSource, string> = {
  Participant: '来自参与者',
  Administrator: '来自管理员',
}

export const CTP_ORDER_TYPE: Record<CtpOrderType, string> = {
  Normal: '正常',
  DeriveFromQuote: '报价衍生',
  DeriveFromCombination: '组合衍生',
  Combination: '组合报单',
  ConditionalOrder: '条件单',
  Swap: '互换单',
  DeriveFromBlockTrade: '大宗交易成交衍生',
  DeriveFromEFPTrade: '期转现成交衍生',
}

export const TRADING_ROLE: Record<TradingRole, string> = {
  Broker: '代理',
  Host: '自营',
  Maker: '做市商',
}

export const PRICE_SOURCE: Record<PriceSource, string> = {
  LastPrice: '前成交价',
  Buy: '买委托价',
  Sell: '卖委托价',
  OTC: '场外成交价',
}

export const TRADE_SOURCE: Record<TradeSource, string> = {
  NORMAL: '来自交易所普通回报',
  QUERY: '来自查询',
}

/** 上期所、能源中心平仓须区分平今/平昨 */
export function closeTodaySeparately(exchangeId: string): boolean {
  return exchangeId === 'SHFE' || exchangeId === 'INE'
}
