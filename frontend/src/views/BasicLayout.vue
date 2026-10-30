<template>
  <el-container class="layout">
    <el-header class="header">
      <el-button v-if="isMobile" class="hamburger" text circle :aria-label="t('layout.menu')"
                 @click="mobileMenu = true">
        <el-icon size="20"><Menu /></el-icon>
      </el-button>
      <div class="brand" @click="$router.push('/agents')">{{ t('app.name') }}</div>
      <div class="global-search">
        <el-input v-model="searchKeyword" size="small" clearable :placeholder="t('layout.searchPh')"
                  @input="scheduleSearch" @focus="onSearchFocus" @blur="hideSearchSoon" />
        <div v-if="searchVisible" class="gs-panel" @mousedown.prevent>
          <div v-if="searchKeyword.trim().length < 2" class="gs-empty">{{ t('layout.searchHint') }}</div>
          <div v-else-if="searchLoading" class="gs-empty">…</div>
          <div v-else-if="searchResults.length === 0" class="gs-empty">{{ t('layout.searchEmpty') }}</div>
          <div v-for="(r, i) in searchResults" :key="i" class="gs-item" @click="goSearch(r)">
            <el-tag size="small" :type="r.tagType">{{ r.group }}</el-tag>
            <span class="gs-name" v-html="r.nameHtml || r.name"></span>
            <span class="gs-desc" v-html="r.descHtml || r.desc"></span>
          </div>
        </div>
      </div>
      <div class="spacer" />
      <el-select v-model="currentLocale" size="small" class="lang-select" style="width: 92px" :aria-label="t('common.language')">
        <el-option :label="t('common.chinese')" value="zh-CN" />
        <el-option label="English" value="en-US" />
      </el-select>
      <el-tooltip :content="dark ? t('layout.lightMode') : t('layout.darkMode')">
        <el-button text circle class="theme-btn" :aria-label="t('layout.theme')" @click="toggleDark">
          <el-icon size="18"><Sunny v-if="dark" /><Moon v-else /></el-icon>
        </el-button>
      </el-tooltip>
      <el-popover placement="bottom-end" :width="380" trigger="click" @show="loadNotifications">
        <template #reference>
          <span class="bell">
            <el-badge :value="unreadCount" :hidden="unreadCount === 0" :max="99">
              <el-icon size="18"><Bell /></el-icon>
            </el-badge>
          </span>
        </template>
        <div class="notif-head">
          <b>{{ t('common.notifications') }}</b>
          <el-button text size="small" @click="markAllRead">{{ t('common.markAllRead') }}</el-button>
        </div>
        <div v-if="notifications.length === 0" class="notif-empty">{{ t('common.noNotifications') }}</div>
        <div v-for="n in notifications" :key="n.id" class="notif-item" :class="{ unread: !n.isRead }"
             @click="openNotification(n)">
          <div class="notif-title">{{ n.title }}</div>
          <div class="notif-content">{{ n.content }}</div>
          <div class="notif-time">{{ fmtNotif(n.createdAt) }}</div>
        </div>
      </el-popover>
      <el-select v-if="userStore.userInfo?.isPlatformAdmin"
                 v-model="platformTenantId" size="small" class="tenant-select" style="width: 170px; margin-right: 10px"
                 :placeholder="t('layout.pickTenant')" @change="onTenantSwitch">
        <el-option v-for="tOpt in tenantOptions" :key="tOpt.id" :value="String(tOpt.id)"
                   :label="tOpt.name || tOpt.tenantKey" />
      </el-select>
      <el-dropdown @command="onCommand">
        <span class="user-chip">
          <el-icon><User /></el-icon>
          {{ userName }}
          <el-icon><ArrowDown /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="security">{{ t('common.security') }}</el-dropdown-item>
            <el-dropdown-item command="logout">{{ t('common.logout') }}</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </el-header>
    <el-container class="body">
      <el-aside width="200px" class="aside" :class="{ open: mobileMenu }">
        <el-menu :default-active="$route.path" router :default-openeds="openedGroups">
          <el-sub-menu index="g-work">
            <template #title><el-icon><ChatDotRound /></el-icon>{{ t('layout.workbench') }}</template>
            <el-menu-item index="/agents">{{ t('menu.agents') }}</el-menu-item>
            <el-menu-item index="/square">{{ t('menu.square') }}</el-menu-item>
            <el-menu-item index="/templates">{{ t('menu.templates') }}</el-menu-item>
            <el-menu-item v-if="can('agent:edit')" index="/approvals">{{ t('menu.approvals') }}</el-menu-item>
          </el-sub-menu>
          <el-sub-menu index="g-asset">
            <template #title><el-icon><FolderOpened /></el-icon>{{ t('layout.assets') }}</template>
            <el-menu-item index="/knowledge">{{ t('menu.knowledge') }}</el-menu-item>
            <el-menu-item index="/memories">{{ t('menu.memories') }}</el-menu-item>
            <el-menu-item index="/cases">{{ t('menu.cases') }}</el-menu-item>
            <el-menu-item index="/skills">{{ t('menu.skills') }}</el-menu-item>
            <el-menu-item index="/tools">{{ t('menu.tools') }}</el-menu-item>
            <el-menu-item index="/mcp-servers">{{ t('menu.mcp') }}</el-menu-item>
            <el-menu-item v-if="can('prompt:manage')" index="/prompt-templates">{{ t('menu.prompts') }}</el-menu-item>
          </el-sub-menu>
          <el-sub-menu index="g-ops">
            <template #title><el-icon><DataAnalysis /></el-icon>{{ t('layout.evalOps') }}</template>
            <el-menu-item index="/metrics">{{ t('menu.metrics') }}</el-menu-item>
            <el-menu-item index="/observability">{{ t('menu.observability') }}</el-menu-item>
            <el-menu-item index="/observability/gray">{{ t('menu.grayCompare') }}</el-menu-item>
            <el-menu-item v-if="can('eval:manage')" index="/eval">{{ t('menu.eval') }}</el-menu-item>
            <el-menu-item v-if="can('audit:read')" index="/audit">{{ t('menu.audit') }}</el-menu-item>
            <el-menu-item v-if="userStore.userInfo?.isPlatformAdmin" index="/alert-ack">{{ t('menu.alertAck') }}</el-menu-item>
          </el-sub-menu>
          <el-sub-menu index="g-admin">
            <template #title><el-icon><Setting /></el-icon>{{ t('layout.management') }}</template>
            <el-menu-item index="/provider">{{ t('menu.provider') }}</el-menu-item>
            <el-menu-item v-if="can('billing:read')" index="/settings/usage">{{ t('menu.usage') }}</el-menu-item>
            <el-menu-item v-if="can('billing:read')" index="/billing">{{ t('menu.billing') }}</el-menu-item>
            <el-menu-item v-if="can('tenant:manage')" index="/gateway">{{ t('menu.gateway') }}</el-menu-item>
            <el-menu-item v-if="can('user:manage')" index="/admin/users">{{ t('menu.users') }}</el-menu-item>
            <el-menu-item v-if="can('tenant:manage')" index="/admin/tenant">{{ t('menu.tenant') }}</el-menu-item>
            <el-menu-item v-if="userStore.userInfo?.isPlatformAdmin" index="/admin/platform">{{ t('menu.platform') }}</el-menu-item>
            <el-menu-item v-if="userStore.userInfo?.isPlatformAdmin" index="/admin/settings">{{ t('menu.settings') }}</el-menu-item>
            <el-menu-item v-if="userStore.userInfo?.isPlatformAdmin" index="/admin/infrastructure">{{ t('menu.infra') }}</el-menu-item>
            <el-menu-item v-if="userStore.userInfo?.isPlatformAdmin" index="/admin/i18n">{{ t('menu.i18n') }}</el-menu-item>
          </el-sub-menu>
        </el-menu>
      </el-aside>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>

    <!-- 移动端侧栏遮罩：点空白处收起抽屉 -->
    <div v-if="isMobile && mobileMenu" class="aside-mask" @click="mobileMenu = false" />

    <el-dialog v-model="securityVisible" :title="t('layout.security')" width="560px">
      <div class="sec-row">
        <span class="sec-label">{{ t('layout.mfa') }}</span>
        <el-tag :type="mfaEnabled ? 'success' : 'info'">{{ mfaEnabled ? t('layout.enabled') : t('layout.disabled') }}</el-tag>
      </div>
      <template v-if="!mfaEnabled">
        <div v-if="!mfaSetup" class="hint">{{ t('layout.mfaHint') }}</div>
        <div v-else class="setup-box">
          <div class="step">{{ t('layout.step1') }}</div>
          <div class="secret-row">
            <code class="secret">{{ mfaSetup.secret }}</code>
            <el-button size="small" @click="copyText(mfaSetup.secret)">{{ t('layout.copy') }}</el-button>
          </div>
          <div class="hint">otpauth：{{ mfaSetup.otpauthUri }}</div>
          <div class="step mt8">{{ t('layout.step2') }}</div>
          <el-input v-model="mfaCode" :placeholder="t('layout.codePh')" maxlength="6" class="mt8" />
          <el-button type="primary" class="mt8" :loading="mfaLoading" @click="enableMfa">{{ t('layout.enableMfa') }}</el-button>
        </div>
        <el-button v-if="!mfaSetup" type="primary" @click="setupMfa">{{ t('layout.setupMfa') }}</el-button>
      </template>
      <template v-else>
        <div class="hint">{{ t('layout.recoveryLeft') }}{{ recoveryLeft }}{{ t('layout.recoveryLeftSuffix') }}</div>
        <el-input v-model="mfaCode" :placeholder="t('layout.codeOrRecoveryPh')" class="mt8" />
        <el-button type="danger" class="mt8" :loading="mfaLoading" @click="disableMfa">{{ t('layout.disableMfa') }}</el-button>
      </template>
      <el-divider content-position="left">{{ t('layout.changePassword') }}</el-divider>
      <el-form label-width="90px">
        <el-form-item :label="t('layout.oldPassword')">
          <el-input v-model="pwdForm.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item :label="t('layout.newPassword')">
          <el-input v-model="pwdForm.newPassword" type="password" show-password :placeholder="t('layout.newPasswordPh')" />
        </el-form-item>
        <el-form-item :label="t('layout.confirmPassword')">
          <el-input v-model="pwdForm.confirm" type="password" show-password />
        </el-form-item>
        <el-button :loading="pwdSaving" @click="changePassword">{{ t('layout.changeBtn') }}</el-button>
        <span class="hint" style="margin-left: 8px">{{ t('layout.changeHint') }}</span>
      </el-form>
      <el-divider content-position="left">{{ t('layout.dataAccount') }}</el-divider>
      <div class="account-row">
        <el-button size="small" @click="exportData">{{ t('layout.exportData') }}</el-button>
      </div>
      <div class="danger-zone">
        <div class="danger-title">{{ t('layout.deleteTitle') }}</div>
        <div class="hint">{{ t('layout.deleteDesc') }}</div>
        <el-input v-model="delForm.password" type="password" show-password :placeholder="t('layout.currentPassword')" class="mt8" />
        <el-input v-model="delForm.confirmText" :placeholder="t('layout.confirmDeletePh')" class="mt8" />
        <el-button type="danger" class="mt8" :loading="deleting" @click="deleteAccount">{{ t('layout.deleteBtn') }}</el-button>
      </div>
      <div v-if="recoveryCodes.length" class="recovery-box">
        <div class="recovery-title">{{ t('layout.recoveryTitle') }}</div>
        <div class="recovery-grid">
          <code v-for="c in recoveryCodes" :key="c">{{ c }}</code>
        </div>
        <el-button size="small" @click="copyText(recoveryCodes.join('\n'))">{{ t('layout.copyAll') }}</el-button>
      </div>
    </el-dialog>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'
