<template>
  <div class="page">
    <div class="toolbar">
      <span class="page-title">知识库</span>
      <div class="filters">
        <el-input v-model="keyword" placeholder="搜索名称/描述" clearable class="kw"
                  @keydown.enter="search" @clear="search" />
        <el-select v-model="visibility" placeholder="可见范围" clearable class="vis" @change="search">
          <el-option label="只有我" value="PRIVATE" />
          <el-option label="所有团队成员" value="TENANT" />
          <el-option label="部分成员" value="PARTIAL" />
        </el-select>
        <el-button @click="search">搜索</el-button>
        <el-button v-if="canCreate" type="primary" @click="createVisible = true">新建知识库</el-button>
      </div>
    </div>

    <el-empty v-if="!loading && kbs.length === 0" description="暂无知识库，点击右上角创建" />

    <div v-else class="grid">
      <el-card v-for="kb in kbs" :key="kb.id" class="card" shadow="hover" @click="$router.push(`/knowledge/${kb.id}`)">
        <div class="card-head">
          <el-avatar :size="40" class="avatar">{{ kb.name.slice(0, 1) }}</el-avatar>
          <div class="head-main">
            <div class="name-row">
              <span class="name">{{ kb.name }}</span>
              <el-tag size="small" :type="visTag(kb.visibility)">{{ visText(kb.visibility) }}</el-tag>
            </div>
            <div class="meta">嵌入模型 {{ kb.embeddingModel }} · {{ formatDate(kb.createdAt) }}</div>
          </div>
        </div>
        <div class="desc">{{ kb.description || '暂无描述' }}</div>
        <div class="foot">
          <span>文档 {{ kb.documentCount ?? 0 }} · 分段 {{ kb.chunkCount ?? 0 }}</span>
          <span>创建人 {{ kb.creatorName || '-' }}</span>
        </div>
      </el-card>
    </div>

    <div v-if="total > pageSize" class="pager">
      <el-pagination layout="prev, pager, next, total" :total="total"
                     :page-size="pageSize" :current-page="page" @current-change="onPage" />
    </div>

    <el-dialog v-model="createVisible" title="新建知识库" width="480px">
      <el-form label-width="70px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如：产品手册库" maxlength="64" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="可见范围">
          <el-radio-group v-model="form.visibility">
            <el-radio value="TENANT">所有团队成员</el-radio>
            <el-radio value="PRIVATE">只有我</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="create">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'
import { useUserStore } from '../stores/user'

interface Kb {
  id: number; name: string; description: string; embeddingModel: string; createdAt: string
  visibility: string; documentCount: number; chunkCount: number; creatorName: string
}

const userStore = useUserStore()
const canCreate = computed(() =>
  !!userStore.userInfo?.isPlatformAdmin || (userStore.userInfo?.permissions || []).includes('kb:create'))

const kbs = ref<Kb[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 12
const keyword = ref('')
const visibility = ref('')
const loading = ref(false)
const createVisible = ref(false)
const creating = ref(false)
const form = reactive({ name: '', description: '', visibility: 'TENANT' })

async function load() {
  loading.value = true
  try {
    const data = await http.get('/knowledge-base', {
      params: { keyword: keyword.value || undefined, visibility: visibility.value || undefined,
        page: page.value, pageSize }
    }) as any
    kbs.value = data.list || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}
function onPage(p: number) {
  page.value = p
  load()
}

async function create() {
  if (!form.name.trim()) { ElMessage.warning('名称必填'); return }
  creating.value = true
  try {
    await http.post('/knowledge-base', {
      name: form.name, description: form.description, visibility: form.visibility
    })
    ElMessage.success('已创建')
    createVisible.value = false
    form.name = ''
    form.description = ''
    form.visibility = 'TENANT'
    await load()
  } finally {
    creating.value = false
  }
}

function visText(v?: string) {
  return v === 'PRIVATE' ? '只有我' : v === 'PARTIAL' ? '部分成员' : '所有团队成员'
}
function visTag(v?: string) {
  return v === 'PRIVATE' ? 'info' : v === 'PARTIAL' ? 'warning' : 'success'
}
function formatDate(t?: string) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : ''
}

onMounted(load)
</script>

<style scoped>
.page { max-width: 1100px; margin: 0 auto; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-title { font-size: 16px; font-weight: 600; }
.filters { display: flex; align-items: center; gap: 8px; }
.kw { width: 200px; }
.vis { width: 140px; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 16px; }
.card { cursor: pointer; }
.card-head { display: flex; gap: 12px; align-items: center; }
.head-main { min-width: 0; }
.name-row { display: flex; align-items: center; gap: 8px; }
.avatar { background: #67c23a; color: #fff; font-weight: 600; flex-shrink: 0; }
.name { font-weight: 600; font-size: 15px; }
.meta { font-size: 12px; color: #909399; margin-top: 2px; }
.desc { color: #909399; font-size: 13px; margin-top: 10px; min-height: 18px; }
.foot { display: flex; justify-content: space-between; color: #909399; font-size: 12px; margin-top: 10px;
        border-top: 1px dashed #ebeef5; padding-top: 8px; }
.pager { display: flex; justify-content: center; margin-top: 18px; }
</style>
