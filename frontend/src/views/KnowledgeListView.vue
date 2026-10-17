<template>
  <div class="page">
    <div class="toolbar">
      <span class="page-title">知识库</span>
      <el-button type="primary" @click="createVisible = true">新建知识库</el-button>
    </div>

    <el-empty v-if="!loading && kbs.length === 0" description="暂无知识库，点击右上角创建" />

    <div v-else class="grid">
      <el-card v-for="kb in kbs" :key="kb.id" class="card" shadow="hover" @click="$router.push(`/knowledge/${kb.id}`)">
        <div class="card-head">
          <el-avatar :size="40" class="avatar">{{ kb.name.slice(0, 1) }}</el-avatar>
          <div>
            <div class="name">{{ kb.name }}</div>
            <div class="meta">嵌入模型 {{ kb.embeddingModel }} · {{ formatDate(kb.createdAt) }}</div>
          </div>
        </div>
        <div class="desc">{{ kb.description || '暂无描述' }}</div>
      </el-card>
    </div>

    <el-dialog v-model="createVisible" title="新建知识库" width="480px">
      <el-form label-width="70px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如：产品手册库" maxlength="64" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
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
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'

interface Kb { id: number; name: string; description: string; embeddingModel: string; createdAt: string }

const kbs = ref<Kb[]>([])
const loading = ref(false)
const createVisible = ref(false)
const creating = ref(false)
const form = reactive({ name: '', description: '' })

async function load() {
  loading.value = true
  try {
    const data = await http.get('/knowledge-base') as any
    kbs.value = Array.isArray(data) ? data : (data.list || [])
  } finally {
    loading.value = false
  }
}

async function create() {
  if (!form.name.trim()) { ElMessage.warning('名称必填'); return }
  creating.value = true
  try {
    await http.post('/knowledge-base', { name: form.name, description: form.description })
    ElMessage.success('已创建')
    createVisible.value = false
    form.name = ''
    form.description = ''
    await load()
  } finally {
    creating.value = false
  }
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
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 16px; }
.card { cursor: pointer; }
.card-head { display: flex; gap: 12px; align-items: center; }
.avatar { background: #67c23a; color: #fff; font-weight: 600; flex-shrink: 0; }
.name { font-weight: 600; font-size: 15px; }
.meta { font-size: 12px; color: #909399; margin-top: 2px; }
.desc { color: #909399; font-size: 13px; margin-top: 10px; }
</style>
