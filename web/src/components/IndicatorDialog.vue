<script setup lang="ts">
import { INDICATOR_NAMES, MAIN_INDICATORS, SUB_INDICATORS } from '@/utils/indicators'

/** 指标选择，对照 klinecharts/pro 的 indicator-modal：主图可叠加多个，副图每个指标一个窗格，勾选即生效 */
defineProps<{ main: string[]; sub: string[] }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ toggleMain: [name: string]; toggleSub: [name: string] }>()
</script>

<template>
  <el-dialog v-model="visible" title="指标" width="400px" append-to-body class="indicator-dialog">
    <div class="list">
      <div class="title">主图指标</div>
      <div v-for="name in MAIN_INDICATORS" :key="`main-${name}`" class="row" @click="emit('toggleMain', name)">
        <el-checkbox :model-value="main.includes(name)" @click.prevent>{{ INDICATOR_NAMES[name] }}</el-checkbox>
      </div>
      <div class="title">副图指标</div>
      <div v-for="name in SUB_INDICATORS" :key="`sub-${name}`" class="row" @click="emit('toggleSub', name)">
        <el-checkbox :model-value="sub.includes(name)" @click.prevent>{{ INDICATOR_NAMES[name] }}</el-checkbox>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
/* 列表留在对话框内容区内滚动，滚动条不超出对话框 */
.list {
  max-height: 60vh;
  overflow-y: auto;
}

.title {
  padding: 8px 8px 4px;
  font-size: 12px;
  color: var(--muted-color);
}

.row {
  display: flex;
  align-items: center;
  height: 36px;
  padding: 0 8px;
  border-radius: 4px;
  cursor: pointer;
}

.row:hover {
  background: var(--row-hover-bg);
}

.row :deep(.el-checkbox) {
  pointer-events: none;
}
</style>
