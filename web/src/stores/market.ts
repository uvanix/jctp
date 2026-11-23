import { defineStore } from 'pinia'
import { reactive, ref } from 'vue'
import { api, getToken } from '@/api/http'
import { PushClient, type PushMessage } from '@/api/ws'
import type { Instrument, MarketData, Order, Trade } from '@/api/types'

type TickListener = (tick: MarketData) => void

export interface PushListeners {
  onOrder: (order: Order) => void
  onTrade: (trade: Trade) => void
  onClosed: () => void
}

/**
 * 行情：推送连接、最新行情、合约缓存。
 * 订阅按“用途”登记（行情列表、选中合约、持仓……），各用途的并集即实际订阅集合。
 */
export const useMarketStore = defineStore('market', () => {
  const quotes = reactive<Record<string, MarketData>>({})
  const instruments = reactive<Record<string, Instrument>>({})
  const connected = ref(false)
  const watches = new Map<string, Set<string>>()
  const tickListeners = new Set<TickListener>()
  let client: PushClient | null = null

  function start(listeners: PushListeners) {
    stop()
    const token = getToken()
    if (!token) return
    client = new PushClient(token, {
      onStatus: (value) => (connected.value = value),
      onMessage: (message: PushMessage) => {
        switch (message.type) {
          case 'tick':
            quotes[message.data.instrumentID] = message.data
            tickListeners.forEach((l) => l(message.data))
            break
          case 'order':
            listeners.onOrder(message.data)
            break
          case 'trade':
            listeners.onTrade(message.data)
            break
          case 'closed':
            listeners.onClosed()
            break
        }
      },
    })
    client.connect()
  }

  function stop() {
    client?.close()
    client = null
    watches.clear()
    connected.value = false
  }

  function subscribed(): Set<string> {
    const all = new Set<string>()
    watches.forEach((ids) => ids.forEach((id) => all.add(id)))
    return all
  }

  /** 设置某用途关注的合约，增减部分同步到推送订阅 */
  function watch(owner: string, ids: string[]) {
    const before = subscribed()
    watches.set(owner, new Set(ids.filter(Boolean)))
    const after = subscribed()
    const added = [...after].filter((id) => !before.has(id))
    const removed = [...before].filter((id) => !after.has(id))
    if (added.length) client?.subscribe(added)
    if (removed.length) client?.unsubscribe(removed)
  }

  function onTick(listener: TickListener): () => void {
    tickListeners.add(listener)
    return () => tickListeners.delete(listener)
  }

  function remember(list: Instrument[]) {
    list.forEach((i) => (instruments[i.instrumentID] = i))
  }

  async function instrument(id: string): Promise<Instrument> {
    if (!instruments[id]) instruments[id] = await api.instrument(id)
    return instruments[id]
  }

  return { quotes, instruments, connected, start, stop, watch, onTick, remember, instrument }
})
