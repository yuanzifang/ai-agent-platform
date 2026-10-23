<template>
  <div class="page">
    <div class="toolbar">
      <span class="page-title">{{ t('page.users') }}</span>
      <el-button type="primary" @click="inviteVisible = true">邀请成员</el-button>
    </div>

    <el-card shadow="never" class="mb">
      <template #header>成员（{{ members.length }}）</template>
      <el-table :data="members" size="small">
        <el-table-column prop="username" label="用户名" width="140" />
        <el-table-column prop="displayName" label="显示名" width="140" />
        <el-table-column label="角色" min-width="220">
          <template #default="{ row }">
            <el-select v-if="row.id === editingId" v-model="editingRoles" multiple size="small" style="width: 100%">
              <el-option v-for="r in editableRoles" :key="r" :value="r" :label="r" />
            </el-select>
            <template v-else>
              <el-tag v-for="r in row.roles" :key="r" size="small" class="mr4">{{ r }}</el-tag>
            </template>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="80" />
        <el-table-column label="最近登录" width="150">
          <template #default="{ row }">{{ fmt(row.lastLoginAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="210">
          <template #default="{ row }">
            <template v-if="row.id === editingId">
              <el-button size="small" type="primary" @click="saveRoles(row)">保存</el-button>
              <el-button size="small" @click="editingId = null">取消</el-button>
            </template>
            <template v-else>
              <el-button size="small" @click="startEdit(row)">调整角色</el-button>
              <el-button size="small" text type="danger" @click="removeMember(row)">移除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>邀请记录</template>
      <el-table :data="invitations" size="small">
        <el-table-column prop="email" label="邮箱" min-width="180" />
        <el-table-column prop="role" label="预分配角色" width="110" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACCEPTED' ? 'success' : row.status === 'EXPIRED' ? 'info' : 'warning'">
              {{ statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="过期时间" width="150">
          <template #default="{ row }">{{ fmt(row.expiresAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PENDING'" size="small" text type="primary" @click="copyLink(row)">复制链接</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="inviteVisible" title="邀请成员" width="460px">
      <el-form label-width="90px">
        <el-form-item label="邮箱/标识">
          <el-input v-model="inviteForm.email" placeholder="对方邮箱（记录用，暂不发邮件）" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="inviteForm.role" style="width: 100%">
            <el-option v-for="r in editableRoles" :key="r" :value="r" :label="r" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="inviteVisible = false">取消</el-button>
        <el-button type="primary" @click="createInvite">生成邀请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

const { t } = useI18n()

const members = ref<any[]>([])
const invitations = ref<any[]>([])
const editingId = ref<number | null>(null)
const editingRoles = ref<string[]>([])
const inviteVisible = ref(false)
const inviteForm = reactive({ email: '', role: 'MEMBER' })
const editableRoles = ['ADMIN', 'EDITOR', 'MEMBER', 'RESTRICTED']

onMounted(load)

async function load() {
  members.value = await http.get('/users') as any
  invitations.value = await http.get('/auth/invitations') as any
}

function startEdit(row: any) {
  editingId.value = row.id
  editingRoles.value = [...(row.roles || []).filter((r: string) => editableRoles.includes(r))]
}

async function saveRoles(row: any) {
  await http.put(`/users/${row.id}/roles`, { roles: editingRoles.value })
  ElMessage.success('角色已更新')
  editingId.value = null
  await load()
}

async function removeMember(row: any) {
  await ElMessageBox.confirm(
    `确认移除成员「${row.displayName || row.username}」？移除后该账号将被禁用并退出登录。`,
    '移除成员', { type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消' })
  await http.delete(`/users/${row.id}`)
  ElMessage.success('已移除')
  await load()
}

async function createInvite() {
  const res = await http.post('/auth/invitations', inviteForm) as any
  const link = `${location.origin}/login?invite=${res.token}`
  navigator.clipboard.writeText(link).catch(() => {})
  ElMessage.success(`邀请已生成，链接已复制：${link}`)
  inviteVisible.value = false
  await load()
}

function copyLink(row: any) {
  const link = `${location.origin}/login?invite=${row.token}`
  navigator.clipboard.writeText(link).catch(() => {})
  ElMessage.success('邀请链接已复制')
}

function fmt(t?: string) { return t ? String(t).replace('T', ' ').slice(0, 16) : '-' }
function statusText(s: string) {
  return s === 'ACCEPTED' ? '已接受' : s === 'EXPIRED' ? '已过期' : '待接受'
}
</script>

<style scoped>
.page { max-width: 1100px; margin: 0 auto; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.page-title { font-size: 16px; font-weight: 600; }
.mb { margin-bottom: 14px; }
.mr4 { margin-right: 4px; }
</style>