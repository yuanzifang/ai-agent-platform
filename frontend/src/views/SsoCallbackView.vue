<template>
  <div class="sso-wrap">
    <el-card class="sso-card">
      <h2>企业 SSO 登录</h2>
      <div v-if="loading" class="loading"><el-skeleton :rows="3" animated /></div>
      <el-result v-else-if="error" icon="error" title="SSO 登录失败" :sub-title="error">
        <template #extra>
          <el-button type="primary" @click="$router.push('/login')">返回登录</el-button>
        </template>
      </el-result>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '../api/http'
import { useUserStore } from '../stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const loading = ref(true)
const error = ref('')

onMounted(async () => {
  const code = String(route.query.code || '')
  const state = String(route.query.state || '')
  const err = route.query.error_description || route.query.error
  if (err) {
    error.value = String(err)
    loading.value = false
    return
  }
  if (!code || !state) {
    error.value = '缺少 code/state 参数'
    loading.value = false
    return
  }
  try {
    const data = await http.post('/auth/sso/exchange', { code, state }) as any
    userStore.applyLogin(data)
    router.push(userStore.userInfo?.isPlatformAdmin ? '/admin/platform' : '/agents')
  } catch (e: any) {
    error.value = e?.message || 'SSO 交换失败'
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.sso-wrap { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #f5f7fa; }
.sso-card { width: 420px; padding: 8px; }
h2 { text-align: center; margin-bottom: 16px; }
</style>
