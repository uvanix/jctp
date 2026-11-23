<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '@/api/http'
import type { SettlementInfo } from '@/api/types'
import { errorText } from '@/utils/format'

/** 选择的交易日（YYYY-MM-DD），为空时查最近一次结算单 */
const tradingDay = ref<string>('')
const settlement = ref<SettlementInfo | null>(null)
const loading = ref(false)
const error = ref('')
/** 已查询过，用于区分“尚未查询”与“没有结算单” */
const queried = ref(false)

/** 结算单查询走柜台，较慢，只在打开页签或点击查询时请求 */
async function load() {
  loading.value = true
  error.value = ''
  try {
    settlement.value = await api.settlement(tradingDay.value || undefined)
    queried.value = true
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

function disabledDate(date: Date): boolean {
  return date.getTime() > Date.now()
}

onMounted(load)
</script>

<template>
  <div class="settlement">
    <div class="toolbar">
      <el-date-picker
        v-model="tradingDay"
        type="date"
        value-format="YYYY-MM-DD"
        placeholder="最近一次结算单"
        size="small"
        clearable
        :disabled-date="disabledDate"
        style="width: 160px"
      />
      <el-button size="small" type="primary" :loading="loading" @click="load">查询</el-button>
      <span v-if="settlement" class="muted">
        交易日 {{ settlement.tradingDay }} · 结算编号 {{ settlement.settlementID }} · 投资者 {{ settlement.investorID }}
      </span>
    </div>
    <div v-loading="loading" class="body">
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <pre v-else-if="settlement" class="content">{{ settlement.content }}</pre>
      <el-empty
        v-else-if="queried"
        :description="tradingDay ? `${tradingDay} 没有结算单` : '没有结算单'"
        :image-size="60"
      />
    </div>
  </div>
</template>

<style scoped>
.settlement {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
}

.body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 0 8px 8px;
}

/* 结算单按 GBK 字节宽度排版：宋体等宽且汉字恰为两个字符宽，表格才能对齐 */
.content {
  margin: 0;
  font-family: 'NSimSun', 'SimSun', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.45;
  white-space: pre;
  color: var(--el-text-color-primary);
}
</style>
