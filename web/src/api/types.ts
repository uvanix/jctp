// 与 server 返回的 JSON 一一对应（core 的 struct 记录 + server 的视图）。
// 价格字段在 CTP 无效（DBL_MAX）时为 null。

export type ProductClass = 'Futures' | 'Options' | 'Combination' | 'Spot' | 'EFP' | 'SpotOption' | 'TAS' | 'MI'
export type Direction = 'Buy' | 'Sell'
export type PosiDirection = 'Net' | 'Long' | 'Short'
export type OffsetFlag = 'Open' | 'Close' | 'ForceClose' | 'CloseToday' | 'CloseYesterday' | 'ForceOff' | 'LocalForceClose'
export type HedgeFlag = 'Speculation' | 'Arbitrage' | 'Hedge' | 'MarketMaker' | 'SpecHedge' | 'HedgeSpec'
export type OrderStatus =
  | 'AllTraded'
  | 'PartTradedQueueing'
  | 'PartTradedNotQueueing'
  | 'NoTradeQueueing'
  | 'NoTradeNotQueueing'
  | 'Canceled'
  | 'Unknown'
  | 'NotTouched'
  | 'Touched'
export type OrderSubmitStatus =
  | 'InsertSubmitted'
  | 'CancelSubmitted'
  | 'ModifySubmitted'
  | 'Accepted'
  | 'InsertRejected'
  | 'CancelRejected'
  | 'ModifyRejected'
export type OrderType = 'LIMIT' | 'FAK' | 'FOK' | 'MARKET'
export type OrderPriceType = 'AnyPrice' | 'LimitPrice' | 'BestPrice' | 'LastPrice' | 'AskPrice1' | 'BidPrice1' | 'FiveLevelPrice'
export type TimeCondition = 'IOC' | 'GFS' | 'GFD' | 'GTD' | 'GTC' | 'GFA'
export type VolumeCondition = 'AV' | 'MV' | 'CV'
export type TradeType = 'SplitCombination' | 'Common' | 'OptionsExecution' | 'OTC' | 'EFPDerived' | 'CombinationDerived' | 'BlockTrade'
export type ContingentCondition = 'Immediately' | 'Touch' | 'TouchProfit' | 'ParkedOrder' | 'LastPriceGreaterThanStopPrice' | 'LastPriceGreaterEqualStopPrice' | 'LastPriceLesserThanStopPrice' | 'LastPriceLesserEqualStopPrice' | 'AskPriceGreaterThanStopPrice' | 'AskPriceGreaterEqualStopPrice' | 'AskPriceLesserThanStopPrice' | 'AskPriceLesserEqualStopPrice' | 'BidPriceGreaterThanStopPrice' | 'BidPriceGreaterEqualStopPrice' | 'BidPriceLesserThanStopPrice' | 'BidPriceLesserEqualStopPrice'
export type ForceCloseReason = 'NotForceClose' | 'LackDeposit' | 'ClientOverPositionLimit' | 'MemberOverPositionLimit' | 'NotMultiple' | 'Violation' | 'Other' | 'PersonDeliv'
export type OrderSource = 'Participant' | 'Administrator'
export type CtpOrderType = 'Normal' | 'DeriveFromQuote' | 'DeriveFromCombination' | 'Combination' | 'ConditionalOrder' | 'Swap' | 'DeriveFromBlockTrade' | 'DeriveFromEFPTrade'
export type TradingRole = 'Broker' | 'Host' | 'Maker'
export type PriceSource = 'LastPrice' | 'Buy' | 'Sell' | 'OTC'
export type TradeSource = 'NORMAL' | 'QUERY'
export type BarPeriod = 'M1' | 'M5' | 'M15' | 'M30' | 'H1' | 'D1'

export interface Counter {
  id: string
  name: string
  /** 本地模拟柜台：不连 CTP，任意账号密码可登录 */
  simulated: boolean
}

export interface SessionInfo {
  token: string
  userId: string
  counterId: string
  counterName: string
  /** 本地模拟柜台，API 版本无意义 */
  simulated: boolean
  /** CTP API 版本，如 6.5.1 */
  apiVersion: string
  tradingDay: string
}

/** 登录进度：连接柜台在服务端后台进行，前端轮询至 done / failed */
export interface LoginStatus {
  loginId: string
  state: 'connecting' | 'done' | 'failed'
  /** 当前阶段，如 登录、加载合约 */
  stage: string
  /** 已加载的合约数 */
  instruments: number
  elapsedMillis: number
  error: string | null
  session: SessionInfo | null
}

export interface Product {
  productId: string
  exchangeId: string
  productClass: ProductClass
  name: string
  /** 可交易合约数；服务端尚未加载该品种的合约时为 null */
  count: number | null
  /** 尚未加载且后台正在加载；为 false 的未加载品种（后台查询失败）打开时再查询 */
  loading: boolean
}

