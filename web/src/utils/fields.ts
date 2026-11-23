import type { Account, Order, Position, Trade } from '@/api/types'
import {
  CONTINGENT_CONDITION,
  CTP_ORDER_TYPE,
  DIRECTION,
  EXCHANGES,
  FORCE_CLOSE_REASON,
  HEDGE_FLAG,
  OFFSET,
  ORDER_SOURCE,
  ORDER_STATUS,
  POSI_DIRECTION,
  PRICE_SOURCE,
  PRICE_TYPE,
  SUBMIT_STATUS,
  TIME_CONDITION,
  TRADE_SOURCE,
  TRADE_TYPE,
  TRADING_ROLE,
  VOLUME_CONDITION,
} from './dict'
import { int, money, percent, price } from './format'

/**
 * 字段显示格式。money 金额、price 价格（按合约小数位）、avgPrice 均价（多一位小数）、int 数量、
 * ratio 比率、bool 是否、text 原样；也可直接给字典
 */
export type FieldFormat = 'money' | 'price' | 'avgPrice' | 'int' | 'ratio' | 'bool' | 'text' | Record<string, string>

export interface FieldDef<T> {
  key: keyof T & string
  label: string
  format?: FieldFormat
  /** 盈亏类数值按正负着色 */
  trend?: boolean
}

export interface FieldGroup<T> {
  title: string
  fields: FieldDef<T>[]
}

export function fieldValue<T>(row: T, f: FieldDef<T>): unknown {
  return row[f.key]
}

export function isNumeric<T>(f: FieldDef<T>): boolean {
  return f.format === 'money' || f.format === 'price' || f.format === 'avgPrice' || f.format === 'int' || f.format === 'ratio'
}

/** @param digits 价格小数位数 */
export function formatField<T>(row: T, f: FieldDef<T>, digits = 2): string {
  const v = fieldValue(row, f)
  const format = f.format ?? 'text'
  if (typeof format === 'object') return v == null || v === '' ? '--' : (format[v as string] ?? String(v))
  switch (format) {
    case 'money':
      return money(v as number | null)
    case 'price':
      return v === 0 ? '--' : price(v as number | null, digits)
    case 'avgPrice':
      return price(v as number | null, digits + 1)
    case 'int':
      return int(v as number | null)
    case 'ratio':
      return percent(v as number | null)
    case 'bool':
      return v ? '是' : '否'
    default:
      return v == null || v === '' ? '--' : String(v)
  }
}

