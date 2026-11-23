<script setup lang="ts">
import { computed, h, ref, type VNode } from 'vue'
import { ElMessage, ElMessageBox, type TableColumnCtx } from 'element-plus'
import { orderDone, riskRatio, type Order, type Position } from '@/api/types'
import { useMarketStore } from '@/stores/market'
import { useTradingStore } from '@/stores/trading'
import FieldDetails from '@/components/FieldDetails.vue'
import SettlementView from '@/components/SettlementView.vue'
import {
  DIRECTION,
  EXCHANGES,
  OFFSET,
  ORDER_STATUS,
  POSI_DIRECTION,
  PRICE_TYPE,
  SUBMIT_STATUS,
  TIME_CONDITION,
  TRADE_TYPE,
} from '@/utils/dict'
import { ACCOUNT_FIELDS, ORDER_FIELDS, POSITION_FIELDS, TRADE_FIELDS } from '@/utils/fields'
import { decimals, errorText, int, money, percent, price, trendClass } from '@/utils/format'

const emit = defineEmits<{
  select: [id: string]
  close: [position: Position]
}>()

const market = useMarketStore()
const trading = useTradingStore()
const tab = ref('positions')
const refreshing = ref(false)

const pending = computed(() => trading.orders.filter((o) => !orderDone(o)))

function digitsOf(id: string) {
  return decimals(market.instruments[id]?.priceTick)
}

/** 浮动盈亏：按开仓均价与最新价计算 */
function floating(p: Position): number | null {
  const last = market.quotes[p.instrumentID]?.lastPrice
  if (last == null) return null
  const sign = p.direction === 'Short' ? -1 : 1
  return (last - p.openPrice) * p.position * p.volumeMultiple * sign
}

const totalFloating = computed(() =>
  trading.positions.reduce((sum, p) => sum + (floating(p) ?? 0), 0),
)

/** 持仓合计行：按列标签汇总数量、金额，盈亏按正负着色 */
const POSITION_TOTALS: Record<string, { value: (p: Position) => number; format: (v: number) => string; trend?: boolean }> = {
  总仓: { value: (p) => p.position, format: int },
  昨仓: { value: (p) => p.ydPosition, format: int },
  今仓: { value: (p) => p.todayPosition, format: int },
  开仓成本: { value: (p) => p.openCost, format: money },
  浮动盈亏: { value: (p) => floating(p) ?? 0, format: money, trend: true },
  盯市盈亏: { value: (p) => p.positionProfit, format: money, trend: true },
  平仓盈亏: { value: (p) => p.closeProfit, format: money, trend: true },
  占用保证金: { value: (p) => p.useMargin, format: money },
  手续费: { value: (p) => p.commission, format: money },
}

function positionSummary({ columns, data }: { columns: TableColumnCtx<Position>[]; data: Position[] }): (string | VNode)[] {
  return columns.map((column) => {
    if (column.label === '合约') return '合计'
    const total = POSITION_TOTALS[column.label]
    if (!total || !data.length) return ''
    const sum = data.reduce((acc, p) => acc + total.value(p), 0)
    return h('span', { class: ['num', total.trend ? trendClass(sum) : ''] }, total.format(sum))
  })
}

function statusText(o: Order): string {
  if (o.submitStatus === 'InsertRejected') return '已拒绝'
  if (o.submitStatus === 'CancelRejected') return '撤单被拒'
  return o.status ? ORDER_STATUS[o.status] : o.submitStatus ? SUBMIT_STATUS[o.submitStatus] : '--'
}

function statusClass(o: Order): string {
  if (o.submitStatus === 'InsertRejected' || o.submitStatus === 'CancelRejected') return 'down'
  if (o.status === 'AllTraded') return 'up'
  return ''
}

