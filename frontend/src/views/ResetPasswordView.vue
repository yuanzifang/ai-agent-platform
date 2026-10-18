<template>
  <div class="reset-wrap">
    <el-card class="reset-card">
      <h2>重置密码</h2>
      <el-form label-position="top" @keyup.enter="submit">
        <el-form-item label="新密码（至少 8 位，含字母与数字）">
          <el-input v-model="password" type="password" show-password />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="confirm" type="password" show-password />
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="loading" @click="submit">重置密码</el-button>
        <el-button text style="width: 100%; margin-left: 0; margin-top: 6px" @click="$router.push('/login')">
          返回登录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api/http'

const route = useRoute()
const router = useRouter()
const token = String(route.query.token || '')
const password = ref('')
const confirm = ref('')
const loading = ref(false)

async function submit() {
  if (!token) { ElMessage.error('重置链接缺少 token'); return }
  if (password.value.length < 8) { ElMessage.warning('密码至少 8 位'); return }
  if (password.value !== confirm.value) { ElMessage.warning('两次输入的密码不一致'); return }
  loading.value = true
  try {
    await http.post('/auth/reset-password', { token, newPassword: password.value })
    ElMessage.success('密码已重置，请使用新密码登录')
    router.push('/login')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.reset-wrap { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #f5f7fa; }
.reset-card { width: 420px; padding: 8px; }
h2 { text-align: center; margin-bottom: 18px; }
</style>
