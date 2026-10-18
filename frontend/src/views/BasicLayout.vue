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
            <el-dropdown-item command="security">安全设置</el-dropdown-item>
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
          <el-menu-item index="/square">
            <el-icon><Star /></el-icon>Agent 广场
          </el-menu-item>
          <el-menu-item index="/templates">
            <el-icon><Grid /></el-icon>模板库
          </el-menu-item>
          <el-menu-item index="/knowledge">
            <el-icon><Collection /></el-icon>知识库
          </el-menu-item>
          <el-menu-item index="/skills">
            <el-icon><MagicStick /></el-icon>技能管理
          </el-menu-item>
          <el-menu-item index="/tools">
            <el-icon><SetUp /></el-icon>工具管理
          </el-menu-item>
          <el-menu-item index="/mcp-servers">
            <el-icon><Link /></el-icon>MCP Server
          </el-menu-item>
          <el-menu-item v-if="can('prompt:manage')" index="/prompt-templates">
            <el-icon><DocumentCopy /></el-icon>Prompt 模板
          </el-menu-item>
          <el-menu-item index="/provider">
            <el-icon><Connection /></el-icon>模型接入
          </el-menu-item>
          <el-menu-item v-if="can('billing:read')" index="/settings/usage">
            <el-icon><DataLine /></el-icon>用量看板
          </el-menu-item>
          <el-menu-item v-if="can('billing:read')" index="/billing">
            <el-icon><Money /></el-icon>计费管理
          </el-menu-item>
          <el-menu-item v-if="can('user:manage')" index="/admin/users">
            <el-icon><UserFilled /></el-icon>成员管理
          </el-menu-item>
          <el-menu-item v-if="can('tenant:manage')" index="/admin/tenant">
            <el-icon><Setting /></el-icon>租户设置
          </el-menu-item>
          <el-menu-item v-if="can('audit:read')" index="/audit">
            <el-icon><Document /></el-icon>审计日志
          </el-menu-item>
          <el-menu-item v-if="userStore.userInfo?.isPlatformAdmin" index="/admin/platform">
            <el-icon><OfficeBuilding /></el-icon>平台管理
          </el-menu-item>
          <el-menu-item v-if="userStore.userInfo?.isPlatformAdmin" index="/admin/settings">
            <el-icon><Tools /></el-icon>系统设置
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>

    <el-dialog v-model="securityVisible" title="安全设置" width="560px">
      <div class="sec-row">
        <span class="sec-label">多因素认证（MFA / TOTP）</span>
        <el-tag :type="mfaEnabled ? 'success' : 'info'">{{ mfaEnabled ? '已启用' : '未启用' }}</el-tag>
      </div>
      <template v-if="!mfaEnabled">
        <div v-if="!mfaSetup" class="hint">启用后登录需要输入身份验证器中的 6 位动态码。</div>
        <div v-else class="setup-box">
          <div class="step">1. 在身份验证器（Google Authenticator / 1Password 等）添加账户，密钥：</div>
          <div class="secret-row">
            <code class="secret">{{ mfaSetup.secret }}</code>
            <el-button size="small" @click="copyText(mfaSetup.secret)">复制</el-button>
          </div>
          <div class="hint">otpauth：{{ mfaSetup.otpauthUri }}</div>
          <div class="step mt8">2. 输入验证器生成的 6 位动态码：</div>
          <el-input v-model="mfaCode" placeholder="6 位动态码" maxlength="6" class="mt8" />
          <el-button type="primary" class="mt8" :loading="mfaLoading" @click="enableMfa">启用 MFA</el-button>
        </div>
        <el-button v-if="!mfaSetup" type="primary" @click="setupMfa">初始化 MFA</el-button>
      </template>
      <template v-else>
        <div class="hint">已启用；剩余恢复码 {{ recoveryLeft }} 个。关闭 MFA 需输入动态码或恢复码。</div>
        <el-input v-model="mfaCode" placeholder="动态码或恢复码" class="mt8" />
        <el-button type="danger" class="mt8" :loading="mfaLoading" @click="disableMfa">关闭 MFA</el-button>
      </template>
      <el-divider content-position="left">修改密码</el-divider>
      <el-form label-width="90px">
        <el-form-item label="原密码">
          <el-input v-model="pwdForm.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="至少 8 位，含字母与数字" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="pwdForm.confirm" type="password" show-password />
        </el-form-item>
        <el-button :loading="pwdSaving" @click="changePassword">修改密码</el-button>
        <span class="hint" style="margin-left: 8px">修改成功后需重新登录</span>
      </el-form>
      <div v-if="recoveryCodes.length" class="recovery-box">
        <div class="recovery-title">恢复码（仅显示一次，请妥善保存）</div>
        <div class="recovery-grid">
          <code v-for="c in recoveryCodes" :key="c">{{ c }}</code>
        </div>
        <el-button size="small" @click="copyText(recoveryCodes.join('\n'))">复制全部</el-button>
      </div>
    </el-dialog>
  </el-container>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'
