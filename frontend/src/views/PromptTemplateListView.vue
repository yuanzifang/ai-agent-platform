<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">Prompt 模板</span>
      <span class="sub">模板内容支持双花括号变量，在 Agent 提示词中可一键插入</span>
      <div class="spacer" />
      <el-input v-model="keyword" placeholder="搜索名称/内容" clearable style="width: 200px"
                @keyup.enter="load" @clear="load" />
      <el-select v-model="category" placeholder="分类" clearable style="width: 120px" @change="load">
        <el-option v-for="c in categories" :key="c" :value="c" :label="c" />
      </el-select>
      <el-button @click="load">搜索</el-button>
      <el-button v-if="canManage" type="primary" @click="openCreate">新建模板</el-button>
    </div>

    <el-table :data="list" size="small" v-loading="loading">
      <el-table-column prop="name" label="名称" width="200" />
      <el-table-column prop="category" label="分类" width="100" />
      <el-table-column label="变量" width="200">
        <template #default="{ row }">
          <el-tag v-for="v in row.variables || []" :key="v.key" size="small" class="var-tag">{{ v.key }}</el-tag>
          <span v-if="!(row.variables || []).length" class="muted">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="content" label="内容预览" min-width="280" show-overflow-tooltip />
      <el-table-column prop="updatedAt" label="更新时间" width="160" />
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button v-if="canManage" size="small" text @click="openEdit(row)">编辑</el-button>
          <el-button v-if="canManage" size="small" text type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="editVisible" :title="editForm.id ? '编辑模板' : '新建模板'" width="620px">
      <el-form label-width="70px">
        <el-form-item label="名称" required>
          <el-input v-model="editForm.name" maxlength="128" placeholder="如：客服话术生成" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="editForm.category" placeholder="如：客服 / 营销 / 分析" style="width: 220px" />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="editForm.content" type="textarea" :rows="9"
                    placeholder="模板内容，使用 {{key}} 定义变量" />
        </el-form-item>
        <el-form-item label="变量">
          <div>
            <el-tag v-for="v in previewVars" :key="v" size="small" class="var-tag">{{ v }}</el-tag>
            <span v-if="previewVars.length === 0" class="muted">未检测到变量</span>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import { useUserStore } from '../stores/user'

const userStore = useUserStore()
const canManage = computed(() => {
  const perms = userStore.userInfo?.permissions || []
  return userStore.userInfo?.isPlatformAdmin || perms.includes('prompt:manage')
})

const list = ref<any[]>([])
const loading = ref(false)
const keyword = ref('')
const category = ref('')
const categories = computed(() => [...new Set(list.value.map(t => t.category).filter(Boolean))])

const editVisible = ref(false)
const saving = ref(false)
const editForm = reactive({ id: 0, name: '', category: '', content: '' })
const previewVars = computed(() => {
  const keys = new Set<string>()
  const re = /\{\{\s*([a-zA-Z0-9_]+)\s*\}\}/g
  let m: RegExpExecArray | null
  while ((m = re.exec(editForm.content)) !== null) keys.add(m[1])
  return [...keys]
})

onMounted(load)

async function load() {
  loading.value = true
  try {
    const res = await http.get('/prompt-templates', {
      params: { keyword: keyword.value || undefined, category: category.value || undefined }
    }) as any
    list.value = res?.list || []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(editForm, { id: 0, name: '', category: '', content: '' })
  editVisible.value = true
}

async function openEdit(row: any) {
  const detail = await http.get(`/prompt-templates/${row.id}`) as any
  Object.assign(editForm, {
    id: detail.id, name: detail.name || '', category: detail.category || '', content: detail.content || ''
  })
  editVisible.value = true
}

async function save() {
  if (!editForm.name.trim()) { ElMessage.warning('名称必填'); return }
  if (!editForm.content.trim()) { ElMessage.warning('内容必填'); return }
  saving.value = true
  try {
    const payload = { name: editForm.name.trim(), category: editForm.category, content: editForm.content }
    if (editForm.id) await http.put(`/prompt-templates/${editForm.id}`, payload)
    else await http.post('/prompt-templates', payload)
    ElMessage.success('已保存')
    editVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(row: any) {
  const refs = await http.get(`/prompt-templates/${row.id}/references`) as any
  const agents: string[] = refs?.agents || []
  const extra = agents.length
    ? `以下 Agent 的提示词包含该模板内容：${agents.join('、')}。删除后请检查其提示词。`
    : '确认删除该模板？'
  await ElMessageBox.confirm(`删除模板「${row.name}」？${extra}`, '删除模板',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  await http.delete(`/prompt-templates/${row.id}`)
  ElMessage.success('已删除')
  await load()
}
</script>

<style scoped>
.page { background: #fff; border: 1px solid #e4e7ed; border-radius: 6px; padding: 14px; }
.toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.title { font-weight: 600; font-size: 15px; }
.sub { color: #909399; font-size: 12px; margin-left: 8px; }
.spacer { flex: 1; }
.var-tag { margin-right: 4px; }
.muted { color: #c0c4cc; font-size: 12px; }
</style>
