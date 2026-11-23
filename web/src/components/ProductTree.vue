<script setup lang="ts">
import { computed, ref } from 'vue'
import { api } from '@/api/http'
import { isOptionClass, type Instrument, type Product } from '@/api/types'
import { EXCHANGE_ORDER, EXCHANGES } from '@/utils/dict'

const props = defineProps<{ products: Product[]; selected: Product | null }>()
const emit = defineEmits<{
  select: [product: Product]
  pick: [instrument: Instrument]
}>()

type Kind = 'futures' | 'options'
const kind = ref<Kind>('futures')
const filter = ref('')
const search = ref('')

function kindOf(p: Product): Kind | null {
  if (p.productClass === 'Futures') return 'futures'
  return isOptionClass(p.productClass) ? 'options' : null
}

const groups = computed(() => {
  const text = filter.value.trim().toLowerCase()
  const list = props.products.filter(
    (p) =>
      kindOf(p) === kind.value &&
      (!text || p.productId.toLowerCase().includes(text) || p.name.toLowerCase().includes(text)),
  )
  const exchanges = [...new Set(list.map((p) => p.exchangeId))].sort(
    (a, b) => (EXCHANGE_ORDER.indexOf(a) + 1 || 99) - (EXCHANGE_ORDER.indexOf(b) + 1 || 99),
  )
  return exchanges.map((exchangeId) => ({
    exchangeId,
    name: EXCHANGES[exchangeId] ?? exchangeId,
    products: list.filter((p) => p.exchangeId === exchangeId),
  }))
})

const opened = ref<string[]>([...EXCHANGE_ORDER])

async function querySearch(text: string, cb: (items: { value: string; instrument: Instrument }[]) => void) {
  if (!text.trim()) return cb([])
  try {
    const list = await api.search(text)
    cb(list.map((i) => ({ value: `${i.instrumentID}  ${i.instrumentName}`, instrument: i })))
  } catch {
    cb([])
  }
}

function onPick(item: Record<string, unknown>) {
  const instrument = item.instrument as Instrument
  search.value = ''
  const product = props.products.find(
    (p) => p.productId === instrument.productID && p.exchangeId === instrument.exchangeID,
  )
  if (product) {
    kind.value = kindOf(product) ?? kind.value
  }
  emit('pick', instrument)
}
</script>

<template>
  <div class="panel tree">
    <div class="panel-title">
      <el-radio-group v-model="kind" size="small">
        <el-radio-button value="futures">期货</el-radio-button>
        <el-radio-button value="options">期权</el-radio-button>
      </el-radio-group>
    </div>
    <div class="tools">
      <el-autocomplete
        v-model="search"
        :fetch-suggestions="querySearch"
        :debounce="250"
        placeholder="搜索合约 如 au2602"
        clearable
        style="width: 100%"
        @select="onPick"
      />
      <el-input v-model="filter" placeholder="筛选品种" clearable />
    </div>
    <div class="panel-body">
      <el-collapse v-model="opened">
        <el-collapse-item v-for="g in groups" :key="g.exchangeId" :name="g.exchangeId">
          <template #title>
            <span class="exchange">{{ g.name }}</span>
            <span class="muted">&nbsp;{{ g.products.length }}</span>
          </template>
          <div
            v-for="p in g.products"
            :key="p.exchangeId + p.productId"
            class="product"
            :class="{ active: selected?.productId === p.productId && selected?.exchangeId === p.exchangeId }"
            @click="emit('select', p)"
          >
            <span>{{ p.name }}</span>
            <span class="muted">{{ p.productId }} · {{ p.count ?? (p.loading ? '加载中' : '点击加载') }}</span>
          </div>
        </el-collapse-item>
      </el-collapse>
      <div v-if="!groups.length" class="muted empty">无可交易品种</div>
    </div>
  </div>
</template>

<style scoped>
.tree {
  height: 100%;
}

.tools {
  padding: 6px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  border-bottom: 1px solid var(--panel-border);
}

.exchange {
  font-weight: 600;
  padding-left: 8px;
}

.product {
  display: flex;
  justify-content: space-between;
  padding: 4px 10px 4px 18px;
  cursor: pointer;
}

.product:hover {
  background: var(--row-hover-bg);
}

.product.active {
  background: var(--row-active-bg);
  color: var(--text-strong);
}

.empty {
  padding: 16px;
  text-align: center;
}

:deep(.el-collapse) {
  --el-collapse-header-height: 28px;
  --el-collapse-header-bg-color: var(--panel-bg);
  --el-collapse-content-bg-color: var(--panel-bg);
  border: none;
}

:deep(.el-collapse-item__content) {
  padding-bottom: 4px;
}
</style>