export const POSITION_FIELDS: FieldGroup<Position>[] = [
  {
    title: '持仓',
    fields: [
      { key: 'instrumentID', label: '合约' },
      { key: 'exchangeID', label: '交易所', format: EXCHANGES },
      { key: 'direction', label: '多空', format: POSI_DIRECTION },
      { key: 'hedgeFlag', label: '投保', format: HEDGE_FLAG },
      { key: 'position', label: '总持仓', format: 'int' },
      { key: 'ydPosition', label: '昨仓', format: 'int' },
      { key: 'todayPosition', label: '今仓', format: 'int' },
      { key: 'combPosition', label: '组合持仓', format: 'int' },
      { key: 'tasPosition', label: 'TAS 持仓', format: 'int' },
      { key: 'volumeMultiple', label: '合约乘数', format: 'int' },
    ],
  },
  {
    title: '冻结与开平',
    fields: [
      { key: 'longFrozen', label: '多头冻结', format: 'int' },
      { key: 'shortFrozen', label: '空头冻结', format: 'int' },
      { key: 'strikeFrozen', label: '执行冻结', format: 'int' },
      { key: 'abandonFrozen', label: '放弃执行冻结', format: 'int' },
      { key: 'openVolume', label: '今日开仓量', format: 'int' },
      { key: 'closeVolume', label: '今日平仓量', format: 'int' },
      { key: 'openAmount', label: '今日开仓金额', format: 'money' },
      { key: 'closeAmount', label: '今日平仓金额', format: 'money' },
    ],
  },
  {
    title: '价格与成本',
    fields: [
      { key: 'openPrice', label: '开仓均价', format: 'avgPrice' },
      { key: 'positionPrice', label: '持仓均价', format: 'avgPrice' },
      { key: 'preSettlementPrice', label: '昨结算价', format: 'price' },
      { key: 'settlementPrice', label: '结算价', format: 'price' },
      { key: 'openCost', label: '开仓成本', format: 'money' },
      { key: 'positionCost', label: '持仓成本', format: 'money' },
    ],
  },
  {
    title: '保证金与冻结资金',
    fields: [
      { key: 'useMargin', label: '占用保证金', format: 'money' },
      { key: 'preMargin', label: '上次占用保证金', format: 'money' },
      { key: 'exchangeMargin', label: '交易所保证金', format: 'money' },
      { key: 'marginRateByMoney', label: '保证金率', format: 'ratio' },
      { key: 'marginRateByVolume', label: '保证金（按手）', format: 'money' },
      { key: 'frozenMargin', label: '冻结保证金', format: 'money' },
      { key: 'frozenCash', label: '冻结资金', format: 'money' },
      { key: 'frozenCommission', label: '冻结手续费', format: 'money' },
    ],
  },
  {
    title: '盈亏与费用',
    fields: [
      { key: 'positionProfit', label: '持仓盈亏（盯市）', format: 'money', trend: true },
      { key: 'closeProfit', label: '平仓盈亏', format: 'money', trend: true },
      { key: 'closeProfitByDate', label: '逐日盯市平仓盈亏', format: 'money', trend: true },
      { key: 'closeProfitByTrade', label: '逐笔对冲平仓盈亏', format: 'money', trend: true },
      { key: 'commission', label: '手续费', format: 'money' },
    ],
  },
]

export const ORDER_FIELDS: FieldGroup<Order>[] = [
  {
    title: '报单',
    fields: [
      { key: 'instrumentID', label: '合约' },
      { key: 'exchangeID', label: '交易所', format: EXCHANGES },
      { key: 'direction', label: '买卖', format: DIRECTION },
      { key: 'offsetFlag', label: '开平', format: OFFSET },
      { key: 'hedgeFlag', label: '投保', format: HEDGE_FLAG },
      { key: 'priceType', label: '价格条件', format: PRICE_TYPE },
      { key: 'limitPrice', label: '价格', format: 'price' },
      { key: 'stopPrice', label: '止损价', format: 'price' },
      { key: 'volume', label: '委托数量', format: 'int' },
      { key: 'minVolume', label: '最小成交量', format: 'int' },
      { key: 'timeCondition', label: '有效期', format: TIME_CONDITION },
      { key: 'gtdDate', label: 'GTD 日期' },
      { key: 'volumeCondition', label: '成交量类型', format: VOLUME_CONDITION },
      { key: 'contingentCondition', label: '触发条件', format: CONTINGENT_CONDITION },
      { key: 'forceCloseReason', label: '强平原因', format: FORCE_CLOSE_REASON },
      { key: 'autoSuspend', label: '自动挂起', format: 'bool' },
      { key: 'userForceClose', label: '用户强平', format: 'bool' },
      { key: 'swapOrder', label: '互换单', format: 'bool' },
    ],
  },
  {
    title: '状态',
    fields: [
      { key: 'submitStatus', label: '提交状态', format: SUBMIT_STATUS },
      { key: 'status', label: '报单状态', format: ORDER_STATUS },
      { key: 'statusMsg', label: '状态信息' },
      { key: 'volumeTraded', label: '已成交', format: 'int' },
      { key: 'volumeTotal', label: '剩余', format: 'int' },
      { key: 'zceTotalTradedVolume', label: '郑商所成交数量', format: 'int' },
      { key: 'orderType', label: '报单类型', format: CTP_ORDER_TYPE },
      { key: 'orderSource', label: '报单来源', format: ORDER_SOURCE },
    ],
  },
  {
    title: '时间',
    fields: [
      { key: 'tradingDay', label: '交易日' },
      { key: 'insertDate', label: '报单日期' },
      { key: 'insertTime', label: '委托时间' },
      { key: 'activeTime', label: '激活时间' },
      { key: 'suspendTime', label: '挂起时间' },
      { key: 'updateTime', label: '最后修改时间' },
      { key: 'cancelTime', label: '撤销时间' },
    ],
  },
  {
    title: '编号',
    fields: [
      { key: 'frontID', label: '前置编号' },
      { key: 'sessionID', label: '会话编号' },
      { key: 'orderRef', label: '报单引用' },
      { key: 'orderSysID', label: '交易所报单编号' },
      { key: 'orderLocalID', label: '本地报单编号' },
      { key: 'relativeOrderSysID', label: '相关报单' },
      { key: 'brokerOrderSeq', label: '经纪公司报单编号' },
      { key: 'requestID', label: '请求编号' },
      { key: 'sequenceNo', label: '序号' },
      { key: 'notifySequence', label: '报单提示序号' },
      { key: 'settlementID', label: '结算编号' },
    ],
  },
  {
    title: '账户与席位',
    fields: [
      { key: 'investorID', label: '投资者代码' },
      { key: 'userID', label: '用户代码' },
      { key: 'accountID', label: '资金账号' },
      { key: 'currencyID', label: '币种' },
      { key: 'clientID', label: '交易编码' },
      { key: 'participantID', label: '会员代码' },
      { key: 'clearingPartID', label: '结算会员编号' },
      { key: 'traderID', label: '交易所交易员' },
      { key: 'activeTraderID', label: '最后修改交易员' },
      { key: 'installID', label: '安装编号' },
      { key: 'activeUserID', label: '操作用户' },
      { key: 'branchID', label: '营业部编号' },
      { key: 'businessUnit', label: '业务单元' },
      { key: 'investUnitID', label: '投资单元' },
      { key: 'userProductInfo', label: '产品信息' },
      { key: 'ipAddress', label: 'IP 地址' },
      { key: 'macAddress', label: 'Mac 地址' },
    ],
  },
]

