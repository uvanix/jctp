import type { MarketData, Order, Trade } from './types'

export type PushMessage =
  | { type: 'tick'; data: MarketData }
  | { type: 'order'; data: Order }
  | { type: 'trade'; data: Trade }
  | { type: 'pong'; data: null }
  | { type: 'closed'; data: null }

export interface PushHandlers {
  onMessage: (message: PushMessage) => void
  onStatus: (connected: boolean) => void
}

const PING_INTERVAL = 15_000
const RECONNECT_DELAY = 3_000

/**
 * 推送连接：断线自动重连，重连后重新发送全部订阅。
 */
export class PushClient {
  private socket: WebSocket | null = null
  private readonly subscriptions = new Set<string>()
  private pingTimer: number | undefined
  private reconnectTimer: number | undefined
  private stopped = false

  constructor(
    private readonly token: string,
    private readonly handlers: PushHandlers,
  ) {}

  connect() {
    this.stopped = false
    const protocol = location.protocol === 'https:' ? 'wss' : 'ws'
    const socket = new WebSocket(`${protocol}://${location.host}/ws?token=${encodeURIComponent(this.token)}`)
    this.socket = socket
    socket.onopen = () => {
      this.handlers.onStatus(true)
      if (this.subscriptions.size) this.send({ type: 'subscribe', instruments: [...this.subscriptions] })
      this.pingTimer = window.setInterval(() => this.send({ type: 'ping' }), PING_INTERVAL)
    }
    socket.onmessage = (event) => this.handlers.onMessage(JSON.parse(event.data) as PushMessage)
    socket.onclose = (event) => {
      window.clearInterval(this.pingTimer)
      this.handlers.onStatus(false)
      // 4401：令牌无效，不再重连
      if (!this.stopped && event.code !== 4401) {
        this.reconnectTimer = window.setTimeout(() => this.connect(), RECONNECT_DELAY)
      }
    }
  }

  close() {
    this.stopped = true
    window.clearTimeout(this.reconnectTimer)
    window.clearInterval(this.pingTimer)
    this.socket?.close()
    this.socket = null
  }

  subscribe(ids: string[]) {
    const fresh = ids.filter((id) => !this.subscriptions.has(id))
    fresh.forEach((id) => this.subscriptions.add(id))
    if (fresh.length) this.send({ type: 'subscribe', instruments: fresh })
  }

  unsubscribe(ids: string[]) {
    const removed = ids.filter((id) => this.subscriptions.delete(id))
    if (removed.length) this.send({ type: 'unsubscribe', instruments: removed })
  }

  private send(message: object) {
    if (this.socket?.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify(message))
  }
}
