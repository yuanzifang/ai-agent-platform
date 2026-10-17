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

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await userStore.login(form.username, form.password, form.tenantKey || undefined)
    router.push('/agents')
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