export const TRADE_FIELDS: FieldGroup<Trade>[] = [
  {
    title: '成交',
    fields: [
      { key: 'instrumentID', label: '合约' },
      { key: 'exchangeID', label: '交易所', format: EXCHANGES },
      { key: 'direction', label: '买卖', format: DIRECTION },
      { key: 'offsetFlag', label: '开平', format: OFFSET },
      { key: 'hedgeFlag', label: '投保', format: HEDGE_FLAG },
      { key: 'price', label: '成交价', format: 'price' },
      { key: 'volume', label: '数量', format: 'int' },
      { key: 'tradeType', label: '成交类型', format: TRADE_TYPE },
      { key: 'priceSource', label: '成交价来源', format: PRICE_SOURCE },
      { key: 'tradingRole', label: '交易角色', format: TRADING_ROLE },
      { key: 'tradeSource', label: '成交来源', format: TRADE_SOURCE },
    ],
  },
  {
    title: '时间',
    fields: [
      { key: 'tradingDay', label: '交易日' },
      { key: 'tradeDate', label: '成交日期' },
      { key: 'tradeTime', label: '成交时间' },
    ],
  },
  {
    title: '编号',
    fields: [
      { key: 'tradeID', label: '成交编号' },
      { key: 'orderSysID', label: '交易所报单编号' },
      { key: 'orderRef', label: '报单引用' },
      { key: 'orderLocalID', label: '本地报单编号' },
      { key: 'brokerOrderSeq', label: '经纪公司报单编号' },
      { key: 'sequenceNo', label: '序号' },
      { key: 'settlementID', label: '结算编号' },
    ],
  },
  {
    title: '账户与席位',
    fields: [
      { key: 'investorID', label: '投资者代码' },
      { key: 'userID', label: '用户代码' },
      { key: 'clientID', label: '交易编码' },
      { key: 'participantID', label: '会员代码' },
      { key: 'clearingPartID', label: '结算会员编号' },
      { key: 'traderID', label: '交易所交易员' },
      { key: 'businessUnit', label: '业务单元' },
      { key: 'investUnitID', label: '投资单元' },
    ],
  },
]

