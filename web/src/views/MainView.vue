<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/http'
import { isOptionClass, riskRatio, type Instrument, type OrderPreset, type Position, type Product } from '@/api/types'
import { useMarketStore } from '@/stores/market'
import { useSessionStore } from '@/stores/session'
import { useTradingStore } from '@/stores/trading'
import { DIRECTION, EXCHANGES, closeTodaySeparately } from '@/utils/dict'
import { errorText, money, percent, trendClass } from '@/utils/format'
import { useStorage } from '@/utils/storage'
import ProductTree from '@/components/ProductTree.vue'
import QuoteTable from '@/components/QuoteTable.vue'
import OptionTQuote from '@/components/OptionTQuote.vue'
import KlineChart from '@/components/KlineChart.vue'
import OrderPanel from '@/components/OrderPanel.vue'
import InstrumentInfo from '@/components/InstrumentInfo.vue'
import TradeTabs from '@/components/TradeTabs.vue'
import ThemeToggle from '@/components/ThemeToggle.vue'

/** 资金定时刷新间隔 */
const ACCOUNT_REFRESH = 15_000
/** 合约在服务端后台按品种加载，加载完成前定时刷新品种列表（合约数） */
const PRODUCTS_REFRESH = 5_000
const LAYOUT_KEY = 'jctp.layout'

/** 可拖动分割面板的尺寸（像素或百分比），保存在本地，刷新后保持 */
interface LayoutSizes {
  tree: number | string
  side: number | string
  quotes: number | string
  bottom: number | string
}

const DEFAULT_LAYOUT: LayoutSizes = { tree: 210, side: 300, quotes: '42%', bottom: 240 }
const layout = useStorage<LayoutSizes>(LAYOUT_KEY, { ...DEFAULT_LAYOUT })

function resetLayout() {
  layout.value = { ...DEFAULT_LAYOUT }
}

const router = useRouter()
const session = useSessionStore()
const market = useMarketStore()
const trading = useTradingStore()

const products = ref<Product[]>([])
const product = ref<Product | null>(null)
const productInstruments = ref<Instrument[]>([])
const listLoading = ref(false)
const selectedId = ref<string | null>(null)
const preset = ref<OrderPreset | null>(null)
let accountTimer: number | undefined
let productsTimer: number | undefined

const selected = computed(() => (selectedId.value ? market.instruments[selectedId.value] ?? null : null))
const isOptions = computed(() => !!product.value && isOptionClass(product.value.productClass))
const account = computed(() => trading.account)

onMounted(async () => {
  if (!(await session.restore())) {
    router.replace({ name: 'login' })
    return
  }
  market.start({
    onOrder: trading.onOrder,
    onTrade: (trade) => {
      trading.onTrade(trade)
      ElMessage.success(`成交 ${trade.instrumentID} ${DIRECTION[trade.direction]} ${trade.volume} 手 @ ${trade.price}`)
    },
    onClosed: () => {
      ElMessage.warning('会话已登出')
      session.clear()
      router.replace({ name: 'login' })
    },
  })
  try {
    products.value = await api.products()
    refreshProductsUntilLoaded()
    const first =
      products.value.find((p) => p.productId === 'au' && p.productClass === 'Futures') ??
      products.value.find((p) => p.productClass === 'Futures') ??
      products.value[0]
    if (first) await selectProduct(first)
  } catch (e) {
    ElMessage.error(`加载合约失败：${errorText(e)}`)
  }
  trading.load().catch((e) => ElMessage.error(`加载交易数据失败：${errorText(e)}`))
  accountTimer = window.setInterval(() => trading.refreshAccount().catch(() => {}), ACCOUNT_REFRESH)
})

onBeforeUnmount(() => {
  window.clearInterval(accountTimer)
  window.clearTimeout(productsTimer)
  market.stop()
  trading.reset()
})

// 持仓合约也订阅行情，用于计算浮动盈亏
watch(
  () => trading.positions.map((p) => p.instrumentID),
  (ids) => {
    ids.forEach((id) => market.instrument(id).catch(() => {}))
    market.watch('positions', ids)
  },
)