async function cancel(o: Order) {
  try {
    await trading.cancel(o)
    ElMessage.info(`已发送撤单 ${o.instrumentID}`)
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}

async function cancelAll() {
  if (!pending.value.length) return
  try {
    await ElMessageBox.confirm(`撤销全部 ${pending.value.length} 笔挂单？`, '全撤', { type: 'warning' })
  } catch {
    return
  }
  for (const o of pending.value) await cancel(o)
}

async function refresh() {
  refreshing.value = true
  try {
    if (tab.value === 'account') await trading.refreshAccount()
    else await trading.refreshPositions()
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    refreshing.value = false
  }
}

const account = computed(() => trading.account)

/** 按金额收取时显示比率，按手数收取时显示每手金额 */
function marginRate(p: Position): string {
  if (p.marginRateByMoney) return percent(p.marginRateByMoney)
  if (p.marginRateByVolume) return `${money(p.marginRateByVolume)}/手`
  return '--'
}
</script>

<template>
  <div class="panel tabs">
    <el-tabs v-model="tab" class="trade-tabs">
      <el-tab-pane :label="`持仓 ${trading.positions.length}`" name="positions">
        <el-table
          :data="trading.positions"
          height="100%"
          empty-text="无持仓"
          show-summary
          :summary-method="positionSummary"
          @row-click="(p: Position) => emit('select', p.instrumentID)"
        >
          <el-table-column type="expand" fixed="left">
            <template #default="{ row }">
              <FieldDetails :row="row" :groups="POSITION_FIELDS" :digits="digitsOf(row.instrumentID)" />
            </template>
          </el-table-column>
          <el-table-column label="合约" prop="instrumentID" width="96" fixed="left" />
          <el-table-column label="交易所" width="70">
            <template #default="{ row }">{{ EXCHANGES[row.exchangeID] ?? row.exchangeID }}</template>
          </el-table-column>
          <el-table-column label="多空" width="50">
            <template #default="{ row }">
              <span :class="row.direction === 'Long' ? 'up' : 'down'">{{ POSI_DIRECTION[row.direction as Position['direction']] }}</span>
            </template>
          </el-table-column>
          <el-table-column label="总仓" width="60" align="right">
            <template #default="{ row }"><span class="num">{{ row.position }}</span></template>
          </el-table-column>
          <el-table-column label="昨仓" width="60" align="right">
            <template #default="{ row }"><span class="num">{{ row.ydPosition }}</span></template>
          </el-table-column>
          <el-table-column label="今仓" width="60" align="right">
            <template #default="{ row }"><span class="num">{{ row.todayPosition }}</span></template>
          </el-table-column>
          <el-table-column label="冻结(多/空)" width="96" align="right">
            <template #default="{ row }"><span class="num">{{ row.longFrozen }} / {{ row.shortFrozen }}</span></template>
          </el-table-column>
          <el-table-column label="合约乘数" width="76" align="right">
            <template #default="{ row }"><span class="num">{{ row.volumeMultiple }}</span></template>
          </el-table-column>
          <el-table-column label="开仓均价" width="96" align="right">
            <template #default="{ row }"><span class="num">{{ price(row.openPrice, digitsOf(row.instrumentID) + 1) }}</span></template>
          </el-table-column>
          <el-table-column label="开仓成本" width="110" align="right">
            <template #default="{ row }"><span class="num">{{ money(row.openCost) }}</span></template>
          </el-table-column>
          <el-table-column label="昨结算" width="86" align="right">
            <template #default="{ row }"><span class="num">{{ price(row.preSettlementPrice, digitsOf(row.instrumentID)) }}</span></template>
          </el-table-column>
          <el-table-column label="结算价" width="86" align="right">
            <template #default="{ row }"><span class="num">{{ price(row.settlementPrice, digitsOf(row.instrumentID)) }}</span></template>
          </el-table-column>
          <el-table-column label="最新价" width="86" align="right">
            <template #default="{ row }">
              <span class="num">{{ price(market.quotes[row.instrumentID]?.lastPrice, digitsOf(row.instrumentID)) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="浮动盈亏" width="100" align="right">
            <template #default="{ row }">
              <span class="num" :class="trendClass(floating(row))">{{ money(floating(row)) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="盯市盈亏" width="100" align="right">
            <template #default="{ row }">
              <span class="num" :class="trendClass(row.positionProfit)">{{ money(row.positionProfit) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="平仓盈亏" width="100" align="right">
            <template #default="{ row }">
              <span class="num" :class="trendClass(row.closeProfit)">{{ money(row.closeProfit) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="占用保证金" width="110" align="right">
            <template #default="{ row }"><span class="num">{{ money(row.useMargin) }}</span></template>
          </el-table-column>
          <el-table-column label="保证金率" width="90" align="right">
            <template #default="{ row }"><span class="num">{{ marginRate(row) }}</span></template>
          </el-table-column>
          <el-table-column label="手续费" width="86" align="right">
            <template #default="{ row }"><span class="num">{{ money(row.commission) }}</span></template>
          </el-table-column>
          <el-table-column label="操作" min-width="70" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click.stop="emit('close', row)">平仓</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="`挂单 ${pending.length}`" name="pending">
        <el-table :data="pending" height="100%" empty-text="无挂单" @row-dblclick="cancel">
          <el-table-column type="expand" fixed="left">
            <template #default="{ row }">
              <FieldDetails :row="row" :groups="ORDER_FIELDS" :digits="digitsOf(row.instrumentID)" />
            </template>
          </el-table-column>
          <el-table-column label="交易日" prop="tradingDay" width="96" />
          <el-table-column label="时间" prop="insertTime" width="76" />
          <el-table-column label="合约" prop="instrumentID" width="96" fixed="left" />
          <el-table-column label="交易所" width="70">
            <template #default="{ row }">{{ EXCHANGES[row.exchangeID] ?? row.exchangeID }}</template>
          </el-table-column>
          <el-table-column label="买卖" width="50">
            <template #default="{ row }">
              <span :class="row.direction === 'Buy' ? 'up' : 'down'">{{ DIRECTION[row.direction as Order['direction']] }}</span>
            </template>
          </el-table-column>
          <el-table-column label="开平" width="56">
            <template #default="{ row }">{{ row.offsetFlag ? OFFSET[row.offsetFlag as Order['offsetFlag']] : '' }}</template>
          </el-table-column>
          <el-table-column label="价格" width="86" align="right">
            <template #default="{ row }"><span class="num">{{ price(row.limitPrice, digitsOf(row.instrumentID)) }}</span></template>
          </el-table-column>
          <el-table-column label="价格条件" width="76">
            <template #default="{ row }">{{ row.priceType ? PRICE_TYPE[row.priceType as keyof typeof PRICE_TYPE] : '' }}</template>
          </el-table-column>
          <el-table-column label="委托" width="56" align="right" prop="volume" />
          <el-table-column label="已成交" width="64" align="right" prop="volumeTraded" />
          <el-table-column label="未成交" width="64" align="right" prop="volumeTotal" />
          <el-table-column label="有效期" width="76">
            <template #default="{ row }">{{ row.timeCondition ? TIME_CONDITION[row.timeCondition as keyof typeof TIME_CONDITION] : '' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">{{ statusText(row) }}</template>
          </el-table-column>
          <el-table-column label="报单引用" prop="orderRef" width="80" />
          <el-table-column label="报单编号" prop="orderSysID" width="110" />
          <el-table-column min-width="70" fixed="right">
            <template #header>
              <el-button link type="danger" :disabled="!pending.length" @click="cancelAll">全撤</el-button>
            </template>
            <template #default="{ row }">
              <el-button link type="warning" @click="cancel(row)">撤单</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="`委托 ${trading.orders.length}`" name="orders">
        <el-table :data="trading.orders" height="100%" empty-text="今日无委托">
          <el-table-column type="expand" fixed="left">
            <template #default="{ row }">
              <FieldDetails :row="row" :groups="ORDER_FIELDS" :digits="digitsOf(row.instrumentID)" />
            </template>
          </el-table-column>
          <el-table-column label="交易日" prop="tradingDay" width="96" />
          <el-table-column label="时间" prop="insertTime" width="76" />
          <el-table-column label="合约" prop="instrumentID" width="96" fixed="left" />
          <el-table-column label="交易所" width="70">
            <template #default="{ row }">{{ EXCHANGES[row.exchangeID] ?? row.exchangeID }}</template>
          </el-table-column>
          <el-table-column label="买卖" width="50">
            <template #default="{ row }">
              <span :class="row.direction === 'Buy' ? 'up' : 'down'">{{ DIRECTION[row.direction as Order['direction']] }}</span>
            </template>
          </el-table-column>
          <el-table-column label="开平" width="56">
            <template #default="{ row }">{{ row.offsetFlag ? OFFSET[row.offsetFlag as Order['offsetFlag']] : '' }}</template>
          </el-table-column>
          <el-table-column label="价格" width="86" align="right">
            <template #default="{ row }"><span class="num">{{ price(row.limitPrice, digitsOf(row.instrumentID)) }}</span></template>
          </el-table-column>
          <el-table-column label="价格条件" width="76">
            <template #default="{ row }">{{ row.priceType ? PRICE_TYPE[row.priceType as keyof typeof PRICE_TYPE] : '' }}</template>
          </el-table-column>
          <el-table-column label="委托" width="56" align="right" prop="volume" />
          <el-table-column label="成交" width="56" align="right" prop="volumeTraded" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }"><span :class="statusClass(row)">{{ statusText(row) }}</span></template>
          </el-table-column>
          <el-table-column label="有效期" width="76">
            <template #default="{ row }">{{ row.timeCondition ? TIME_CONDITION[row.timeCondition as keyof typeof TIME_CONDITION] : '' }}</template>
          </el-table-column>
          <el-table-column label="说明" prop="statusMsg" min-width="180" show-overflow-tooltip />
          <el-table-column label="报单引用" prop="orderRef" width="80" />
          <el-table-column label="报单编号" prop="orderSysID" width="110" />
          <el-table-column label="更新时间" width="76">
            <template #default="{ row }">{{ row.cancelTime ?? row.updateTime ?? '' }}</template>
          </el-table-column>
          <el-table-column width="60" fixed="right">
            <template #default="{ row }">
              <el-button v-if="!orderDone(row)" link type="warning" @click="cancel(row)">撤单</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="`成交 ${trading.trades.length}`" name="trades">
        <el-table :data="trading.trades" height="100%" empty-text="今日无成交">
          <el-table-column type="expand" fixed="left">
            <template #default="{ row }">
              <FieldDetails :row="row" :groups="TRADE_FIELDS" :digits="digitsOf(row.instrumentID)" />
            </template>
          </el-table-column>
          <el-table-column label="交易日" prop="tradingDay" width="96" />
          <el-table-column label="成交时间" prop="tradeTime" width="76" />
          <el-table-column label="合约" prop="instrumentID" width="96" fixed="left" />
          <el-table-column label="买卖" width="50">
            <template #default="{ row }">
              <span :class="row.direction === 'Buy' ? 'up' : 'down'">{{ DIRECTION[row.direction as Order['direction']] }}</span>
            </template>
          </el-table-column>
          <el-table-column label="开平" width="56">
            <template #default="{ row }">{{ row.offsetFlag ? OFFSET[row.offsetFlag as Order['offsetFlag']] : '' }}</template>
          </el-table-column>
          <el-table-column label="成交价" width="86" align="right">
            <template #default="{ row }"><span class="num">{{ price(row.price, digitsOf(row.instrumentID)) }}</span></template>
          </el-table-column>
          <el-table-column label="数量" width="56" align="right" prop="volume" />
          <el-table-column label="成交类型" width="80">
            <template #default="{ row }">{{ row.tradeType ? TRADE_TYPE[row.tradeType as keyof typeof TRADE_TYPE] : '' }}</template>
          </el-table-column>
          <el-table-column label="交易所" width="70">
            <template #default="{ row }">{{ EXCHANGES[row.exchangeID] ?? row.exchangeID }}</template>
          </el-table-column>
          <el-table-column label="成交编号" prop="tradeID" width="110" />
          <el-table-column label="交易所报单编号" prop="orderSysID" width="120" />
          <el-table-column label="报单引用" prop="orderRef" min-width="80" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="资金" name="account">
        <div class="account">
          <template v-if="account">
            <el-descriptions :column="4" size="small" border>
              <el-descriptions-item label="动态权益"><span class="num">{{ money(account.balance) }}</span></el-descriptions-item>
              <el-descriptions-item label="可用资金"><span class="num">{{ money(account.available) }}</span></el-descriptions-item>
              <el-descriptions-item label="占用保证金"><span class="num">{{ money(account.currMargin) }}</span></el-descriptions-item>
              <el-descriptions-item label="风险度">
                <span class="num">{{ percent(riskRatio(account)) }}</span>
              </el-descriptions-item>
            </el-descriptions>
            <FieldDetails :row="account" :groups="ACCOUNT_FIELDS" />
          </template>
          <div v-else class="muted">资金查询中…</div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="结算单" name="settlement" lazy>
        <SettlementView />
      </el-tab-pane>
    </el-tabs>
    <div class="extra">
      <span v-if="tab === 'positions'" class="muted">
        浮动盈亏合计
        <span class="num" :class="trendClass(totalFloating)">{{ money(totalFloating) }}</span>
        · 持仓 {{ int(trading.positions.reduce((s, p) => s + p.position, 0)) }} 手
      </span>
      <el-button v-if="tab === 'positions' || tab === 'account'" link :loading="refreshing" @click="refresh">刷新</el-button>
    </div>
  </div>
</template>

<style scoped>
.tabs {
  height: 100%;
  position: relative;
}

.trade-tabs {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.trade-tabs :deep(.el-tabs__header) {
  margin: 0;
  padding: 0 8px;
  background: var(--header-bg);
}

.trade-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
}

.trade-tabs :deep(.el-tab-pane) {
  height: 100%;
}

.extra {
  position: absolute;
  right: 10px;
  top: 6px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.account {
  height: 100%;
  overflow: auto;
  padding: 8px;
}

.account :deep(.field-details) {
  padding: 8px 0 0;
}
</style>
