<script setup lang="ts">
import { Moon, Sunny } from '@element-plus/icons-vue'
import { useThemeStore } from '@/stores/theme'

const theme = useThemeStore()
</script>

<template>
  <el-button
    size="small"
    circle
    :icon="theme.theme === 'dark' ? Sunny : Moon"
    :title="theme.theme === 'dark' ? '切换到明亮模式' : '切换到暗夜模式'"
    @click="theme.toggle"
  />
</template>