function refreshProductsUntilLoaded() {
  window.clearTimeout(productsTimer)
  if (!products.value.some((p) => p.loading)) return
  productsTimer = window.setTimeout(async () => {
    try {
      products.value = await api.products()
    } catch {
      // 下次再试
    }
    refreshProductsUntilLoaded()
  }, PRODUCTS_REFRESH)
}

function sameProduct(a: Product | null, b: Product | null): boolean {
  return !!a && !!b && a.productId === b.productId && a.exchangeId === b.exchangeId
}

async function selectProduct(p: Product) {
  product.value = p
  listLoading.value = true
  try {
    const list = await api.instruments(p.productId)
    // 未加载的品种此次按需加载，更新列表中的合约数
    if (p.count == null) {
      const index = products.value.findIndex((x) => x.productId === p.productId && x.exchangeId === p.exchangeId)
      if (index >= 0) products.value[index] = { ...p, count: list.length, loading: false }
    }
    if (product.value !== p) return
    market.remember(list)
    productInstruments.value = list
    if (!isOptions.value) market.watch('list', list.map((i) => i.instrumentID))
    if (!selectedId.value || !list.some((i) => i.instrumentID === selectedId.value)) {
      const first = list[0]
      if (first) selectInstrument(first.instrumentID)
    }
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    listLoading.value = false
  }
}

async function selectInstrument(id: string) {
  await market.instrument(id)
  selectedId.value = id
  market.watch('selected', [id])
}

async function pick(instrument: Instrument) {
  market.remember([instrument])
  const p = products.value.find((x) => x.productId === instrument.productID && x.exchangeId === instrument.exchangeID)
  if (p && !sameProduct(p, product.value)) await selectProduct(p)
  await selectInstrument(instrument.instrumentID)
}

async function closePosition(p: Position) {
  await selectInstrument(p.instrumentID)
  const separate = closeTodaySeparately(p.exchangeID)
  const direction = p.direction === 'Short' ? 'Buy' : 'Sell'
  // 平仓价取对手价，避免沿用下单板里之前的限价
  const quote = market.quotes[p.instrumentID]
  const counterparty = direction === 'Sell' ? quote?.bidPrice1 : quote?.askPrice1
  preset.value = {
    direction,
    offsetFlag: separate ? (p.todayPosition > 0 ? 'CloseToday' : 'CloseYesterday') : 'Close',
    volume: separate ? (p.todayPosition > 0 ? p.todayPosition : p.ydPosition) : p.position,
    price: counterparty ?? quote?.lastPrice ?? undefined,
    nonce: Date.now(),
  }
}

async function logout() {
  try {
    await ElMessageBox.confirm('退出登录？最后一个会话退出后将断开柜台连接。', '退出', { type: 'warning' })
  } catch {
    return
  }
  await session.logout().catch(() => {})
  router.replace({ name: 'login' })
}
</script>

