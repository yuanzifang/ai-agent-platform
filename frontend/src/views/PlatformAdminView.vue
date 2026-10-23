<template>
  <div class="page">
    <div class="toolbar">
      <span class="page-title">{{ t('page.platform') }}</span>
      <el-button type="primary" @click="createVisible = true">开通租户</el-button>
    </div>

    <el-card shadow="never">
      <el-table :data="tenants" size="small">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="tenantKey" label="租户标识" width="140" />
        <el-table-column prop="name" label="名称" width="180" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : row.status === 'SUSPENDED' ? 'danger' : 'info'">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="members" label="成员数" width="80" />
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="160">
          <template #default="{ row }">
            <el-button v-if="row.status !== 'ACTIVE'" size="small" type="success" plain @click="setStatus(row, 'ACTIVE')">启用</el-button>
            <el-button v-if="row.status === 'ACTIVE'" size="small" type="danger" plain @click="setStatus(row, 'SUSPENDED')">停用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="createVisible" title="开通租户" width="520px">
      <el-form label-width="110px">
        <el-form-item label="租户标识" required>
          <el-input v-model="form.tenantKey" placeholder="如 acme（登录时填写）" />
        </el-form-item>
        <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="所有者账号" required>
          <el-input v-model="form.ownerUsername" placeholder="如 acme-owner" />
        </el-form-item>
        <el-form-item label="初始密码" required>
          <el-input v-model="form.ownerPassword" type="password" show-password placeholder="至少 8 位" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="create">开通</el-button>
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

const tenants = ref<any[]>([])
const createVisible = ref(false)
const creating = ref(false)
const form = reactive({ tenantKey: '', name: '', ownerUsername: '', ownerPassword: '' })

onMounted(load)

async function load() {
  tenants.value = await http.get('/platform/tenants') as any
}

async function create() {
  for (const k of ['tenantKey', 'name', 'ownerUsername', 'ownerPassword'] as const) {
    if (!form[k].trim()) { ElMessage.warning('请完整填写'); return }
  }
  creating.value = true
  try {
    await http.post('/platform/tenants', { ...form })
    ElMessage.success('租户已开通（含 OWNER 账号与默认配额）')
    createVisible.value = false
    Object.assign(form, { tenantKey: '', name: '', ownerUsername: '', ownerPassword: '' })
    await load()
  } finally {
    creating.value = false
  }
}

async function setStatus(row: any, status: string) {
  await ElMessageBox.confirm(`确认${status === 'ACTIVE' ? '启用' : '停用'}租户「${row.name}」？`, '确认', { type: 'warning' })
  await http.patch(`/platform/tenants/${row.id}/status`, { status })
  ElMessage.success('状态已更新')
  await load()
}

function fmt(t?: string) { return t ? String(t).replace('T', ' ').slice(0, 16) : '-' }
</script>

<style scoped>
.page { max-width: 1000px; margin: 0 auto; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.page-title { font-size: 16px; font-weight: 600; }
</style>