import { useIsMobile } from '../composables/useMediaQuery'
import http from '../api/http'
import { setLocale, type Locale } from '../i18n'
import { User, ArrowDown, ChatDotRound, Collection, Connection, DataLine,
  UserFilled, Setting, OfficeBuilding, Document, MagicStick, SetUp, DocumentCopy, Grid, Star, Link, Money,
  Checked, Bell, Moon, Sunny, DataAnalysis, TrendCharts, View, FolderOpened, Menu } from '@element-plus/icons-vue'

const router = useRouter()
const userStore = useUserStore()
const { t, locale } = useI18n()
const currentLocale = computed({
  get: () => locale.value,
  set: (v: Locale) => {
    setLocale(v)
    // 持久化用户语言偏好，供站内通知/邮件模板选择语言
    void http.put('/auth/locale', { locale: v }).catch(() => {})
  }
})
const userName = computed(() => userStore.userInfo?.displayName || t('common.user'))

// 侧栏二级菜单：默认只展开当前路由所在分组（切换路由时 el-sub-menu 自动跟随激活项）
const route = useRoute()

// 移动端：侧栏改为抽屉，切换路由后自动收起
const isMobile = useIsMobile()
const mobileMenu = ref(false)
watch(() => route.path, () => { mobileMenu.value = false })

