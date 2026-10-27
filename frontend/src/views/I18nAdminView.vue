<template>
  <div class="page">
    <h2>{{ t('i18nAdmin.title') }}</h2>
    <p class="desc">{{ t('i18nAdmin.desc') }}</p>

    <div class="toolbar">
      <el-select v-model="lang" style="width: 140px" @change="load">
        <el-option v-for="l in locales" :key="l" :label="l" :value="l" />
      </el-select>
      <el-input v-model="keyword" :placeholder="t('i18nAdmin.searchPh')" style="width: 240px"
                clearable @keyup.enter="load" @clear="load" />
      <el-button @click="load">{{ t('app.refresh') }}</el-button>
      <el-button type="primary" @click="openEdit(null)">{{ t('i18nAdmin.add') }}</el-button>
      <el-upload :show-file-list="false" :auto-upload="false" accept=".json,.csv,.txt"
                 :on-change="onImportFile">
        <el-button>{{ t('i18nAdmin.importBtn') }}</el-button>
      </el-upload>
      <el-button @click="doExport('csv')">CSV</el-button>
      <el-button @click="doExport('json')">JSON</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" border stripe>
      <el-table-column prop="lang" label="Lang" width="100" />
      <el-table-column prop="messageKey" label="Key" min-width="220" show-overflow-tooltip />
      <el-table-column prop="content" label="Value" min-width="240" show-overflow-tooltip />
      <el-table-column prop="description" :label="t('i18nAdmin.description')" min-width="160" show-overflow-tooltip />
      <el-table-column prop="updatedAt" :label="t('i18nAdmin.updatedAt')" width="160">
        <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column :label="t('agentList.edit')" width="150">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openEdit(row)">{{ t('agentList.edit') }}</el-button>
          <el-button size="small" link type="danger" @click="remove(row)">{{ t('agentList.delete') }}</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="size" :total="total"
                   layout="total, prev, pager, next" @current-change="load" style="margin-top: 12px" />

    <el-dialog v-model="editVisible" :title="editing?.id ? t('i18nAdmin.editTitle') : t('i18nAdmin.add')" width="520px">
      <el-form label-width="90px">
        <el-form-item label="Lang">
          <el-input v-model="editing.lang" :disabled="!!editing.id" placeholder="zh-CN / en-US / ja-JP" />
        </el-form-item>
        <el-form-item label="Key">
          <el-input v-model="editing.messageKey" :disabled="!!editing.id" placeholder="menu.agents" />
        </el-form-item>
        <el-form-item :label="t('i18nAdmin.value')">
          <el-input v-model="editing.content" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item :label="t('i18nAdmin.description')">
          <el-input v-model="editing.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">{{ t('agentList.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('i18nAdmin.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile } from 'element-plus'
import http from '../api/http'
import { remoteLocales, applyRemoteMessages } from '../i18n'

const { t } = useI18n()
const lang = ref('zh-CN')
const keyword = ref('')
const locales = ref<string[]>(['zh-CN', 'en-US'])
const rows = ref<any[]>([])
const loading = ref(false)
const page = ref(1)
const size = 20
const total = ref(0)
const editVisible = ref(false)
const saving = ref(false)
const editing = reactive<any>({ id: null, lang: '', messageKey: '', content: '', description: '' })

async function load() {
  loading.value = true
  try {
    const res: any = await http.get('/i18n/admin/messages', {
      params: { lang: lang.value, keyword: keyword.value || undefined, page: page.value, size }
    })
    rows.value = res.records || []
    total.value = Number(res.total || 0)
  } finally {
    loading.value = false
  }
}

function openEdit(row: any) {
  Object.assign(editing, row ?? { id: null, lang: lang.value, messageKey: '', content: '', description: '' })
  editVisible.value = true
}

async function save() {
  saving.value = true
  try {
    await http.post('/i18n/admin/messages', {
      lang: editing.lang, key: editing.messageKey,
      content: editing.content, description: editing.description || null
    })
    ElMessage.success(t('i18nAdmin.saved'))
    editVisible.value = false
    await load()
    await applyRemoteMessages(editing.lang)
  } finally {
    saving.value = false
  }
}

async function remove(row: any) {
  await ElMessageBox.confirm(`${row.lang} · ${row.messageKey}`, t('agentList.deleteTitle'))
  await http.delete(`/i18n/admin/messages/${row.id}`)
  ElMessage.success(t('agentList.deleted'))
  await load()
}

async function onImportFile(file: UploadFile) {
  const text = await (file.raw as File).text()
  const format = (file.name || '').toLowerCase().endsWith('.csv') ? 'csv' : 'json'
  const res: any = await http.post('/i18n/admin/import', { lang: lang.value, format, content: text })
  ElMessage.success(t('i18nAdmin.imported', res as any))
  await load()
  await applyRemoteMessages(lang.value)
}

async function doExport(format: string) {
  const res = await fetch(`/api/v1/i18n/admin/export?lang=${encodeURIComponent(lang.value)}&format=${format}`,
    { headers: { Authorization: `Bearer ${localStorage.getItem('accessToken') || ''}` } })
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `i18n-${lang.value}.${format}`
  a.click()
  URL.revokeObjectURL(url)
}

function formatTime(v: string) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '-'
}

onMounted(async () => {
  const remote = await remoteLocales()
  if (remote.length) locales.value = remote
  await load()
})
</script>

<style scoped>
.page { padding: 20px; }
.desc { color: var(--el-text-color-secondary); font-size: 13px; margin: 0 0 12px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
</style>
