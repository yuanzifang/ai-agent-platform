<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <div class="login-lang">
        <el-select v-model="currentLocale" size="small" style="width: 92px">
          <el-option label="中文" value="zh-CN" />
          <el-option label="English" value="en-US" />
        </el-select>
      </div>
      <h2>{{ t('app.name') }}</h2>

      <!-- 邀请激活模式 -->
      <template v-if="inviteToken">
        <el-alert type="info" :closable="false" class="mb"
                  :title="t('login.invite')" :description="t('login.inviteDesc')" />
        <el-form label-position="top" @keyup.enter="accept">
          <el-form-item :label="t('login.username')">
            <el-input v-model="inviteForm.username" placeholder="设置你的用户名" />
          </el-form-item>
          <el-form-item :label="t('login.password')+' (8+)'">
            <el-input v-model="inviteForm.password" type="password" show-password />
          </el-form-item>
          <el-button type="primary" style="width: 100%" :loading="loading" @click="accept">
            {{ t('login.activate') }}
          </el-button>
          <el-button text style="width: 100%; margin-top: 8px; margin-left: 0" @click="inviteToken = ''">
            {{ t('login.back') }}
          </el-button>
        </el-form>
      </template>

      <!-- 登录模式 -->
      <el-form v-else :model="form" label-position="top" @keyup.enter="submit">
        <el-form-item :label="t('login.username')">
          <el-input v-model="form.username" placeholder="用户名" />
        </el-form-item>
        <el-form-item :label="t('login.password')">
          <el-input v-model="form.password" type="password" placeholder="密码" show-password />
        </el-form-item>
        <el-form-item :label="t('login.tenant')">
          <el-input v-model="form.tenantKey" placeholder="平台管理员可留空" />
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="loading" @click="submit">
          {{ t('login.login') }}
        </el-button>
        <el-button text type="primary" style="width: 100%; margin-left: 0; margin-top: 6px" @click="ssoLogin">
          {{ t('login.sso') }}
        </el-button>
        <div class="forgot"><el-link type="info" :underline="false" @click="forgotPassword">{{ t('login.forgot') }}</el-link></div>
      </el-form>

      <!-- MFA 二次验证 -->
      <el-form v-if="mfaMode" label-position="top" @keyup.enter="verifyMfa" class="mt12">
        <el-alert type="info" :closable="false" :title="t('login.mfa')"
                  description="请输入身份验证器中的 6 位动态码（或使用恢复码）" />
        <el-form-item :label="t('login.code')" class="mt8">
          <el-input v-model="mfaCode" placeholder="6 位数字或恢复码" maxlength="11" />
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="loading" @click="verifyMfa">
          {{ t('login.verify') }}
        </el-button>
        <el-button text style="width: 100%; margin-left: 0; margin-top: 6px" @click="mfaMode = false">
          {{ t('login.back') }}
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { setLocale, type Locale } from '../i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '../stores/user'
import http from '../api/http'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { t, locale } = useI18n()
const currentLocale = computed({ get: () => locale.value, set: (v: Locale) => setLocale(v) })
const form = reactive({ username: '', password: '', tenantKey: '' })
const inviteForm = reactive({ username: '', password: '' })
const inviteToken = ref((route.query.invite as string) || '')
const loading = ref(false)
const mfaMode = ref(false)
const mfaToken = ref('')
const mfaCode = ref('')

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const data = await userStore.login(form.username, form.password, form.tenantKey || undefined)
    if (data?.mfaRequired) {
      mfaToken.value = data.mfaToken
      mfaMode.value = true
      return
    }
    router.push(userStore.userInfo?.isPlatformAdmin ? '/admin/platform' : '/agents')
  } finally {
    loading.value = false
  }
}

async function forgotPassword() {
  try {
    const { value } = await ElMessageBox.prompt('请输入用户名，系统将生成重置链接', '忘记密码', {
      inputPlaceholder: '用户名', confirmButtonText: '提交', cancelButtonText: '取消'
    })
    if (!value) return
    await http.post('/auth/forgot-password', { username: value })
    ElMessage.success('如账号存在，重置链接已生成（开发环境请查看后端日志）')
  } catch { /* 取消 */ }
}

async function ssoLogin() {
  if (!form.tenantKey.trim()) {
    ElMessage.warning('请先填写租户标识')
    return
  }
  const available = await http.get('/auth/sso/available', {
    params: { tenantKey: form.tenantKey.trim() }
  }) as any
  if (!available?.enabled) {
    ElMessage.warning('该租户未启用 SSO')
    return
  }
  const res = await http.get('/auth/sso/authorize', {
    params: { tenantKey: form.tenantKey.trim() }
  }) as any
  window.location.href = res.authorizeUrl
}

async function verifyMfa() {
  if (!mfaCode.value.trim()) { ElMessage.warning('请输入验证码'); return }
  loading.value = true
  try {
    const data = await http.post('/auth/mfa/verify', {
      mfaToken: mfaToken.value, code: mfaCode.value.trim()
    }) as any
    userStore.applyLogin(data)
    router.push(userStore.userInfo?.isPlatformAdmin ? '/admin/platform' : '/agents')
  } finally {
    loading.value = false
  }
}

async function accept() {
  if (!inviteForm.username || inviteForm.password.length < 8) {
    ElMessage.warning('用户名必填，密码至少 8 位')
    return
  }
  loading.value = true
  try {
    await http.post('/auth/invitations/accept', {
      token: inviteToken.value,
      username: inviteForm.username,
      password: inviteForm.password
    })
    ElMessage.success('激活成功，请登录')
    inviteToken.value = ''
    form.username = inviteForm.username
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.mt8 { margin-top: 8px; }
.forgot { text-align: right; margin-top: 8px; font-size: 12px; }
.mt12 { margin-top: 12px; }
.login-wrap {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
}
.login-lang { display: flex; justify-content: flex-end; margin-bottom: 4px; }
.login-card {
  width: 380px;
}
.mb {
  margin-bottom: 14px;
}
</style>