// 平台管理员：选择要查看的租户（选定后全局 X-Tenant-Id，刷新重载数据）
const platformTenantId = ref(localStorage.getItem('platformTenantId') || '')
const tenantOptions = ref<any[]>([])
onMounted(async () => {
  if (!userStore.userInfo?.isPlatformAdmin) return
  try {
    tenantOptions.value = await http.get('/platform/tenants') as any
  } catch { /* 忽略：未授权时选择器隐藏 */ }
})
function onTenantSwitch(id: string) {
  localStorage.setItem('platformTenantId', id)
  router.go(0)
}
const openedGroups = ref<string[]>([groupOf(route.path)])
function groupOf(path: string): string {
  if (/^\/(agents|square|templates|approvals)\b/.test(path)) return 'g-work'
  if (/^\/(knowledge|memories|cases|skills|tools|mcp-servers|prompt-templates)\b/.test(path)) return 'g-asset'
  if (/^\/(eval|metrics|observability|audit|alert-ack)\b/.test(path) || path.startsWith('/observability/gray')) return 'g-ops'
  if (/^\/(provider|billing|settings|admin)\b/.test(path)) return 'g-admin'
  return 'g-work'
}
const notifications = ref<any[]>([])
const unreadCount = ref(0)
const dark = ref(localStorage.getItem('theme') === 'dark')
const searchKeyword = ref('')
const searchVisible = ref(false)
const searchLoading = ref(false)
const searchResults = ref<any[]>([])
let searchTimer: ReturnType<typeof setTimeout> | undefined

