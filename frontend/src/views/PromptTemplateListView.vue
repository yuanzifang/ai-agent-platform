<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">{{ t('page.prompts') }}</span>
      <span class="sub">{{ t('prompt.sub') }}</span>
      <div class="spacer" />
      <el-input v-model="keyword" :placeholder="t('prompt.searchPh')" clearable style="width: 200px"
                @keyup.enter="load" @clear="load" />
      <el-select v-model="category" :placeholder="t('prompt.category')" clearable style="width: 120px" @change="load">
        <el-option v-for="c in categories" :key="c" :value="c" :label="c" />
      </el-select>
      <el-button @click="load">{{ t('prompt.search') }}</el-button>
      <el-button v-if="canManage" type="primary" @click="openCreate">{{ t('prompt.create') }}</el-button>
    </div>

    <el-table :data="list" size="small" v-loading="loading">
      <el-table-column prop="name" :label="t('prompt.name')" width="200" />
      <el-table-column prop="category" :label="t('prompt.category')" width="100" />
      <el-table-column :label="t('prompt.variables')" width="200">
        <template #default="{ row }">
          <el-tag v-for="v in row.variables || []" :key="v.key" size="small" class="var-tag">{{ v.key }}</el-tag>
          <span v-if="!(row.variables || []).length" class="muted">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="content" :label="t('prompt.contentPreview')" min-width="280" show-overflow-tooltip />
      <el-table-column prop="updatedAt" :label="t('prompt.updated')" width="160" />
      <el-table-column :label="t('prompt.actions')" width="140">
        <template #default="{ row }">
          <el-button v-if="canManage" size="small" text @click="openEdit(row)">{{ t('prompt.edit') }}</el-button>
          <el-button v-if="canManage" size="small" text type="danger" @click="remove(row)">{{ t('prompt.delete') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="editVisible" :title="editForm.id ? t('prompt.editTitle') : t('prompt.newTitle')" width="620px">
      <el-form label-width="70px">
        <el-form-item :label="t('prompt.name')" required>
          <el-input v-model="editForm.name" maxlength="128" :placeholder="t('prompt.namePh')" />
        </el-form-item>
        <el-form-item :label="t('prompt.category')">
          <el-input v-model="editForm.category" :placeholder="t('prompt.categoryPh')" style="width: 220px" />
        </el-form-item>
        <el-form-item :label="t('prompt.content')" required>
          <el-input v-model="editForm.content" type="textarea" :rows="9"
                    :placeholder="t('prompt.contentPh')" />
          <div class="content-actions">
            <el-button size="small" type="primary" plain @click="openOptimize">{{ t('prompt.optimize') }}</el-button>
            <span class="hint">{{ t('prompt.optimizeHint') }}</span>
          </div>
        </el-form-item>
        <el-form-item :label="t('prompt.variables')">
          <div>
            <el-tag v-for="v in previewVars" :key="v" size="small" class="var-tag">{{ v }}</el-tag>
            <span v-if="previewVars.length === 0" class="muted">{{ t('prompt.noVars') }}</span>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">{{ t('prompt.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('prompt.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- AI 优化 Prompt 模板 -->
    <el-dialog v-model="optimizeVisible" :title="t('prompt.optTitle')" width="760px">
      <div class="hint mb8">{{ t('prompt.optHint') }}</div>
      <el-form label-width="90px">
        <el-form-item :label="t('prompt.optDirection')">
          <el-select v-model="optimizeStyle" clearable :placeholder="t('prompt.optional')" style="width: 220px">
            <el-option :label="t('prompt.structured')" value="结构化、分点清晰" />
            <el-option :label="t('prompt.concise')" value="精简冗余，保持信息完整" />
            <el-option :label="t('prompt.professional')" value="更专业严谨，适合企业场景" />
            <el-option :label="t('prompt.serviceTone')" value="礼貌、先结论后步骤的客服语气" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('prompt.extraReq')">
          <el-input v-model="optimizeInstruction" type="textarea" :rows="2"
                    :placeholder="t('prompt.extraPh')" />
        </el-form-item>
      </el-form>
      <div class="opt-origin">
        <div class="opt-label">{{ t('prompt.currentContent') }}（{{ (editForm.content || '').length }} {{ t('prompt.chars') }}）</div>
        <div class="opt-origin-text">{{ editForm.content || t('prompt.empty') }}</div>
      </div>
      <div class="mt8">
        <el-button type="primary" :loading="optimizing" @click="runOptimize">{{ t('prompt.start') }}</el-button>
        <span v-if="optimizeUsage" class="hint">
          {{ optimizeModel }} · {{ t('prompt.inputTokens') }} {{ optimizeUsage.inputTokens }} / {{ t('prompt.outputTokens') }} {{ optimizeUsage.outputTokens }} tokens
        </span>
      </div>
      <div v-if="optimizeResult" class="opt-result">
        <div class="opt-label">{{ t('prompt.result') }}（{{ optimizeResult.length }} {{ t('prompt.chars') }}）</div>
        <el-input v-model="optimizeResult" type="textarea" :rows="9" />
        <div class="mt8">
          <el-button type="primary" @click="applyOptimized('replace')">{{ t('prompt.replace') }}</el-button>
          <el-button @click="applyOptimized('append')">{{ t('prompt.append') }}</el-button>
          <el-button @click="optimizeResult = ''">{{ t('prompt.clear') }}</el-button>
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
  if (!editForm.content.trim()) { ElMessage.warning(t('prompt.contentRequired')); return }
  optimizeResult.value = ''
  optimizeUsage.value = null
  optimizeInstruction.value = ''
  optimizeVisible.value = true
}

async function runOptimize() {
  if (!editForm.content.trim()) { ElMessage.warning(t('prompt.contentRequired')); return }
  optimizing.value = true
  try {
    const res = await http.post('/prompt-templates/optimize', {
      prompt: editForm.content,
      name: editForm.name,
      description: editForm.category ? `${t('prompt.categoryDesc')}${editForm.category}` : '',
      instruction: optimizeInstruction.value,
      style: optimizeStyle.value
    }, { timeout: 120000 }) as any
    optimizeResult.value = res.optimizedPrompt || ''
    optimizeUsage.value = res.usage || null
    optimizeModel.value = res.modelName || ''
    ElMessage.success(t('prompt.optDone'))
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
  ElMessage.success(mode === 'replace' ? t('prompt.replaced') : t('prompt.appended'))
}

async function save() {
  if (!editForm.name.trim()) { ElMessage.warning(t('prompt.nameRequired')); return }
  if (!editForm.content.trim()) { ElMessage.warning(t('prompt.contentRequired')); return }
  saving.value = true
  try {
    const payload = { name: editForm.name.trim(), category: editForm.category, content: editForm.content }
    if (editForm.id) await http.put(`/prompt-templates/${editForm.id}`, payload)
    else await http.post('/prompt-templates', payload)
    ElMessage.success(t('prompt.saved'))
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
    ? `${t('prompt.deleteRefsPrefix')}${agents.join('、')}${t('prompt.deleteRefsSuffix')}`
    : t('prompt.deleteConfirm')
  await ElMessageBox.confirm(`${t('prompt.deleteTitle')}: ${row.name}? ${extra}`, t('prompt.deleteTitle'),
    { type: 'warning', confirmButtonText: t('prompt.delete'), cancelButtonText: t('prompt.cancel') })
  await http.delete(`/prompt-templates/${row.id}`)
  ElMessage.success(t('prompt.deleted'))
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