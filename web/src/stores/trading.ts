import { defineStore } from 'pinia'
import { computed, reactive, ref } from 'vue'
import { api } from '@/api/http'
import { orderId, tradeId, type Account, type Order, type OrderRequest, type Position, type Trade } from '@/api/types'

/** 成交后延迟刷新持仓与资金：柜台更新需要时间，且查询每秒只能一次 */
const REFRESH_DELAY = 1500

export const useTradingStore = defineStore('trading', () => {
  const orderMap = reactive<Record<string, Order>>({})
  const tradeMap = reactive<Record<string, Trade>>({})
  const positions = ref<Position[]>([])
  const account = ref<Account | null>(null)
  let refreshTimer: number | undefined

  const orders = computed(() =>
    Object.values(orderMap).sort(
      (a, b) => (b.insertTime ?? '').localeCompare(a.insertTime ?? '') || b.systemTimestamp - a.systemTimestamp,
    ),
  )
  const trades = computed(() =>
    Object.values(tradeMap).sort(
      (a, b) => (b.tradeTime ?? '').localeCompare(a.tradeTime ?? '') || b.systemTimestamp - a.systemTimestamp,
    ),
  )

  async function load() {
    const [o, t] = await Promise.all([api.orders(), api.trades()])
    o.forEach((x) => (orderMap[orderId(x)] = x))
    t.forEach((x) => (tradeMap[tradeId(x)] = x))
    // 资金、持仓查询在柜台排队执行，依次请求
    await refreshAccount()
    await refreshPositions()
  }

  async function refreshAccount() {
    account.value = await api.account()
  }

  async function refreshPositions() {
    positions.value = await api.positions()
  }

  function scheduleRefresh() {
    window.clearTimeout(refreshTimer)
    refreshTimer = window.setTimeout(async () => {
      await refreshPositions().catch(() => {})
      await refreshAccount().catch(() => {})
    }, REFRESH_DELAY)
  }

  function onOrder(order: Order) {
    orderMap[orderId(order)] = order
  }

  function onTrade(trade: Trade) {
    tradeMap[tradeId(trade)] = trade
    scheduleRefresh()
  }

  async function submit(request: OrderRequest): Promise<Order> {
    const order = await api.submit(request)
    const id = orderId(order)
    // 推送可能已先到，保留更新的状态
    if (!orderMap[id]) orderMap[id] = order
    return order
  }

  async function cancel(order: Order) {
    await api.cancel(orderId(order))
  }

  function reset() {
    window.clearTimeout(refreshTimer)
    Object.keys(orderMap).forEach((k) => delete orderMap[k])
    Object.keys(tradeMap).forEach((k) => delete tradeMap[k])
    positions.value = []
    account.value = null
  }

  return {
    orders,
    trades,
    positions,
    account,
    load,
    refreshAccount,
    refreshPositions,
    onOrder,
    onTrade,
    submit,
    cancel,
    reset,
  }
})