export const ACCOUNT_FIELDS: FieldGroup<Account>[] = [
  {
    title: '权益',
    fields: [
      { key: 'balance', label: '动态权益', format: 'money' },
      { key: 'available', label: '可用资金', format: 'money' },
      { key: 'withdrawQuota', label: '可取资金', format: 'money' },
      { key: 'preBalance', label: '上日结存', format: 'money' },
      { key: 'deposit', label: '入金', format: 'money' },
      { key: 'withdraw', label: '出金', format: 'money' },
      { key: 'preDeposit', label: '上次存款额', format: 'money' },
      { key: 'cashIn', label: '资金差额', format: 'money' },
      { key: 'reserve', label: '基本准备金', format: 'money' },
      { key: 'reserveBalance', label: '保底结算准备金', format: 'money' },
      { key: 'credit', label: '信用额度', format: 'money' },
      { key: 'preCredit', label: '上次信用额度', format: 'money' },
      { key: 'mortgage', label: '质押金额', format: 'money' },
      { key: 'preMortgage', label: '上次质押金额', format: 'money' },
    ],
  },
  {
    title: '保证金与冻结',
    fields: [
      { key: 'currMargin', label: '占用保证金', format: 'money' },
      { key: 'preMargin', label: '上次占用保证金', format: 'money' },
      { key: 'exchangeMargin', label: '交易所保证金', format: 'money' },
      { key: 'deliveryMargin', label: '交割保证金', format: 'money' },
      { key: 'exchangeDeliveryMargin', label: '交易所交割保证金', format: 'money' },
      { key: 'frozenMargin', label: '冻结保证金', format: 'money' },
      { key: 'frozenCash', label: '冻结资金', format: 'money' },
      { key: 'frozenCommission', label: '冻结手续费', format: 'money' },
    ],
  },
  {
    title: '盈亏与费用',
    fields: [
      { key: 'positionProfit', label: '持仓盈亏', format: 'money', trend: true },
      { key: 'closeProfit', label: '平仓盈亏', format: 'money', trend: true },
      { key: 'commission', label: '手续费', format: 'money' },
      { key: 'interest', label: '利息收入', format: 'money' },
      { key: 'interestBase', label: '利息基数', format: 'money' },
    ],
  },
  {
    title: '货币质押与换汇',
    fields: [
      { key: 'fundMortgageIn', label: '货币质入', format: 'money' },
      { key: 'fundMortgageOut', label: '货币质出', format: 'money' },
      { key: 'preFundMortgageIn', label: '上次货币质入', format: 'money' },
      { key: 'preFundMortgageOut', label: '上次货币质出', format: 'money' },
      { key: 'fundMortgageAvailable', label: '货币质押余额', format: 'money' },
      { key: 'mortgageableFund', label: '可质押货币', format: 'money' },
      { key: 'frozenSwap', label: '延时换汇冻结', format: 'money' },
      { key: 'remainSwap', label: '剩余换汇额度', format: 'money' },
    ],
  },
  {
    title: '特殊产品',
    fields: [
      { key: 'specProductMargin', label: '占用保证金', format: 'money' },
      { key: 'specProductExchangeMargin', label: '交易所保证金', format: 'money' },
      { key: 'specProductFrozenMargin', label: '冻结保证金', format: 'money' },
      { key: 'specProductCommission', label: '手续费', format: 'money' },
      { key: 'specProductFrozenCommission', label: '冻结手续费', format: 'money' },
      { key: 'specProductPositionProfit', label: '持仓盈亏', format: 'money', trend: true },
      { key: 'specProductPositionProfitByAlg', label: '持仓盈亏（按算法）', format: 'money', trend: true },
      { key: 'specProductCloseProfit', label: '平仓盈亏', format: 'money', trend: true },
    ],
  },
  {
    title: '账户',
    fields: [
      { key: 'accountID', label: '资金账号' },
      { key: 'currencyID', label: '币种' },
      { key: 'tradingDay', label: '交易日' },
      { key: 'settlementID', label: '结算编号' },
    ],
  },
]
