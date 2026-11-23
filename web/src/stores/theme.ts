import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

export type Theme = 'light' | 'dark'

/** 与 index.html 中的启动脚本一致 */
export const THEME_KEY = 'jctp.theme'

/**
 * 明/暗主题。暗色以 html 上的 dark 类表示（Element Plus 的暗色变量与 style.css 的暗色变量都挂在 html.dark 上）；
 * 初始值由 index.html 在应用加载前设置，避免刷新时闪烁。
 */
export const useThemeStore = defineStore('theme', () => {
  const theme = ref<Theme>(document.documentElement.classList.contains('dark') ? 'dark' : 'light')

  watch(theme, (value) => {
    document.documentElement.classList.toggle('dark', value === 'dark')
    document.documentElement.style.colorScheme = value
    localStorage.setItem(THEME_KEY, value)
  })

  function toggle() {
    theme.value = theme.value === 'dark' ? 'light' : 'dark'
  }

  return { theme, toggle }
})