<template>
  <div class="main">
    <header class="topbar">
      <div class="brand">
        <img src="/favicon.svg" alt="" />
        <span>JCTP</span>
      </div>
      <div v-if="session.info" class="who">
        <span class="user">{{ session.info.userId }}</span>
        <span>{{ session.info.counterName }}</span>
        <span v-if="!session.info.simulated">API v{{ session.info.apiVersion }}</span>
        <span>交易日 {{ session.info.tradingDay }}</span>
      </div>
      <div v-if="account" class="metrics">
        <span>权益 <b class="num">{{ money(account.balance) }}</b></span>
        <span>可用 <b class="num">{{ money(account.available) }}</b></span>
        <span>保证金 <b class="num">{{ money(account.currMargin) }}</b></span>
        <span>持仓盈亏 <b class="num" :class="trendClass(account.positionProfit)">{{ money(account.positionProfit) }}</b></span>
        <span>平仓盈亏 <b class="num" :class="trendClass(account.closeProfit)">{{ money(account.closeProfit) }}</b></span>
        <span>
          风险度
          <b class="num">{{ percent(riskRatio(account), 1) }}</b>
        </span>
      </div>
      <div class="right">
        <el-tag :type="market.connected ? 'success' : 'danger'" size="small" effect="dark">
          {{ market.connected ? '推送已连接' : '推送断开' }}
        </el-tag>
        <ThemeToggle />
        <el-button size="small" title="恢复各面板的默认大小" @click="resetLayout">重置布局</el-button>
        <el-button size="small" @click="logout">退出</el-button>
      </div>
    </header>

    <!-- 工作区与底部交易面板、品种列表 | 行情与 K 线 | 下单区，均可拖动调整 -->
    <el-splitter layout="vertical" class="work">
      <el-splitter-panel :min="200">
        <el-splitter>
          <el-splitter-panel v-model:size="layout.tree" :min="150" :max="420" collapsible>
            <ProductTree class="fill" :products="products" :selected="product" @select="selectProduct" @pick="pick" />
          </el-splitter-panel>

          <el-splitter-panel :min="360">
            <el-splitter layout="vertical">
              <el-splitter-panel v-model:size="layout.quotes" :min="100" collapsible>
                <div class="panel quotes fill">
                  <div class="panel-title">
                    <template v-if="product">
                      {{ product.name }}
                      <span class="muted">{{ product.productId }} · {{ EXCHANGES[product.exchangeId] ?? product.exchangeId }}</span>
                    </template>
                    <span class="muted hint">单击选择合约（K 线、下单、合约信息联动）</span>
                  </div>
                  <div class="panel-body">
                    <OptionTQuote
                      v-if="isOptions"
                      :instruments="productInstruments"
                      :selected="selectedId"
                      :loading="listLoading"
                      @select="selectInstrument"
                      @open="selectInstrument"
                    />
                    <QuoteTable
                      v-else
                      :instruments="productInstruments"
                      :selected="selectedId"
                      :loading="listLoading"
                      @select="selectInstrument"
                      @open="selectInstrument"
                    />
                  </div>
                </div>
              </el-splitter-panel>
              <el-splitter-panel :min="160">
                <KlineChart class="fill" :instrument="selected" />
              </el-splitter-panel>
            </el-splitter>
          </el-splitter-panel>

          <el-splitter-panel v-model:size="layout.side" :min="240" :max="560" collapsible>
            <div class="side fill">
              <OrderPanel :instrument="selected" :preset="preset" />
              <InstrumentInfo :instrument="selected" />
            </div>
          </el-splitter-panel>
        </el-splitter>
      </el-splitter-panel>

      <el-splitter-panel v-model:size="layout.bottom" :min="120" collapsible>
        <TradeTabs class="fill" @select="selectInstrument" @close="closePosition" />
      </el-splitter-panel>
    </el-splitter>
  </div>
</template>

<style scoped>
.main {
  height: 100%;
  display: grid;
  grid-template-rows: 40px minmax(0, 1fr);
  /* 列宽不随表格等内容撑开，超出部分由各面板自己滚动 */
  grid-template-columns: minmax(0, 1fr);
  gap: 4px;
  padding: 0 4px 4px;
  box-sizing: border-box;
}

.topbar {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 0 8px;
  background: var(--header-bg);
  border-bottom: 1px solid var(--panel-border);
  margin: 0 -4px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 700;
  font-size: 14px;
}

.brand img {
  width: 22px;
  height: 22px;
}

.who {
  display: flex;
  gap: 10px;
  color: var(--text-secondary);
}

.who .user {
  font-weight: 600;
  color: var(--text-strong);
}

.metrics {
  display: flex;
  gap: 16px;
}

.metrics b {
  font-weight: 600;
  color: var(--text-strong);
}

.right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 10px;
}

.work {
  min-height: 0;
  min-width: 0;
}

/* 面板内容撑满，滚动交给各组件自己 */
.work :deep(.el-splitter-panel) {
  overflow: hidden;
}

.fill {
  height: 100%;
  box-sizing: border-box;
}

.quotes .panel-body {
  overflow: hidden;
}

.hint {
  margin-left: auto;
  font-weight: normal;
}

.side {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 4px;
  min-height: 0;
}
</style>