export interface Instrument {
  instrumentID: string
  exchangeID: string
  instrumentName: string
  exchangeInstID: string
  productID: string
  productClass: ProductClass
  instLifePhase: string | null
  isTrading: boolean
  priceTick: number
  volumeMultiple: number
  underlyingMultiple: number
  underlyingInstrID: string
  strikePrice: number
  optionsType: 'Call' | 'Put' | null
  createDate: string | null
  openDate: string | null
  expireDate: string | null
  deliveryYear: number
  deliveryMonth: number
  startDelivDate: string | null
  endDelivDate: string | null
  longMarginRatio: number
  shortMarginRatio: number
  maxMarketOrderVolume: number
  minMarketOrderVolume: number
  maxLimitOrderVolume: number
  minLimitOrderVolume: number
  positionType: string | null
}

export interface MarketData {
  instrumentID: string
  exchangeId: string
  systemTimestamp: number
  actionTimestamp: number
  actionTime: string
  actionDay: string
  tradingDay: string
  lastPrice: number | null
  preOpenInterest: number | null
  preClosePrice: number | null
  preSettlementPrice: number | null
  openPrice: number | null
  highestPrice: number | null
  lowestPrice: number | null
  closePrice: number | null
  upperLimitPrice: number | null
  lowerLimitPrice: number | null
  settlementPrice: number | null
  /** 昨虚实度、今虚实度（Delta），多数行情不填，无效时为 null */
  preDelta: number | null
  currDelta: number | null
  /** 当日均价（成交加权）。除郑商所外为 成交额 ÷ 成交量，含合约乘数 */
  averagePrice: number | null
  volume: number
  volumeDelta: number
  turnover: number
  turnoverDelta: number
  openInterest: number
  openInterestDelta: number
  bidPrice1: number | null
  bidVolume1: number
  askPrice1: number | null
  askVolume1: number
  bidPrice2: number | null
  bidVolume2: number
  askPrice2: number | null
  askVolume2: number
  bidPrice3: number | null
  bidVolume3: number
  askPrice3: number | null
  askVolume3: number
  bidPrice4: number | null
  bidVolume4: number
  askPrice4: number | null
  askVolume4: number
  bidPrice5: number | null
  bidVolume5: number
  askPrice5: number | null
  askVolume5: number
}

export interface Bar {
  time: number
  tradingDay: string | null
  open: number
  high: number
  low: number
  close: number
  volume: number
  turnover: number
  openInterest: number
}

export interface Order {
  instrumentID: string
  exchangeID: string
  direction: Direction
  offsetFlag: OffsetFlag
  hedgeFlag: HedgeFlag
  priceType: OrderPriceType | null
  timeCondition: TimeCondition | null
  volumeCondition: VolumeCondition | null
  limitPrice: number
  volume: number
  minVolume: number
  frontID: number
  sessionID: number
  orderRef: string
  orderSysID: string
  submitStatus: OrderSubmitStatus | null
  status: OrderStatus | null
  statusMsg: string
  volumeTraded: number
  volumeTotal: number
  tradingDay: string | null
  insertDate: string | null
  insertTime: string | null
  updateTime: string | null
  cancelTime: string | null
  investorID: string
  userID: string
  accountID: string
  currencyID: string
  gtdDate: string | null
  contingentCondition: ContingentCondition | null
  stopPrice: number
  forceCloseReason: ForceCloseReason | null
  autoSuspend: boolean
  userForceClose: boolean
  swapOrder: boolean
  orderSource: OrderSource | null
  orderType: CtpOrderType | null
  activeTime: string | null
  suspendTime: string | null
  requestID: number
  orderLocalID: string
  brokerOrderSeq: number
  relativeOrderSysID: string
  zceTotalTradedVolume: number
  participantID: string
  clientID: string
  traderID: string
  activeTraderID: string
  clearingPartID: string
  installID: number
  notifySequence: number
  sequenceNo: number
  settlementID: number
  activeUserID: string
  userProductInfo: string
  businessUnit: string
  branchID: string
  investUnitID: string
  ipAddress: string
  macAddress: string
  systemTimestamp: number
}

export interface Trade {
  instrumentID: string
  exchangeID: string
  tradeID: string
  orderRef: string
  orderSysID: string
  direction: Direction
  offsetFlag: OffsetFlag
  hedgeFlag: HedgeFlag
  tradeType: TradeType | null
  price: number
  volume: number
  tradingDay: string | null
  tradeDate: string | null
  tradeTime: string | null
  investorID: string
  userID: string
  tradingRole: TradingRole | null
  priceSource: PriceSource | null
  tradeSource: TradeSource | null
  orderLocalID: string
  brokerOrderSeq: number
  participantID: string
  clientID: string
  traderID: string
  clearingPartID: string
  sequenceNo: number
  settlementID: number
  businessUnit: string
  investUnitID: string
  systemTimestamp: number
}

