<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '@/api/http'
import type { Counter, LoginStatus } from '@/api/types'
import { useSessionStore } from '@/stores/session'
import ThemeToggle from '@/components/ThemeToggle.vue'
import { useStorage } from '@/utils/storage'
import { errorText } from '@/utils/format'

const REMEMBER_KEY = 'jctp.login'

/** 勾选“记住”时保存的登录选项（不含密码） */
interface Remembered {
  counterId?: string
  apiVersion?: string
  userId?: string
}

const router = useRouter()
const session = useSessionStore()
const counters = ref<Counter[]>([])
const versions = ref<string[]>([])
const loading = ref(false)
const progress = ref<LoginStatus | null>(null)
const error = ref('')
const form = reactive({ counterId: '', apiVersion: '', userId: '', password: '', remember: true })
const remembered = useStorage<Remembered | null>(REMEMBER_KEY, null)
const simulated = computed(() => counters.value.find((c) => c.id === form.counterId)?.simulated ?? false)

onMounted(async () => {
  const saved = remembered.value ?? {}
  try {
    const [c, v] = await Promise.all([api.counters(), api.versions()])
    counters.value = c
    versions.value = v
  } catch (e) {
    error.value = `无法连接服务端：${errorText(e)}`
    return
  }
  form.counterId =
    counters.value.find((c) => c.id === saved.counterId)?.id ?? counters.value[0]?.id ?? ''
  form.apiVersion = versions.value.find((v) => v === saved.apiVersion) ?? versions.value[0] ?? ''
  form.userId = saved.userId ?? ''
})

async function submit() {
  // 登录中忽略重复提交，否则会签发多余的会话
  if (loading.value) return
  const missing = missingText()
  if (missing) {
    error.value = missing
    return
  }
  loading.value = true
  progress.value = null
  error.value = ''
  try {
    await session.login(form.counterId, form.userId.trim(), form.password, form.apiVersion, (s) => {
      progress.value = s
    })
    remembered.value = form.remember ? { counterId: form.counterId, apiVersion: form.apiVersion, userId: form.userId.trim() } : null
    ElMessage.success(`登录成功，交易日 ${session.info?.tradingDay ?? ''}`)
    router.replace({ name: 'main' })
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

/** 未填项的提示；模拟柜台不用选版本，账号密码可任意填写 */
function missingText(): string {
  if (!form.counterId) return '请选择交易柜台'
  if (!simulated.value && !form.apiVersion) return '请选择 CTP API 版本'
  const fields = [!form.userId.trim() && '资金账号', !form.password && '交易密码'].filter(Boolean).join('和')
  if (!fields) return ''
  return simulated.value ? `请输入${fields}（模拟柜台可任意填写）` : `请输入${fields}`
}

function progressText(s: LoginStatus | null): string {
  if (!s) return '正在发起登录…'
  const count = s.instruments > 0 ? ` ${s.instruments.toLocaleString()} 个` : ''
  return `${s.stage}${count} · 已用时 ${Math.round(s.elapsedMillis / 1000)}s`
}
</script>

<template>
  <div class="login">
    <ThemeToggle class="theme" />
    <div class="card">
      <div class="brand">
        <img src="/favicon.svg" alt="" />
        <div>
          <div class="title">JCTP 期货交易</div>
          <div class="muted">CTP 行情 · 交易 · K 线</div>
        </div>
      </div>
      <el-form label-position="top" size="default" @submit.prevent="submit">
        <el-form-item label="交易柜台">
          <el-select v-model="form.counterId" placeholder="选择柜台" style="width: 100%">
            <el-option v-for="c in counters" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-alert
          v-if="simulated"
          class="sim-tip"
          type="info"
          :closable="false"
          show-icon
          title="本地模拟行情与撮合，不连 CTP，任意账号密码即可登录"
        />
        <el-form-item v-else label="CTP API 版本">
          <el-select v-model="form.apiVersion" placeholder="须与柜台要求一致" style="width: 100%">
            <el-option v-for="v in versions" :key="v" :label="`v${v}`" :value="v" />
          </el-select>
        </el-form-item>
        <el-form-item label="资金账号">
          <el-input v-model="form.userId" placeholder="投资者代码" autocomplete="username" clearable />
        </el-form-item>
        <el-form-item label="交易密码">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            autocomplete="current-password"
            show-password
          />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.remember">记住柜台、版本和账号</el-checkbox>
        </el-form-item>
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="error" />
        <el-button type="primary" native-type="submit" :loading="loading" style="width: 100%">
          {{ loading ? '正在连接柜台…' : '登 录' }}
        </el-button>
        <div v-if="loading" class="progress">
          <el-progress :percentage="100" :indeterminate="true" :show-text="false" :stroke-width="4" />
          <div class="muted hint">{{ progressText(progress) }}</div>
        </div>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.login {
  position: relative;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--login-bg);
}

.theme {
  position: absolute;
  top: 12px;
  right: 16px;
}

.card {
  width: 360px;
  padding: 28px 32px 24px;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 8px;
  box-shadow: var(--card-shadow);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}

.brand img {
  width: 40px;
  height: 40px;
}

.title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-strong);
}

.sim-tip {
  margin-bottom: 18px;
}

.error {
  margin-bottom: 12px;
}

.progress {
  margin-top: 12px;
}

.hint {
  margin-top: 8px;
  text-align: center;
}
</style>
