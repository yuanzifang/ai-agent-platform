<template>
  <el-container style="height: 100vh">
    <el-header class="header">
      <span>AI Agent 平台</span>
      <span style="flex: 1" />
      <span style="margin-right: 16px">{{ userStore.userInfo?.displayName || userStore.userInfo?.id }}</span>
      <el-button link @click="logout">退出</el-button>
    </el-header>
    <el-main>
      <el-empty description="可用 Agent 列表（M2 交付：Agent 创建与对话入口）" />
    </el-main>
  </el-container>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'

const router = useRouter()
const userStore = useUserStore()

async function logout() {
  await userStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.header {
  display: flex;
  align-items: center;
  border-bottom: 1px solid #e4e7ed;
}
</style>
