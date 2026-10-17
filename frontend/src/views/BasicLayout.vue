<template>
  <el-container class="layout">
    <el-header class="header">
      <div class="brand" @click="$router.push('/agents')">AI Agent 平台</div>
      <div class="spacer" />
      <el-dropdown @command="onCommand">
        <span class="user-chip">
          <el-icon><User /></el-icon>
          {{ userName }}
          <el-icon><ArrowDown /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </el-header>
    <el-container class="body">
      <el-aside width="200px" class="aside">
        <el-menu :default-active="$route.path" router>
          <el-menu-item index="/agents">
            <el-icon><ChatDotRound /></el-icon>Agent 列表
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { User, ArrowDown, ChatDotRound } from '@element-plus/icons-vue'

const router = useRouter()
const userStore = useUserStore()
const userName = computed(() => userStore.userInfo?.displayName || '用户')

function onCommand(cmd: string) {
  if (cmd === 'logout') {
    userStore.logout()
    router.push('/login')
  }
}
</script>

<style scoped>
.layout {
  height: 100vh;
}
.header {
  display: flex;
  align-items: center;
  border-bottom: 1px solid #e4e7ed;
  background: #fff;
}
.brand {
  font-size: 17px;
  font-weight: 600;
  cursor: pointer;
}
.spacer {
  flex: 1;
}
.user-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  color: #606266;
  outline: none;
}
.body {
  height: calc(100vh - 60px);
}
.aside {
  border-right: 1px solid #e4e7ed;
  background: #fff;
}
.main {
  padding: 16px;
  background: #f5f7fa;
}
</style>
