<script setup lang="ts">
import { ref, watch } from 'vue'
import { INDICATOR_NAMES, INDICATOR_PARAMS, normalizeParams } from '@/utils/indicators'

/** 指标参数设置，对照 klinecharts/pro 的 indicator-setting-modal：留空的参数取缺省值，可选周期线留空即不画 */
const props = defineProps<{ name: string; calcParams: number[] }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ confirm: [calcParams: number[]] }>()

const values = ref<(number | undefined)[]>([])

watch(visible, (open) => {
  if (open) values.value = (INDICATOR_PARAMS[props.name] ?? []).map((_, i) => props.calcParams[i])
})

function confirm() {
  emit('confirm', normalizeParams(props.name, values.value))
  visible.value = false
}
</script>

<template>
  <el-dialog v-model="visible" :title="INDICATOR_NAMES[name] ?? name" width="360px" append-to-body>
    <el-form v-if="INDICATOR_PARAMS[name]?.length" label-width="70px" size="small">
      <el-form-item v-for="(param, i) in INDICATOR_PARAMS[name]" :key="i" :label="param.label">
        <el-input-number
          v-model="values[i]"
          :min="param.min"
          :precision="param.precision"
          :placeholder="param.default == null ? '留空不显示' : String(param.default)"
          controls-position="right"
          style="width: 200px"
        />
      </el-form-item>
    </el-form>
    <div v-else class="muted">该指标没有可设置的参数</div>
    <template #footer>
      <el-button size="small" @click="visible = false">取消</el-button>
      <el-button size="small" type="primary" @click="confirm">确定</el-button>
    </template>
  </el-dialog>
</template>