/** 投资者结算单，content 为柜台返回的全文（定宽排版） */
export interface SettlementInfo {
  tradingDay: string
  settlementID: number
  brokerID: string
  investorID: string
  accountID: string
  currencyID: string
  content: string
}

export interface Account {
  accountID: string
  currencyID: string
  tradingDay: string
  preBalance: number
  balance: number
  available: number
  withdrawQuota: number
  deposit: number
  withdraw: number
  currMargin: number
  exchangeMargin: number
  frozenMargin: number
  frozenCash: number
  frozenCommission: number
  commission: number
  closeProfit: number
  positionProfit: number
  credit: number
  mortgage: number
  preMargin: number
  preCredit: number
  preMortgage: number
  preDeposit: number
  interestBase: number
  interest: number
  cashIn: number
  reserve: number
  reserveBalance: number
  deliveryMargin: number
  exchangeDeliveryMargin: number
  preFundMortgageIn: number
  preFundMortgageOut: number
  fundMortgageIn: number
  fundMortgageOut: number
  fundMortgageAvailable: number
  mortgageableFund: number
  specProductMargin: number
  specProductFrozenMargin: number
  specProductCommission: number
  specProductFrozenCommission: number
  specProductPositionProfit: number
  specProductCloseProfit: number
  specProductPositionProfitByAlg: number
  specProductExchangeMargin: number
  frozenSwap: number
  remainSwap: number
  settlementID: number
}

export interface Position {
  instrumentID: string
  exchangeID: string
  direction: PosiDirection
  hedgeFlag: HedgeFlag
  position: number
  ydPosition: number
  todayPosition: number
  longFrozen: number
  shortFrozen: number
  openVolume: number
  closeVolume: number
  combPosition: number
  strikeFrozen: number
  abandonFrozen: number
  tasPosition: number
  openPrice: number
  positionPrice: number
  openCost: number
  positionCost: number
  openAmount: number
  closeAmount: number
  useMargin: number
  preMargin: number
  exchangeMargin: number
  frozenMargin: number
  frozenCash: number
  frozenCommission: number
  positionProfit: number
  closeProfit: number
  closeProfitByDate: number
  closeProfitByTrade: number
  commission: number
  /** 无效时为 null（JSON 中 NaN 序列化为 null） */
  preSettlementPrice: number | null
  settlementPrice: number | null
  marginRateByMoney: number | null
  marginRateByVolume: number | null
  volumeMultiple: number
}

export interface CommissionRate {
  instrumentID: string
  exchangeID: string
  openRatioByMoney: number
  openRatioByVolume: number
  closeRatioByMoney: number
  closeRatioByVolume: number
  closeTodayRatioByMoney: number
  closeTodayRatioByVolume: number
}

export interface MarginRate {
  instrumentID: string
  exchangeID: string
  hedgeFlag: HedgeFlag
  longMarginRatioByMoney: number
  longMarginRatioByVolume: number
  shortMarginRatioByMoney: number
  shortMarginRatioByVolume: number
  relative: boolean
}

export interface InstrumentRates {
  commission: CommissionRate | null
  margin: MarginRate | null
}

export interface OrderRequest {
  instrumentId: string
  direction: Direction
  offsetFlag: OffsetFlag
  price: number
  volume: number
  orderType: OrderType
}

export function orderId(o: Pick<Order, 'frontID' | 'sessionID' | 'orderRef'>): string {
  return `${o.frontID}_${o.sessionID}_${o.orderRef}`
}

export function tradeId(t: Pick<Trade, 'exchangeID' | 'tradeID' | 'direction'>): string {
  return `${t.exchangeID}_${t.tradeID}_${t.direction}`
}

/** 期权品种（期货期权、现货期权）按 T 型报价展示 */
export function isOptionClass(productClass: ProductClass): boolean {
  return productClass === 'Options' || productClass === 'SpotOption'
}

/** 风险度 = 占用保证金 ÷ 动态权益；权益为 0 时为 null */
export function riskRatio(account: Account): number | null {
  return account.balance ? account.currMargin / account.balance : null
}

/** 报单已终结（全部成交、已撤、被拒），与 core 的 Order.done() 一致 */
export function orderDone(o: Order): boolean {
  return (
    o.submitStatus === 'InsertRejected' ||
    o.status === 'AllTraded' ||
    o.status === 'Canceled' ||
    o.status === 'PartTradedNotQueueing' ||
    o.status === 'NoTradeNotQueueing'
  )
}

/** 下单板预填（如从持仓一键平仓） */
export interface OrderPreset {
  direction?: Direction
  offsetFlag?: OffsetFlag
  volume?: number
  price?: number
  /** 每次预填递增，使相同内容也能触发 */
  nonce: number
}