function onSearchFocus() {
  searchVisible.value = true
  if (searchKeyword.value.trim().length >= 2) scheduleSearch()
}

function hideSearchSoon() {
  setTimeout(() => { searchVisible.value = false }, 150)
}

function scheduleSearch() {
  if (searchTimer) clearTimeout(searchTimer)
  if (searchKeyword.value.trim().length < 2) {
    searchResults.value = []
    return
  }
  searchTimer = setTimeout(runSearch, 250)
}

async function runSearch() {
  const kw = searchKeyword.value.trim()
  if (kw.length < 2) return
  searchLoading.value = true
  try {
    const data = await http.get('/search', { params: { q: kw } }) as any
    const meta: Record<string, { group: string; tagType: string }> = {
      AGENT: { group: t('layout.searchAgent'), tagType: 'success' },
      KB: { group: t('layout.searchKb'), tagType: 'primary' },
      CASE: { group: t('layout.searchCase'), tagType: 'warning' },
      TOOL: { group: t('layout.searchTool'), tagType: 'info' }
    }
    searchResults.value = (data?.list || []).map((r: any) => {
      const m = meta[r.type] || { group: r.type, tagType: 'info' }
      return {
        type: r.type,
        id: r.id == null ? '' : String(r.id),
        group: m.group,
        tagType: m.tagType,
        name: r.name,
        desc: r.desc || '',
        // 后端已 HTML 转义，只保留 <mark> 高亮，避免把用户内容直接当 HTML 渲染。
        nameHtml: r.nameHtml || r.name,
        descHtml: r.descHtml || r.desc || '',
        path: r.path
      }
    })
    searchVisible.value = true
  } finally {
    searchLoading.value = false
  }
}

function goSearch(row: any) {
  const query = searchKeyword.value.trim()
  if (row.type && row.id != null && query) {
    // 点击反馈是排序增强，失败不阻断跳转。
    void http.post('/search/feedback', { type: row.type, id: row.id, query }).catch(() => {})
  }
  searchVisible.value = false
  searchKeyword.value = ''
  if (row.path) router.push(row.path)
}
let notifTimer: any = null

