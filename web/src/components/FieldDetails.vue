<script setup lang="ts" generic="T">
import { fieldValue, formatField, isNumeric, type FieldGroup } from '@/utils/fields'
import { trendClass } from '@/utils/format'

/** 按分组展示一条记录的全部字段（持仓、报单、成交、资金的明细） */
withDefaults(defineProps<{ row: T; groups: FieldGroup<T>[]; digits?: number; column?: number }>(), {
  digits: 2,
  column: 4,
})
</script>

<template>
  <div class="field-details">
    <el-descriptions v-for="g in groups" :key="g.title" :title="g.title" :column="column" size="small" border>
      <el-descriptions-item v-for="f in g.fields" :key="f.key" :label="f.label">
        <span :class="[isNumeric(f) ? 'num' : '', f.trend ? trendClass(fieldValue(row, f) as number) : '']">
          {{ formatField(row, f, digits) }}
        </span>
      </el-descriptions-item>
    </el-descriptions>
  </div>
</template>

<style scoped>
.field-details {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px 12px;
}

.field-details :deep(.el-descriptions__header) {
  margin-bottom: 4px;
}

.field-details :deep(.el-descriptions__title) {
  font-size: 12px;
  font-weight: 600;
}

.field-details :deep(.el-descriptions__label) {
  width: 110px;
  white-space: nowrap;
}
</style>
