import type {
  Account,
  Bar,
  BarPeriod,
  Counter,
  Instrument,
  InstrumentRates,
  LoginStatus,
  Order,
  OrderRequest,
  Position,
  Product,
  SessionInfo,
  SettlementInfo,
  Trade,
} from './types'

const TOKEN_KEY = 'jctp.token'

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message)
  }
}

export function getToken(): string | null {
  return sessionStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string | null) {
  if (token) sessionStorage.setItem(TOKEN_KEY, token)
  else sessionStorage.removeItem(TOKEN_KEY)
}

/** 会话失效（401）时的回调，由路由层注册以跳回登录页 */
let onUnauthorized: () => void = () => {}

export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const response = await fetch(`/api${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  const data = text ? JSON.parse(text) : null
  if (!response.ok) {
    if (response.status === 401) {
      setToken(null)
      onUnauthorized()
    }
    throw new ApiError(data?.error ?? `请求失败 ${response.status}`, response.status)
  }
  return data as T
}

const q = encodeURIComponent

export const api = {
  counters: () => request<Counter[]>('GET', '/counters'),
  /** 服务端支持的 CTP API 版本，首个为缺省 */
  versions: () => request<string[]>('GET', '/versions'),
  /** 发起登录，立即返回；之后用 loginStatus 轮询 */
  login: (counterId: string, userId: string, password: string, apiVersion: string) =>
    request<LoginStatus>('POST', '/login', { counterId, userId, password, apiVersion }),
  loginStatus: (loginId: string) => request<LoginStatus>('GET', `/login/${q(loginId)}`),
  logout: () => request<void>('POST', '/logout'),
  session: () => request<SessionInfo>('GET', '/session'),

  products: () => request<Product[]>('GET', '/products'),
  instruments: (productId: string) => request<Instrument[]>('GET', `/instruments?productId=${q(productId)}`),
  search: (text: string) => request<Instrument[]>('GET', `/instruments?q=${q(text)}`),
  instrument: (id: string) => request<Instrument>('GET', `/instruments/${q(id)}`),
  rates: (id: string) => request<InstrumentRates>('GET', `/instruments/${q(id)}/rates`),
  bars: (id: string, period: BarPeriod) => request<Bar[]>('GET', `/bars/${q(id)}?period=${period}`),

  account: () => request<Account>('GET', '/account'),
  /** 结算单；tradingDay 为 YYYY-MM-DD，缺省为最近一次；没有结算单时为 null */
  settlement: (tradingDay?: string) =>
    request<SettlementInfo | null>('GET', `/settlement${tradingDay ? `?tradingDay=${q(tradingDay)}` : ''}`),
  positions: () => request<Position[]>('GET', '/positions'),
  orders: () => request<Order[]>('GET', '/orders'),
  trades: () => request<Trade[]>('GET', '/trades'),
  submit: (order: OrderRequest) => request<Order>('POST', '/orders', order),
  cancel: (orderId: string) => request<Order>('POST', `/orders/${q(orderId)}/cancel`),
}