function applyTheme() {
  document.documentElement.classList.toggle('dark', dark.value)
}
function toggleDark() {
  dark.value = !dark.value
  localStorage.setItem('theme', dark.value ? 'dark' : 'light')
  applyTheme()
}

onMounted(() => {
  applyTheme()
  if (userStore.accessToken) fetchUnread()
  notifTimer = setInterval(() => { if (userStore.accessToken) fetchUnread() }, 30000)
})
onUnmounted(() => { if (notifTimer) clearInterval(notifTimer) })

async function fetchUnread() {
  try {
    const data = await http.get('/notifications/unread-count') as any
    unreadCount.value = data?.count || 0
  } catch { /* 通知失败不打扰 */ }
}

async function loadNotifications() {
  const data = await http.get('/notifications', { params: { pageSize: 10 } }) as any
  notifications.value = data?.list || []
  unreadCount.value = data?.unread || 0
}

async function openNotification(n: any) {
  if (!n.isRead) {
    await http.post(`/notifications/${n.id}/read`)
    n.isRead = 1
    await fetchUnread()
  }
  if (n.link && n.link.startsWith('/')) router.push(n.link)
}

async function markAllRead() {
  await http.post('/notifications/read-all')
  notifications.value.forEach(n => { n.isRead = 1 })
  unreadCount.value = 0
}

function fmtNotif(v?: string) {
  return v ? String(v).replace('T', ' ').slice(0, 16) : ''
}

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
const deleting = ref(false)
const delForm = ref({ password: '', confirmText: '' })
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
  if (!mfaCode.value.trim()) { ElMessage.warning(t('layout.codeRequired')); return }
  mfaLoading.value = true
  try {
    const res = await http.post('/auth/mfa/enable', { code: mfaCode.value.trim() }) as any
    recoveryCodes.value = res.recoveryCodes || []
    mfaEnabled.value = true
    recoveryLeft.value = recoveryCodes.value.length
    mfaSetup.value = null
    mfaCode.value = ''
    ElMessage.success(t('layout.mfaEnabled'))
  } finally {
    mfaLoading.value = false
  }
}

async function disableMfa() {
  if (!mfaCode.value.trim()) { ElMessage.warning(t('layout.codeOrRecoveryRequired')); return }
  mfaLoading.value = true
  try {
    await http.post('/auth/mfa/disable', { code: mfaCode.value.trim() })
    mfaEnabled.value = false
    recoveryCodes.value = []
    recoveryLeft.value = 0
    mfaCode.value = ''
    ElMessage.success(t('layout.mfaDisabled'))
  } finally {
    mfaLoading.value = false
  }
}

async function changePassword() {
  if (!pwdForm.value.oldPassword || pwdForm.value.newPassword.length < 8) {
    ElMessage.warning(t('layout.pwdInvalid')); return
  }
  if (pwdForm.value.newPassword !== pwdForm.value.confirm) {
    ElMessage.warning(t('layout.pwdMismatch')); return
  }
  pwdSaving.value = true
  try {
    await http.post('/auth/change-password', {
      oldPassword: pwdForm.value.oldPassword, newPassword: pwdForm.value.newPassword
    })
    ElMessage.success(t('layout.pwdChanged'))
    securityVisible.value = false
    userStore.clear()
    router.push('/login')
  } finally {
    pwdSaving.value = false
  }
}

async function exportData() {
  const blob = await http.get('/auth/me/export', { responseType: 'blob' }) as any
  const url = URL.createObjectURL(new Blob([blob], { type: 'application/json' }))
  const a = document.createElement('a')
  a.href = url
  a.download = 'my-data.json'
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success(t('layout.exported'))
}

async function deleteAccount() {
  if (!delForm.value.password) { ElMessage.warning(t('layout.passwordRequired')); return }
  if (delForm.value.confirmText !== t('layout.confirmDeleteWord')) { ElMessage.warning(t('layout.deleteConfirmRequired')); return }
  deleting.value = true
  try {
    await http.delete('/auth/me', { data: { password: delForm.value.password, confirmText: '注销' } })
    ElMessage.success(t('layout.accountDeleted'))
    userStore.clear()
    router.push('/login')
  } finally {
    deleting.value = false
  }
}

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(t('layout.copied'))
  } catch {
    ElMessage.warning(t('layout.copyFail'))
  }
}
</script>

