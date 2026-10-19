<template>
  <router-view />
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useUserStore } from './stores/user'

const userStore = useUserStore()

onMounted(() => {
  // 刷新角色/权限，避免升级后旧 localStorage 中缺少新增权限导致按钮被隐藏
  if (userStore.accessToken) userStore.refreshProfile()
})
</script>
