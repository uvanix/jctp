import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, getToken, setToken } from '@/api/http'
import type { LoginStatus, SessionInfo } from '@/api/types'

const LOGIN_POLL_MS = 500

export const useSessionStore = defineStore('session', () => {
  const info = ref<SessionInfo | null>(null)

  /** 发起登录并轮询进度，直至连接完成或失败 */
  async function login(
    counterId: string,
    userId: string,
    password: string,
    apiVersion: string,
    onProgress: (status: LoginStatus) => void = () => {},
  ) {
    let status = await api.login(counterId, userId, password, apiVersion)
    while (status.state === 'connecting') {
      onProgress(status)
      await new Promise((resolve) => setTimeout(resolve, LOGIN_POLL_MS))
      status = await api.loginStatus(status.loginId)
    }
    onProgress(status)
    if (status.state === 'failed' || !status.session) {
      throw new Error(status.error ?? '登录失败')
    }
    setToken(status.session.token)
    info.value = status.session
  }

  /** 页面刷新后用 sessionStorage 中的令牌恢复会话 */
  async function restore(): Promise<boolean> {
    if (info.value) return true
    if (!getToken()) return false
    try {
      info.value = await api.session()
      return true
    } catch {
      return false
    }
  }

  async function logout() {
    try {
      await api.logout()
    } finally {
      clear()
    }
  }

  function clear() {
    setToken(null)
    info.value = null
  }

  return { info, login, restore, logout, clear }
})
