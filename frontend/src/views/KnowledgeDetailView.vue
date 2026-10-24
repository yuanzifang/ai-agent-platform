<template>
  <div class="page">
    <div class="toolbar">
      <el-button text @click="$router.push('/knowledge')">
        <el-icon><ArrowLeft /></el-icon> {{ t('kbDetail.back') }}
      </el-button>
      <span class="kb-title">{{ detail.name }}</span>
      <el-tag size="small" :type="visTag(detail.visibility)">{{ visText(detail.visibility) }}</el-tag>
      <span class="kb-meta">{{ t('kbDetail.metaDocs') }}{{ detail.documentCount }}{{ t('kbDetail.metaChunks') }}{{ detail.chunkCount }}{{ t('kbDetail.creator') }}{{ detail.creatorName || '-' }}</span>
      <el-button v-if="detail.canEdit" @click="openEdit">{{ t('kbDetail.edit') }}</el-button>
      <el-button @click="$router.push(`/knowledge/${kbId}/graph`)">{{ t('graphPage.title') }}</el-button>
      <el-button v-if="detail.canEdit" @click="openPermission">{{ t('kbDetail.permission') }}</el-button>
      <el-upload v-if="detail.canEdit" :show-file-list="false" :before-upload="upload" :disabled="uploading"
                 accept=".md,.txt,.docx,.pdf,.xlsx,.xls,.pptx,.html,.htm,.csv,.json,.png,.jpg,.jpeg,.bmp,.tif,.tiff">
        <el-button type="primary" :loading="uploading">
          {{ uploading ? t('kbDetail.uploading') : t('kbDetail.upload') }}
        </el-button>
      </el-upload>
      <el-button v-if="detail.canEdit" @click="urlVisible = true">{{ t('kbDetail.urlImport') }}</el-button>
      <el-button v-if="detail.canEdit" type="danger" plain @click="removeKb">{{ t('kbDetail.deleteKb') }}</el-button>
    </div>

    <div v-if="detail.canEdit" class="upload-hint">
      {{ t('kbDetail.uploadHint') }}
    </div>

    <div class="body">
      <!-- 文档列表 + 分段 -->
      <div class="docs">
        <div class="sec-title">{{ t('kbDetail.docs') }}{{ documents.length }}）</div>
        <el-table :data="documents" size="small" @row-click="toggleChunks">
          <el-table-column prop="fileName" :label="t('kbDetail.fileName')" min-width="180" />
          <el-table-column :label="t('kbDetail.status')" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="chunkCount" :label="t('kbDetail.chunks')" width="70" />
          <el-table-column :label="t('kbDetail.error')" min-width="160">
            <template #default="{ row }">
              <span class="err">{{ row.errorMessage || '' }}</span>
              <span v-if="row.retryTimes > 0" class="retry-tip">{{ t('kbDetail.retriedPrefix') }}{{ row.retryTimes }}{{ t('kbDetail.retriedSuffix') }}</span>
            </template>
          </el-table-column>
          <el-table-column v-if="detail.canEdit" :label="t('kbDetail.actions')" width="150">
            <template #default="{ row }">
              <el-button v-if="row.status === 'FAILED'" size="small" text type="warning"
                         @click.stop="retryDoc(row)">{{ t('kbDetail.retry') }}</el-button>
              <el-button size="small" text type="danger" @click.stop="removeDoc(row)">{{ t('kbDetail.deleteKb') }}</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="chunks.docId" class="chunks">
          <div class="sec-title">
            {{ t('kbDetail.chunkPreview') }}{{ chunks.docName }}{{ t('kbDetail.totalChunks') }}{{ chunks.total }}{{ t('kbDetail.totalSuffix') }}
            <el-button size="small" text @click="chunks.docId = null">{{ t('kbDetail.collapse') }}</el-button>
          </div>
          <div v-for="c in chunks.list" :key="c.id" class="chunk">
            <span class="idx">#{{ c.chunkIndex }}</span>
            <span class="text">{{ c.content }}</span>
            <el-button v-if="detail.canEdit" size="small" text type="primary"
                       @click="openChunkAcl(c)">{{ t('kbDetail.authorize') }}</el-button>
          </div>
        </div>
      </div>

      <!-- 检索测试 -->
      <div class="retrieval">
        <div class="sec-title">{{ t('kbDetail.searchTitle') }}</div>
        <el-input v-model="search.query" :placeholder="t('kbDetail.searchPh')" @keydown.enter="doSearch" />
        <div class="params">
          <el-select v-model="search.mode" size="small" style="width: 100px">
            <el-option :label="t('kbDetail.hybrid')" value="hybrid" />
            <el-option :label="t('kbDetail.vector')" value="vector" />
            <el-option :label="t('kbDetail.keyword')" value="keyword" />
          </el-select>
          topK <el-input-number v-model="search.topK" :min="1" :max="20" size="small" />
          {{ t('kbDetail.threshold') }} <el-input-number v-model="search.scoreThreshold" :min="0" :max="1" :step="0.05" size="small" />
          <el-button size="small" type="primary" :loading="searching" @click="doSearch">{{ t('kbDetail.search') }}</el-button>
        </div>
        <el-empty v-if="searched && results.length === 0" :description="t('kbDetail.noHits')" :image-size="60" />
        <div v-for="(r, i) in results" :key="i" class="hit">
          <div class="hit-head">
            <el-progress :percentage="Math.round(r.score * 100)" :stroke-width="10"
                         :color="r.score > 0.6 ? '#67c23a' : '#e6a23c'" class="score" />
            <span class="pct">{{ (r.score * 100).toFixed(1) }}%</span>
          </div>
          <div class="hit-text">{{ r.content }}</div>
        </div>
      </div>
    </div>

    <el-dialog v-model="editVisible" :title="t('kbDetail.editTitle')" width="480px">
      <el-form label-width="70px">
        <el-form-item :label="t('kbDetail.name')" required>
          <el-input v-model="editForm.name" maxlength="128" />
        </el-form-item>
        <el-form-item :label="t('kbDetail.desc')">
          <el-input v-model="editForm.description" type="textarea" :rows="2" maxlength="512" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">{{ t('kbDetail.cancel') }}</el-button>
        <el-button type="primary" :loading="editSaving" @click="saveEdit">{{ t('kbDetail.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- 权限设置（需求 3.6.3(5)：只有我 / 所有团队成员 / 部分团队成员） -->
    <el-dialog v-model="urlVisible" :title="t('kbDetail.urlTitle')" width="540px">
      <el-form label-width="70px">
        <el-form-item label="URL" required>
          <el-input v-model="urlForm.url" placeholder="https://example.com/manual.html" />
        </el-form-item>
      </el-form>
      <div class="hint">{{ t('kbDetail.urlHint') }}</div>
      <template #footer>
        <el-button @click="urlVisible = false">{{ t('kbDetail.cancel') }}</el-button>
        <el-button type="primary" :loading="urlImporting" @click="importUrl">{{ t('kbDetail.import') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="permVisible" :title="t('kbDetail.permTitle')" width="620px">
      <el-form label-width="90px">
        <el-form-item :label="t('kbDetail.visibility')">
          <el-radio-group v-model="perm.visibility">
            <el-radio value="PRIVATE">{{ t('kbDetail.private') }}</el-radio>
            <el-radio value="TENANT">{{ t('kbDetail.tenantAll') }}</el-radio>
            <el-radio value="PARTIAL">{{ t('kbDetail.partial') }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="perm.visibility === 'PARTIAL'" :label="t('kbDetail.grantedMembers')">
          <el-table :data="grantableMembers" size="small" max-height="300" class="member-table">
            <el-table-column width="46">
              <template #default="{ row }">
                <el-checkbox :model-value="isGranted(row.id)" @change="toggleGrant(row.id)" />
              </template>
            </el-table-column>
            <el-table-column :label="t('kbDetail.member')">
              <template #default="{ row }">{{ memberLabel(row) }}</template>
            </el-table-column>
            <el-table-column :label="t('kbDetail.perm')" width="130">
              <template #default="{ row }">
                <el-select :model-value="permOf(row.id)" :disabled="!isGranted(row.id)"
                           size="small" @change="(v: string) => setPerm(row.id, v)">
                  <el-option :label="t('kbDetail.view')" value="VIEW" />
                  <el-option :label="t('kbDetail.editPerm')" value="EDIT" />
                </el-select>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="grantableMembers.length === 0" :description="t('kbDetail.noGrantable')" :image-size="50" />
        </el-form-item>
        <div class="perm-tip">
          <div>{{ t('kbDetail.permTip1') }}</div>
          <div>{{ t('kbDetail.permTip2') }}</div>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="permVisible = false">{{ t('kbDetail.cancel') }}</el-button>
        <el-button type="primary" :loading="savingPerm" @click="savePermission">{{ t('kbDetail.save') }}</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="imagesVisible" :title="`${t('kbDetail.imagesTitle')}${imagesDocName}`" width="760px">
      <el-empty v-if="!documentImages.length" :description="t('kbDetail.noImages')" :image-size="60" />
      <div v-else class="image-grid">
        <div v-for="img in documentImages" :key="img.id" class="image-card">
          <el-image :src="img.url" fit="contain" style="width: 100%; height: 150px" />
          <div class="hint">{{ img.fileName }} · {{ img.mimeType }}</div>
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="aclVisible" :title="t('kbDetail.aclTitle')" width="520px">
      <el-form label-width="100px">
        <el-form-item :label="t('kbDetail.tenantVisible')">
          <el-switch v-model="aclForm.tenantVisible" />
          <span class="hint" style="margin-left:8px">{{ t('kbDetail.tenantVisibleHint') }}</span>
        </el-form-item>
        <el-form-item :label="t('kbDetail.assignedUsers')">
          <el-select v-model="aclForm.users" multiple filterable style="width:100%"
                     :disabled="aclForm.tenantVisible" :placeholder="t('kbDetail.selectUsers')">
            <el-option v-for="m in aclMembers" :key="m.id" :value="m.id"
                       :label="(m.displayName || m.username) + ' (' + m.id + ')'" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="aclVisible = false">{{ t('kbDetail.cancel') }}</el-button>
        <el-button type="primary" :loading="aclSaving" @click="saveChunkAcl">{{ t('kbDetail.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import http from '../api/http'

const { t } = useI18n()

const route = useRoute()
const router = useRouter()
const kbId = Number(route.params.id)
const detail = reactive<Record<string, any>>({
  name: '', visibility: 'TENANT', creatorId: null, creatorName: '', documentCount: 0, chunkCount: 0, canEdit: false
})
const documents = ref<any[]>([])
const uploading = ref(false)
const urlVisible = ref(false)
const urlImporting = ref(false)
const urlForm = reactive({ url: '' })
const searching = ref(false)
const searched = ref(false)
const results = ref<any[]>([])
const search = reactive({ query: '', topK: 5, scoreThreshold: 0.5, mode: 'hybrid' })
const chunks = reactive<{ docId: number | null; docName: string; list: any[]; total: number }>({
  docId: null, docName: '', list: [], total: 0
})
const imagesVisible = ref(false)
const documentImages = ref<any[]>([])
const imagesDocName = ref('')
const aclVisible = ref(false)
const aclSaving = ref(false)
const aclChunk = ref<any>(null)
const aclMembers = ref<any[]>([])
const aclForm = reactive<{ tenantVisible: boolean; users: number[] }>({ tenantVisible: true, users: [] })

// ---- 编辑/删除 ----
const editVisible = ref(false)
const editSaving = ref(false)
const editForm = reactive({ name: '', description: '' })

// ---- 权限设置 ----
const permVisible = ref(false)
const savingPerm = ref(false)
const members = ref<any[]>([])
const perm = reactive<{ visibility: string; members: Record<number, string> }>({
  visibility: 'TENANT', members: {}
})
const grantableMembers = computed(() =>
  members.value.filter(m => m.id !== detail.creatorId && !(m.roles || []).includes('RESTRICTED')))

onMounted(async () => {
  await loadDetail()
  await loadDocs()
})

async function loadDetail() {
  const data = await http.get(`/knowledge-base/${kbId}`) as any
  Object.assign(detail, data)
}

async function loadDocs() {
  documents.value = await http.get(`/knowledge-base/${kbId}/documents`) as any
}

/** 解析中轮询刷新（异步解析，需求 3.6.3(2)）。 */
function pollDocs(times = 15) {
  setTimeout(async () => {
    await Promise.all([loadDocs(), loadDetail()])
    if (times > 0 && documents.value.some(d => d.status === 'PENDING' || d.status === 'PARSING')) {
      pollDocs(times - 1)
    }
  }, 2000)
}

function openEdit() {
  editForm.name = detail.name
  editForm.description = detail.description || ''
  editVisible.value = true
}

async function saveEdit() {
  if (!editForm.name.trim()) { ElMessage.warning(t('kbDetail.nameRequired')); return }
  editSaving.value = true
  try {
    await http.put(`/knowledge-base/${kbId}`, {
      name: editForm.name.trim(), description: editForm.description
    })
    ElMessage.success(t('kbDetail.saved'))
    editVisible.value = false
    await loadDetail()
  } finally {
    editSaving.value = false
  }
}

async function removeKb() {
  try {
    const refs = await http.get(`/knowledge-base/${kbId}/references`) as any
    const agents: string[] = refs?.agents || []
    const msg = agents.length
      ? `${t('kbDetail.deleteKbRefsPrefix')}${agents.length}${t('kbDetail.deleteKbRefsMid')}${agents.join('、')}${t('kbDetail.deleteKbRefsSuffix')}`
      : `${t('kbDetail.deleteKbConfirmPrefix')}${detail.name}${t('kbDetail.deleteKbConfirmMid')}`
    await ElMessageBox.confirm(msg, t('kbDetail.deleteKbTitle'), {
      type: 'warning', confirmButtonText: t('kbDetail.delete'), cancelButtonText: t('kbDetail.cancel')
    })
    await http.delete(`/knowledge-base/${kbId}`, { params: { force: agents.length > 0 } })
    ElMessage.success(t('kbDetail.deleted'))
    router.push('/knowledge')
  } catch {
    // 用户取消或请求失败（拦截器已提示）
  }
}

async function removeDoc(row: any) {
  try {
    await ElMessageBox.confirm(`${t('kbDetail.deleteDocPrefix')}${row.fileName}${t('kbDetail.deleteDocMid')}`,
      t('kbDetail.deleteDocTitle'), { type: 'warning', confirmButtonText: t('kbDetail.delete'), cancelButtonText: t('kbDetail.cancel') })
    await http.delete(`/knowledge-base/${kbId}/documents/${row.id}`)
    ElMessage.success(t('kbDetail.deleted'))
    if (chunks.docId === row.id) chunks.docId = null
    await Promise.all([loadDocs(), loadDetail()])
  } catch {
    // 用户取消或请求失败
  }
}

async function retryDoc(row: any) {
  try {
    await http.post(`/knowledge-base/${kbId}/documents/${row.id}/retry`)
    ElMessage.success(t('kbDetail.retrySubmitted'))
    if (chunks.docId === row.id) chunks.docId = null
    await loadDocs()
    pollDocs()
  } catch {
    // 拦截器已提示（如达到重试上限）
  }
}

/** 预签名直传：POST 拿 URL → PUT 文件 → callback 登记（需求 3.6）。 */
async function upload(file: File) {
  uploading.value = true
  try {
    const presign = await http.post(`/knowledge-base/${kbId}/documents`, {
      fileName: file.name, sizeBytes: file.size, contentType: file.type || 'application/octet-stream'
    }) as any
    const putResp = await fetch(presign.uploadUrl, {
      method: 'PUT',
      headers: { 'Content-Type': file.type || 'application/octet-stream' },
      body: file
    })
    if (!putResp.ok) throw new Error(t('kbDetail.uploadFailed') + putResp.status)
    await http.post(`/knowledge-base/${kbId}/documents/callback`, { objectName: presign.objectName })
    ElMessage.success(t('kbDetail.uploaded'))
    await loadDocs()
    pollDocs()
  } catch (e: any) {
    ElMessage.error(t('kbDetail.uploadError') + (e.message || e))
  } finally {
    uploading.value = false
  }
  return false // 阻止 el-upload 默认行为
}

async function importUrl() {
  if (!urlForm.url.trim()) { ElMessage.warning(t('kbDetail.urlRequired')); return }
  urlImporting.value = true
  try {
    await http.post(`/knowledge-base/${kbId}/documents/url`, { url: urlForm.url.trim() })
    ElMessage.success(t('kbDetail.urlSubmitted'))
    urlVisible.value = false
    urlForm.url = ''
    await loadDocs()
    pollDocs()
  } finally {
    urlImporting.value = false
  }
}

async function toggleChunks(row: any) {
  if (chunks.docId === row.id) { chunks.docId = null; return }
  const data = await http.get(`/knowledge-base/${kbId}/documents/${row.id}/chunks`,
    { params: { pageSize: 20 } }) as any
  chunks.docId = row.id
  chunks.docName = row.fileName
  chunks.list = data.list || []
  chunks.total = data.total || 0
}

async function openImages(row: any) {
  imagesDocName.value = row.fileName
  try {
    documentImages.value = (await http.get(`/knowledge-base/${kbId}/documents/${row.id}/images`) as any) || []
    imagesVisible.value = true
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('kbDetail.imageLoadFailed'))
  }
}

async function openChunkAcl(c: any) {
  aclChunk.value = c
  let users = c.allowedUsers
  if (typeof users === 'string') { try { users = JSON.parse(users) } catch { users = [] } }
  aclForm.users = (users || []).map((x: any) => Number(x)).filter((x: number) => !Number.isNaN(x))
  aclForm.tenantVisible = c.tenantVisible !== false
  if (!aclMembers.value.length) {
    try { aclMembers.value = (await http.get('/users') as any) || [] } catch { /* 权限不足时仍可编辑已有 ID */ }
  }
  aclVisible.value = true
}

async function saveChunkAcl() {
  if (!aclChunk.value) return
  aclSaving.value = true
  try {
    await http.put(`/knowledge-base/${kbId}/chunks/${aclChunk.value.id}/acl`, {
      allowedUsers: aclForm.users, tenantVisible: aclForm.tenantVisible
    })
    ElMessage.success(t('kbDetail.chunkAclSaved'))
    aclVisible.value = false
    await toggleChunks({ id: chunks.docId, fileName: chunks.docName })
  } finally {
    aclSaving.value = false
  }
}

async function doSearch() {
  if (!search.query.trim()) return
  searching.value = true
  searched.value = true
  try {
    results.value = await http.post(`/knowledge-base/${kbId}/search`, {
      query: search.query, topK: search.topK, scoreThreshold: search.scoreThreshold, mode: search.mode
    }) as any
  } finally {
    searching.value = false
  }
}

async function openPermission() {
  try {
    const [data, ms] = await Promise.all([
      http.get(`/knowledge-base/${kbId}/permissions`) as any,
      http.get('/users') as any
    ])
    perm.visibility = data.visibility || 'TENANT'
    perm.members = {}
    for (const m of (data.members || [])) perm.members[m.userId] = m.permission || 'VIEW'
    members.value = ms || []
    permVisible.value = true
  } catch (e: any) {
    ElMessage.error(t('kbDetail.permissionLoadFailed') + (e.message || e))
  }
}

function isGranted(id: number) {
  return id in perm.members
}
function permOf(id: number) {
  return perm.members[id] || 'VIEW'
}
function toggleGrant(id: number) {
  if (id in perm.members) delete perm.members[id]
  else perm.members[id] = 'VIEW'
}
function setPerm(id: number, value: string) {
  perm.members[id] = value
}
function memberLabel(m: any) {
  return m.displayName ? `${m.displayName}（${m.username}）` : m.username
}

async function savePermission() {
  savingPerm.value = true
  try {
    const membersPayload = Object.entries(perm.members)
      .map(([userId, permission]) => ({ userId: Number(userId), permission }))
    await http.put(`/knowledge-base/${kbId}/permissions`, {
      visibility: perm.visibility, members: membersPayload
    })
    ElMessage.success(t('kbDetail.permissionSaved'))
    permVisible.value = false
    await loadDetail()
  } finally {
    savingPerm.value = false
  }
}

function visText(v: string) {
  return v === 'PRIVATE' ? t('kbDetail.visPrivate') : v === 'PARTIAL' ? t('kbDetail.visPartial') : t('kbDetail.visTenant')
}
function visTag(v: string) {
  return v === 'PRIVATE' ? 'info' : v === 'PARTIAL' ? 'warning' : 'success'
}
function statusType(s: string) {
  return s === 'DONE' ? 'success' : s === 'PARSING' ? 'warning' : s === 'FAILED' ? 'danger' : 'info'
}
function statusText(s: string) {
  return s === 'DONE' ? t('kbDetail.done') : s === 'PARSING' ? t('kbDetail.parsing') : s === 'FAILED' ? t('kbDetail.failed') : t('kbDetail.pending')
}
</script>

<style scoped>
.upload-hint { font-size: 12px; color: #909399; margin: 0 0 10px 4px; }
.page { height: 100%; display: flex; flex-direction: column; }
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.kb-title { font-weight: 600; font-size: 16px; }
.kb-meta { color: #909399; font-size: 12px; flex: 1; }
.body { flex: 1; display: flex; gap: 16px; min-height: 0; }
.docs { flex: 1.3; background: #fff; border: 1px solid #e4e7ed; border-radius: 6px; padding: 14px; overflow-y: auto; }
.retrieval { flex: 1; background: #fff; border: 1px solid #e4e7ed; border-radius: 6px; padding: 14px; overflow-y: auto; }
.sec-title { font-weight: 600; font-size: 14px; margin-bottom: 10px; display: flex; align-items: center; gap: 8px; }
.err { color: #f56c6c; font-size: 12px; }
.retry-tip { color: #e6a23c; font-size: 12px; }
.chunks { margin-top: 14px; border-top: 1px dashed #dcdfe6; padding-top: 10px; }
.chunk { display: flex; gap: 8px; font-size: 13px; padding: 6px 0; border-bottom: 1px dashed #ebeef5; }
.idx { color: #409eff; flex-shrink: 0; }
.text { color: #606266; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.params { display: flex; align-items: center; gap: 8px; margin: 10px 0; font-size: 13px; color: #606266; }
.hit { border: 1px solid #ebeef5; border-radius: 6px; padding: 10px; margin-bottom: 10px; }
.hit-head { display: flex; align-items: center; gap: 8px; }
.score { flex: 1; }
.pct { font-size: 12px; color: #909399; width: 48px; text-align: right; }
.hit-text { font-size: 13px; color: #303133; margin-top: 6px; white-space: pre-wrap; }
.member-table { width: 100%; }
.perm-tip { color: #909399; font-size: 12px; line-height: 20px; padding-left: 90px; }
</style>