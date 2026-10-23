<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">{{ t('page.prompts') }}</span>
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
          <div class="content-actions">
            <el-button size="small" type="primary" plain @click="openOptimize">AI 优化</el-button>
            <span class="hint">保留原有变量占位，优化结果需手动应用</span>
          </div>
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

    <!-- AI 优化 Prompt 模板 -->
    <el-dialog v-model="optimizeVisible" title="AI 优化模板内容" width="760px">
      <div class="hint mb8">保留双花括号变量与原始业务意图；优化结果需手动应用，不会自动保存。</div>
      <el-form label-width="90px">
        <el-form-item label="优化方向">
          <el-select v-model="optimizeStyle" clearable placeholder="可选" style="width: 220px">
            <el-option label="结构化" value="结构化、分点清晰" />
            <el-option label="更简洁" value="精简冗余，保持信息完整" />
            <el-option label="更专业" value="更专业严谨，适合企业场景" />
            <el-option label="客服语气" value="礼貌、先结论后步骤的客服语气" />
          </el-select>
        </el-form-item>
        <el-form-item label="额外要求">
          <el-input v-model="optimizeInstruction" type="textarea" :rows="2"
                    placeholder="如：保留变量；补充拒答边界；不要改变语言" />
        </el-form-item>
      </el-form>
      <div class="opt-origin">
        <div class="opt-label">当前内容（{{ (editForm.content || '').length }} 字）</div>
        <div class="opt-origin-text">{{ editForm.content || '（空）' }}</div>
      </div>
      <div class="mt8">
        <el-button type="primary" :loading="optimizing" @click="runOptimize">开始优化</el-button>
        <span v-if="optimizeUsage" class="hint">
          {{ optimizeModel }} · 输入 {{ optimizeUsage.inputTokens }} / 输出 {{ optimizeUsage.outputTokens }} tokens
        </span>
      </div>
      <div v-if="optimizeResult" class="opt-result">
        <div class="opt-label">优化结果（{{ optimizeResult.length }} 字）</div>
        <el-input v-model="optimizeResult" type="textarea" :rows="9" />
        <div class="mt8">
          <el-button type="primary" @click="applyOptimized('replace')">替换原内容</el-button>
          <el-button @click="applyOptimized('append')">追加到末尾</el-button>
          <el-button @click="optimizeResult = ''">清除结果</el-button>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import { useUserStore } from '../stores/user'

const { t } = useI18n()

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

const optimizeVisible = ref(false)
const optimizing = ref(false)
const optimizeStyle = ref('')
const optimizeInstruction = ref('')
const optimizeResult = ref('')
const optimizeUsage = ref<any>(null)
const optimizeModel = ref('')
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

function openOptimize() {
  if (!editForm.content.trim()) { ElMessage.warning('请先填写模板内容'); return }
  optimizeResult.value = ''
  optimizeUsage.value = null
  optimizeInstruction.value = ''
  optimizeVisible.value = true
}

async function runOptimize() {
  if (!editForm.content.trim()) { ElMessage.warning('请先填写模板内容'); return }
  optimizing.value = true
  try {
    const res = await http.post('/prompt-templates/optimize', {
      prompt: editForm.content,
      name: editForm.name,
      description: editForm.category ? `模板分类：${editForm.category}` : '',
      instruction: optimizeInstruction.value,
      style: optimizeStyle.value
    }, { timeout: 120000 }) as any
    optimizeResult.value = res.optimizedPrompt || ''
    optimizeUsage.value = res.usage || null
    optimizeModel.value = res.modelName || ''
    ElMessage.success('优化完成，请确认后应用')
  } finally {
    optimizing.value = false
  }
}

function applyOptimized(mode: 'replace' | 'append') {
  if (!optimizeResult.value) return
  editForm.content = mode === 'replace'
    ? optimizeResult.value
    : (editForm.content ? editForm.content + '\n\n' + optimizeResult.value : optimizeResult.value)
  optimizeVisible.value = false
  ElMessage.success(mode === 'replace' ? '已替换模板内容（尚未保存）' : '已追加到模板内容末尾（尚未保存）')
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
.content-actions { display: flex; align-items: center; gap: 10px; margin-top: 8px; }
.opt-origin { border: 1px solid #ebeef5; border-radius: 6px; padding: 8px 10px; background: #fafafa; }
.opt-origin-text { white-space: pre-wrap; font-size: 12px; max-height: 130px; overflow-y: auto; color: #606266; }
.opt-label { font-size: 12px; color: #909399; margin-bottom: 4px; }
.opt-result { margin-top: 12px; border-top: 1px dashed #ebeef5; padding-top: 10px; }
.mb8 { margin-bottom: 8px; }
.mt8 { margin-top: 8px; }
</style>