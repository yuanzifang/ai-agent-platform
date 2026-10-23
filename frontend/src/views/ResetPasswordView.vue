<template>
  <div class="reset-wrap">
    <el-card class="reset-card">
      <h2>{{ t('resetPassword.title') }}</h2>
      <el-form label-position="top" @keyup.enter="submit">
        <el-form-item :label="t('resetPassword.newPassword')">
          <el-input v-model="password" type="password" show-password />
        </el-form-item>
        <el-form-item :label="t('resetPassword.confirm')">
          <el-input v-model="confirm" type="password" show-password />
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="loading" @click="submit">{{ t('resetPassword.reset') }}</el-button>
        <el-button text style="width: 100%; margin-left: 0; margin-top: 6px" @click="$router.push('/login')">
          {{ t('resetPassword.back') }}
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api/http'

const { t } = useI18n()

const route = useRoute()
const router = useRouter()
const token = String(route.query.token || '')
const password = ref('')
const confirm = ref('')
const loading = ref(false)

async function submit() {
  if (!token) { ElMessage.error(t('resetPassword.missingToken')); return }
  if (password.value.length < 8) { ElMessage.warning(t('resetPassword.min8')); return }
  if (password.value !== confirm.value) { ElMessage.warning(t('resetPassword.mismatch')); return }
  loading.value = true
  try {
    await http.post('/auth/reset-password', { token, newPassword: password.value })
    ElMessage.success(t('resetPassword.success'))
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