import http from '../api/http'
import { User, ArrowDown, ChatDotRound, Collection, Connection, DataLine,
  UserFilled, Setting, OfficeBuilding, Document, MagicStick, SetUp, DocumentCopy, Grid, Star, Link, Money } from '@element-plus/icons-vue'

const router = useRouter()
const userStore = useUserStore()
const userName = computed(() => userStore.userInfo?.displayName || '用户')

function can(perm: string) {
  const perms = userStore.userInfo?.permissions || []
  return userStore.userInfo?.isPlatformAdmin || perms.includes(perm)
}

const securityVisible = ref(false)
const mfaEnabled = ref(false)
const recoveryLeft = ref(0)
const mfaSetup = ref<any>(null)
const mfaCode = ref('')
const mfaLoading = ref(false)
const recoveryCodes = ref<string[]>([])
const pwdSaving = ref(false)
const pwdForm = ref({ oldPassword: '', newPassword: '', confirm: '' })

async function onCommand(cmd: string) {
  if (cmd === 'logout') {
    userStore.logout()
    router.push('/login')
  } else if (cmd === 'security') {
    await openSecurity()
  }
}

async function openSecurity() {
  mfaSetup.value = null
  mfaCode.value = ''
  recoveryCodes.value = []
  const st = await http.get('/auth/mfa/status') as any
  mfaEnabled.value = !!st.enabled
  recoveryLeft.value = st.recoveryCodesLeft || 0
  securityVisible.value = true
}

async function setupMfa() {
  mfaSetup.value = await http.post('/auth/mfa/setup') as any
  mfaCode.value = ''
}

async function enableMfa() {
  if (!mfaCode.value.trim()) { ElMessage.warning('请输入动态码'); return }
  mfaLoading.value = true
  try {
    const res = await http.post('/auth/mfa/enable', { code: mfaCode.value.trim() }) as any
    recoveryCodes.value = res.recoveryCodes || []
    mfaEnabled.value = true
    recoveryLeft.value = recoveryCodes.value.length
    mfaSetup.value = null
    mfaCode.value = ''
    ElMessage.success('MFA 已启用')
  } finally {
    mfaLoading.value = false
  }
}

async function disableMfa() {
  if (!mfaCode.value.trim()) { ElMessage.warning('请输入动态码或恢复码'); return }
  mfaLoading.value = true
  try {
    await http.post('/auth/mfa/disable', { code: mfaCode.value.trim() })
    mfaEnabled.value = false
    recoveryCodes.value = []
    recoveryLeft.value = 0
    mfaCode.value = ''
    ElMessage.success('MFA 已关闭')
  } finally {
    mfaLoading.value = false
  }
}

async function changePassword() {
  if (!pwdForm.value.oldPassword || pwdForm.value.newPassword.length < 8) {
    ElMessage.warning('请填写原密码，新密码至少 8 位'); return
  }
  if (pwdForm.value.newPassword !== pwdForm.value.confirm) {
    ElMessage.warning('两次输入的新密码不一致'); return
  }
  pwdSaving.value = true
  try {
    await http.post('/auth/change-password', {
      oldPassword: pwdForm.value.oldPassword, newPassword: pwdForm.value.newPassword
    })
    ElMessage.success('密码已修改，请重新登录')
    securityVisible.value = false
    userStore.clear()
    router.push('/login')
  } finally {
    pwdSaving.value = false
  }
}

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请手动复制')
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
  overflow-y: auto;
}
.main {
  padding: 16px;
  background: #f5f7fa;
}
.sec-row { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.sec-label { font-weight: 600; }
.hint { color: #909399; font-size: 12px; word-break: break-all; }
.step { font-size: 13px; color: #606266; }
.setup-box { border: 1px solid #ebeef5; border-radius: 6px; padding: 10px; margin-bottom: 10px; }
.secret-row { display: flex; align-items: center; gap: 8px; margin: 6px 0; }
.secret { font-family: monospace; font-size: 14px; letter-spacing: 1px; }
.mt8 { margin-top: 8px; }
.recovery-box { margin-top: 14px; border: 1px solid #e6a23c; background: #fdf6ec; border-radius: 6px; padding: 10px; }
.recovery-title { font-weight: 600; color: #e6a23c; margin-bottom: 6px; }
.recovery-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 4px; margin-bottom: 8px; }
.recovery-grid code { font-family: monospace; font-size: 13px; }
</style>
