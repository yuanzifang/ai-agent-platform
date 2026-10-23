<template>
  <div class="page">
    <div class="toolbar">
      <span class="page-title">{{ t('page.users') }}</span>
      <el-button type="primary" @click="inviteVisible = true">{{ t('userAdmin.invite') }}</el-button>
    </div>

    <el-card shadow="never" class="mb">
      <template #header>{{ t('userAdmin.members') }}{{ members.length }}）</template>
      <el-table :data="members" size="small">
        <el-table-column prop="username" :label="t('userAdmin.username')" width="140" />
        <el-table-column prop="displayName" :label="t('userAdmin.displayName')" width="140" />
        <el-table-column :label="t('userAdmin.role')" min-width="220">
          <template #default="{ row }">
            <el-select v-if="row.id === editingId" v-model="editingRoles" multiple size="small" style="width: 100%">
              <el-option v-for="r in editableRoles" :key="r" :value="r" :label="r" />
            </el-select>
            <template v-else>
              <el-tag v-for="r in row.roles" :key="r" size="small" class="mr4">{{ r }}</el-tag>
            </template>
          </template>
        </el-table-column>
        <el-table-column prop="status" :label="t('userAdmin.status')" width="80" />
        <el-table-column :label="t('userAdmin.lastLogin')" width="150">
          <template #default="{ row }">{{ fmt(row.lastLoginAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('userAdmin.actions')" width="210">
          <template #default="{ row }">
            <template v-if="row.id === editingId">
              <el-button size="small" type="primary" @click="saveRoles(row)">{{ t('userAdmin.save') }}</el-button>
              <el-button size="small" @click="editingId = null">{{ t('userAdmin.cancel') }}</el-button>
            </template>
            <template v-else>
              <el-button size="small" @click="startEdit(row)">{{ t('userAdmin.adjustRole') }}</el-button>
              <el-button size="small" text type="danger" @click="removeMember(row)">{{ t('userAdmin.remove') }}</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>{{ t('userAdmin.invitations') }}</template>
      <el-table :data="invitations" size="small">
        <el-table-column prop="email" :label="t('userAdmin.email')" min-width="180" />
        <el-table-column prop="role" :label="t('userAdmin.preRole')" width="110" />
        <el-table-column :label="t('userAdmin.status')" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACCEPTED' ? 'success' : row.status === 'EXPIRED' ? 'info' : 'warning'">
              {{ statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('userAdmin.expires')" width="150">
          <template #default="{ row }">{{ fmt(row.expiresAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('userAdmin.actions')" width="120">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PENDING'" size="small" text type="primary" @click="copyLink(row)">{{ t('userAdmin.copyLink') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="inviteVisible" :title="t('userAdmin.inviteTitle')" width="460px">
      <el-form label-width="90px">
        <el-form-item :label="t('userAdmin.emailLabel')">
          <el-input v-model="inviteForm.email" :placeholder="t('userAdmin.emailPh')" />
        </el-form-item>
        <el-form-item :label="t('userAdmin.role')">
          <el-select v-model="inviteForm.role" style="width: 100%">
            <el-option v-for="r in editableRoles" :key="r" :value="r" :label="r" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="inviteVisible = false">{{ t('userAdmin.cancel') }}</el-button>
        <el-button type="primary" @click="createInvite">{{ t('userAdmin.createInvite') }}</el-button>
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
  ElMessage.success(t('userAdmin.rolesUpdated'))
  editingId.value = null
  await load()
}

async function removeMember(row: any) {
  await ElMessageBox.confirm(
    `${t('userAdmin.removeConfirmPrefix')}${row.displayName || row.username}${t('userAdmin.removeConfirmMid')}`,
    t('userAdmin.removeTitle'), { type: 'warning', confirmButtonText: t('userAdmin.remove'), cancelButtonText: t('userAdmin.cancel') })
  await http.delete(`/users/${row.id}`)
  ElMessage.success(t('userAdmin.removed'))
  await load()
}

async function createInvite() {
  const res = await http.post('/auth/invitations', inviteForm) as any
  const link = `${location.origin}/login?invite=${res.token}`
  navigator.clipboard.writeText(link).catch(() => {})
  ElMessage.success(`${t('userAdmin.inviteCreated')}${link}`)
  inviteVisible.value = false
  await load()
}

function copyLink(row: any) {
  const link = `${location.origin}/login?invite=${row.token}`
  navigator.clipboard.writeText(link).catch(() => {})
  ElMessage.success(t('userAdmin.linkCopied'))
}

function fmt(t?: string) { return t ? String(t).replace('T', ' ').slice(0, 16) : '-' }
function statusText(s: string) {
  return s === 'ACCEPTED' ? t('userAdmin.accepted') : s === 'EXPIRED' ? t('userAdmin.expiredStatus') : t('userAdmin.pending')
}
</script>

<style scoped>
.page { max-width: 1100px; margin: 0 auto; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.page-title { font-size: 16px; font-weight: 600; }
.mb { margin-bottom: 14px; }
.mr4 { margin-right: 4px; }
</style>