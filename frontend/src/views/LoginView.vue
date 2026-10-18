<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <h2>AI Agent 平台</h2>

      <!-- 邀请激活模式 -->
      <template v-if="inviteToken">
        <el-alert type="info" :closable="false" class="mb"
                  title="邀请激活" description="你被邀请加入团队，设置用户名和密码完成激活" />
        <el-form label-position="top" @keyup.enter="accept">
          <el-form-item label="用户名">
            <el-input v-model="inviteForm.username" placeholder="设置你的用户名" />
          </el-form-item>
          <el-form-item label="密码（至少 8 位）">
            <el-input v-model="inviteForm.password" type="password" show-password />
          </el-form-item>
          <el-button type="primary" style="width: 100%" :loading="loading" @click="accept">
            激活并加入
          </el-button>
          <el-button text style="width: 100%; margin-top: 8px; margin-left: 0" @click="inviteToken = ''">
            返回登录
          </el-button>
        </el-form>
      </template>

      <!-- 登录模式 -->
      <el-form v-else :model="form" label-position="top" @keyup.enter="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" placeholder="密码" show-password />
        </el-form-item>
        <el-form-item label="租户标识（可选）">
          <el-input v-model="form.tenantKey" placeholder="平台管理员可留空" />
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="loading" @click="submit">
          登 录
        </el-button>
        <el-button text type="primary" style="width: 100%; margin-left: 0; margin-top: 6px" @click="ssoLogin">
          企业 SSO 登录
        </el-button>
      </el-form>

      <!-- MFA 二次验证 -->
      <el-form v-if="mfaMode" label-position="top" @keyup.enter="verifyMfa" class="mt12">
        <el-alert type="info" :closable="false" title="需要二次验证"
                  description="请输入身份验证器中的 6 位动态码（或使用恢复码）" />
        <el-form-item label="验证码" class="mt8">
          <el-input v-model="mfaCode" placeholder="6 位数字或恢复码" maxlength="11" />
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="loading" @click="verifyMfa">
          验 证
        </el-button>
        <el-button text style="width: 100%; margin-left: 0; margin-top: 6px" @click="mfaMode = false">
          返回登录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'
import http from '../api/http'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
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
.mt12 { margin-top: 12px; }
.login-wrap {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
}
.login-card {
  width: 380px;
}
.mb {
  margin-bottom: 14px;
}
</style>
