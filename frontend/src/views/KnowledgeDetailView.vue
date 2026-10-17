<template>
  <div class="page">
    <div class="toolbar">
      <el-button text @click="$router.push('/knowledge')">
        <el-icon><ArrowLeft /></el-icon> 返回
      </el-button>
      <span class="kb-title">{{ kb.name }}</span>
      <el-upload :show-file-list="false" :before-upload="upload" :disabled="uploading"
                 accept=".md,.txt,.docx,.pdf">
        <el-button type="primary" :loading="uploading">
          {{ uploading ? '上传中…' : '上传文档' }}
        </el-button>
      </el-upload>
    </div>

    <div class="body">
      <!-- 文档列表 + 分段 -->
      <div class="docs">
        <div class="sec-title">文档（{{ documents.length }}）</div>
        <el-table :data="documents" size="small" @row-click="toggleChunks">
          <el-table-column prop="fileName" label="文件名" min-width="180" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="chunkCount" label="分段" width="70" />
          <el-table-column label="错误" min-width="140">
            <template #default="{ row }">
              <span class="err">{{ row.errorMessage || '' }}</span>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="chunks.docId" class="chunks">
          <div class="sec-title">
            分段预览（{{ chunks.docName }}，共 {{ chunks.total }} 段）
            <el-button size="small" text @click="chunks.docId = null">收起</el-button>
          </div>
          <div v-for="c in chunks.list" :key="c.id" class="chunk">
            <span class="idx">#{{ c.chunkIndex }}</span>
            <span class="text">{{ c.content }}</span>
          </div>
        </div>
      </div>

      <!-- 检索测试 -->
      <div class="retrieval">
        <div class="sec-title">检索测试</div>
        <el-input v-model="search.query" placeholder="输入查询文本，验证召回效果" @keydown.enter="doSearch" />
        <div class="params">
          topK <el-input-number v-model="search.topK" :min="1" :max="20" size="small" />
          阈值 <el-input-number v-model="search.scoreThreshold" :min="0" :max="1" :step="0.05" size="small" />
          <el-button size="small" type="primary" :loading="searching" @click="doSearch">检索</el-button>
        </div>
        <el-empty v-if="searched && results.length === 0" description="无命中（阈值过高或未入库）" :image-size="60" />
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
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import http from '../api/http'

const route = useRoute()
const kbId = Number(route.params.id)
const kb = reactive({ name: '' })
const documents = ref<any[]>([])
const uploading = ref(false)
const searching = ref(false)
const searched = ref(false)
const results = ref<any[]>([])
const search = reactive({ query: '', topK: 5, scoreThreshold: 0.5 })
const chunks = reactive<{ docId: number | null; docName: string; list: any[]; total: number }>({
  docId: null, docName: '', list: [], total: 0
})

onMounted(async () => {
  const list = await http.get('/knowledge-base') as any
  const arr = Array.isArray(list) ? list : (list.list || [])
  const found = arr.find((k: any) => k.id === kbId)
  kb.name = found?.name || '知识库'
  await loadDocs()
})

async function loadDocs() {
  documents.value = await http.get(`/knowledge-base/${kbId}/documents`) as any
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
    if (!putResp.ok) throw new Error('对象存储上传失败 ' + putResp.status)
    await http.post(`/knowledge-base/${kbId}/documents/callback`, { objectName: presign.objectName })
    ElMessage.success('已上传，后台解析分段中…')
    setTimeout(loadDocs, 1500)
  } catch (e: any) {
    ElMessage.error('上传失败：' + (e.message || e))
  } finally {
    uploading.value = false
  }
  return false // 阻止 el-upload 默认行为
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

async function doSearch() {
  if (!search.query.trim()) return
  searching.value = true
  searched.value = true
  try {
    results.value = await http.post(`/knowledge-base/${kbId}/search`, {
      query: search.query, topK: search.topK, scoreThreshold: search.scoreThreshold
    }) as any
  } finally {
    searching.value = false
  }
}

function statusType(s: string) {
  return s === 'DONE' ? 'success' : s === 'PARSING' ? 'warning' : s === 'FAILED' ? 'danger' : 'info'
}
function statusText(s: string) {
  return s === 'DONE' ? '已完成' : s === 'PARSING' ? '解析中' : s === 'FAILED' ? '失败' : '待处理'
}
</script>

<style scoped>
.page { height: 100%; display: flex; flex-direction: column; }
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.kb-title { font-weight: 600; font-size: 16px; flex: 1; }
.body { flex: 1; display: flex; gap: 16px; min-height: 0; }
.docs { flex: 1.3; background: #fff; border: 1px solid #e4e7ed; border-radius: 6px; padding: 14px; overflow-y: auto; }
.retrieval { flex: 1; background: #fff; border: 1px solid #e4e7ed; border-radius: 6px; padding: 14px; overflow-y: auto; }
.sec-title { font-weight: 600; font-size: 14px; margin-bottom: 10px; display: flex; align-items: center; gap: 8px; }
.err { color: #f56c6c; font-size: 12px; }
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
</style>