<style scoped>
.layout {
  height: 100vh;
  height: 100dvh;
}
.header {
  display: flex;
  align-items: center;
  border-bottom: 1px solid #e4e7ed;
  background: #fff;
}
.global-search {
  position: relative;
  width: min(320px, 30vw);
  margin-left: 18px;
}
.gs-panel {
  position: absolute;
  z-index: 3000;
  top: calc(100% + 8px);
  left: 0;
  width: max(100%, 360px);
  max-height: 420px;
  overflow-y: auto;
  padding: 6px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.12);
}
.gs-empty {
  padding: 18px 10px;
  color: #909399;
  font-size: 13px;
  text-align: center;
}
.gs-item {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  padding: 8px;
  border-radius: 6px;
  cursor: pointer;
}
.gs-item:hover { background: #f5f7fa; }
.gs-name {
  min-width: 0;
  overflow: hidden;
  color: #303133;
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.gs-desc {
  grid-column: 2;
  min-width: 0;
  overflow: hidden;
  color: #909399;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.gs-name :deep(mark),
.gs-desc :deep(mark) {
  padding: 0 1px;
  border-radius: 2px;
  color: #8a5300;
  background: #ffeca8;
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
  height: calc(100dvh - 60px);
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
.account-row { margin-bottom: 8px; }
.danger-zone { border: 1px solid #fbc4c4; background: #fef0f0; border-radius: 6px; padding: 10px; margin-bottom: 12px; }
.danger-title { color: #f56c6c; font-weight: 600; margin-bottom: 4px; }
.recovery-box { margin-top: 14px; border: 1px solid #e6a23c; background: #fdf6ec; border-radius: 6px; padding: 10px; }
.recovery-title { font-weight: 600; color: #e6a23c; margin-bottom: 6px; }
.recovery-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 4px; margin-bottom: 8px; }
.recovery-grid code { font-family: monospace; font-size: 13px; }
.bell { display: inline-flex; align-items: center; margin-right: 16px; cursor: pointer; color: #606266; }
.notif-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.notif-empty { color: #909399; font-size: 13px; padding: 18px 0; text-align: center; }
.notif-item { padding: 8px 4px; border-bottom: 1px solid #f2f3f5; cursor: pointer; }
.notif-item.unread { background: #f0f7ff; }
.notif-title { font-size: 13px; font-weight: 600; color: #303133; }
.notif-content { font-size: 12px; color: #606266; margin-top: 2px; word-break: break-word; }
.notif-time { font-size: 11px; color: #a8abb2; margin-top: 2px; }

/* ===== 移动端（H5）：顶栏精简 + 侧栏改抽屉 ===== */
.hamburger {
  margin-right: 2px;
}
.aside-mask {
  position: fixed;
  inset: 0;
  z-index: 2000;
  background: rgba(0, 0, 0, 0.45);
}

@media (max-width: 768px) {
  .header {
    height: 52px;
    padding: 0 8px;
    gap: 4px;
  }
  .brand {
    font-size: 15px;
    max-width: 38vw;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  /* 屏幕太窄：全局搜索、语言、租户选择挪到桌面端使用 */
  .global-search,
  .lang-select,
  .tenant-select,
  .theme-btn {
    display: none !important;
  }
  .spacer { flex: 1; }
  .bell { margin-right: 10px; }
  .user-chip {
    font-size: 12px;
    max-width: 84px;
    overflow: hidden;
  }
  .user-chip > * { flex-shrink: 0; }

  .body {
    height: calc(100vh - 52px);
    height: calc(100dvh - 52px);
  }
  /* 侧栏变成可滑出的抽屉，不再固定占 200px 宽 */
  .aside {
    position: fixed;
    top: 52px;
    bottom: 0;
    left: 0;
    width: min(78vw, 260px) !important;
    z-index: 2001;
    transform: translateX(-100%);
    transition: transform 0.22s ease;
    box-shadow: 2px 0 10px rgba(0, 0, 0, 0.08);
  }
  .aside.open { transform: none; }
  .main {
    padding: 10px;
  }
  :deep(.el-menu-item),
  :deep(.el-sub-menu__title) {
    height: 46px;
    line-height: 46px;
  }
  .recovery-grid { grid-template-columns: 1fr; }
}
</style>
