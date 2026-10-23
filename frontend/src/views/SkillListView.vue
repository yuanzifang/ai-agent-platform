<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">{{ t('page.skills') }}</span>
      <span class="sub">{{ t('skill.sub') }}</span>
      <div class="spacer" />
      <el-input v-model="keyword" :placeholder="t('skill.searchPh')" clearable style="width: 190px"
                @keyup.enter="load" @clear="load" />
      <el-select v-model="type" :placeholder="t('skill.type')" clearable style="width: 110px" @change="load">
        <el-option :label="t('skill.builtin')" value="BUILTIN" />
        <el-option :label="t('skill.custom')" value="CUSTOM" />
      </el-select>
      <el-button @click="load">{{ t('skill.search') }}</el-button>
      <el-button type="primary" :disabled="!canManage" @click="openCreate">{{ t('skill.create') }}</el-button>
    </div>

    <el-alert v-if="!canManage" type="info" :closable="false" show-icon class="mb12"
              :title="t('skill.readonly')" />

    <el-table :data="list" size="small" v-loading="loading">
      <template #empty>
        <el-empty :description="t('skill.empty')">
          <el-button type="primary" :disabled="!canManage" @click="openCreate">{{ t('skill.create') }}</el-button>
        </el-empty>
      </template>
      <el-table-column :label="t('skill.name')" width="200">
        <template #default="{ row }">
          {{ row.name }}
          <el-tag v-if="row.isBuiltin" size="small" type="info">{{ t('skill.builtin') }}</el-tag>
          <el-tag v-if="row.hasScript" size="small" type="warning" class="ml8">
            {{ row.scriptLanguage === 'PYTHON' ? 'Python' : 'JS' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="category" :label="t('skill.category')" width="90" />
      <el-table-column prop="description" :label="t('skill.desc')" min-width="240" show-overflow-tooltip />
      <el-table-column :label="t('skill.dep')" width="160">
        <template #default="{ row }">
          <span class="deps">{{ depText(row.dependencies) }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('skill.status')" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">
            {{ row.status === 'ENABLED' ? t('skill.enabled') : t('skill.disabled') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('skill.actions')" width="270">
        <template #default="{ row }">
          <el-button size="small" text :disabled="!canManage" @click="openTest(row)">{{ t('skill.verify') }}</el-button>
          <template v-if="canManage && !row.isBuiltin">
            <el-button size="small" text @click="openEdit(row)">{{ t('skill.edit') }}</el-button>
            <el-button size="small" text @click="publish(row)">{{ t('skill.publish') }}</el-button>
            <el-button size="small" text @click="openVersions(row)">{{ t('skill.versions') }}</el-button>
            <el-button size="small" text type="danger" @click="remove(row)">{{ t('skill.delete') }}</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建/编辑 -->
    <el-dialog v-model="editVisible" :title="editForm.id ? t('skill.editTitle') : t('skill.newTitle')" width="780px">
      <el-form label-width="90px">
        <el-form-item :label="t('skill.name')" required>
          <el-input v-model="editForm.name" maxlength="128" />
        </el-form-item>
        <el-form-item :label="t('skill.desc')">
          <el-input v-model="editForm.description" maxlength="512" />
        </el-form-item>
        <el-form-item :label="t('skill.category')">
          <el-input v-model="editForm.category" :placeholder="t('skill.categoryPh')" style="width: 220px" />
        </el-form-item>
        <el-form-item :label="t('skill.tags')">
          <el-input v-model="editForm.tags" :placeholder="t('skill.tagsPh')" />
        </el-form-item>
        <el-form-item :label="t('skill.prompt')">
          <el-input v-model="editForm.prompt" type="textarea" :rows="5"
                    :placeholder="t('skill.promptPh')" />
        </el-form-item>
        <el-form-item :label="t('skill.params')">
          <el-input v-model="editForm.params" type="textarea" :rows="3"
                    :placeholder="t('skill.paramsPh')" />
        </el-form-item>
        <el-form-item :label="t('skill.script')">
          <el-select v-model="editForm.scriptLanguage" style="width: 200px">
            <el-option :label="t('skill.none')" value="NONE" />
            <el-option label="Python 3" value="PYTHON" />
            <el-option label="JavaScript (Node)" value="JAVASCRIPT" />
          </el-select>
          <span class="hint">{{ t('skill.scriptHint') }}</span>
        </el-form-item>
        <template v-if="editForm.scriptLanguage !== 'NONE'">
          <el-alert type="warning" :closable="false" show-icon class="mb12"
                    :title="t('skill.warning')" />
          <el-form-item :label="t('skill.scriptCode')">
            <el-input v-model="editForm.scriptCode" type="textarea" :rows="10" class="code-area"
                      :placeholder="scriptPlaceholder" />
          </el-form-item>
          <el-form-item :label="t('skill.timeout')">
            <el-input-number v-model="editForm.scriptTimeoutMs" :min="500" :max="60000" :step="500" />
            <span class="hint">{{ t('skill.timeoutHint') }}</span>
          </el-form-item>
          <el-form-item :label="t('skill.test')">
            <div class="script-test">
              <el-input v-model="scriptArgs" type="textarea" :rows="2"
                        :placeholder="t('skill.testPh')" />
              <div class="mt8">
                <el-button type="primary" plain :loading="scriptTesting" @click="testScript">{{ t('skill.runScript') }}</el-button>
                <span class="hint">{{ t('skill.testHint') }}</span>
              </div>
              <pre v-if="scriptOutput" class="script-output">{{ scriptOutput }}</pre>
            </div>
          </el-form-item>
        </template>
        <el-form-item :label="t('skill.depTools')">
          <el-select v-model="editForm.toolIds" multiple filterable style="width: 100%"
                     :placeholder="t('skill.depToolsPh')">
            <el-option v-for="tool in toolOptions" :key="tool.id" :value="tool.id"
                       :label="tool.name + (tool.isBuiltin ? t('skill.builtinSuffix') : '')" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('skill.depKbs')">
          <el-select v-model="editForm.kbIds" multiple filterable style="width: 100%"
                     :placeholder="t('skill.depKbsPh')">
            <el-option v-for="k in kbOptions" :key="k.id" :value="k.id" :label="k.name" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">{{ t('skill.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('skill.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- 测试 -->
    <el-dialog v-model="testVisible" :title="`${t('skill.verifyTitle')}${testForm.name}`" width="640px">
      <el-input v-model="testForm.input" type="textarea" :rows="3" :placeholder="t('skill.inputPh')" />
      <div class="mt8">
        <el-button type="primary" :loading="testing" @click="runTest">{{ t('skill.startVerify') }}</el-button>
        <span class="hint">{{ t('skill.verifyHint') }}</span>
      </div>
      <div v-if="testForm.answer" class="test-result">
        <div class="test-label">{{ t('skill.output') }}</div>
        <div class="test-output">{{ testForm.answer }}</div>
        <div v-if="testForm.toolCalls.length" class="test-label mt8">{{ t('skill.toolCalls') }}</div>
        <div v-for="(tc, i) in testForm.toolCalls" :key="i" class="test-tool">
          ⚙ {{ tc.displayName || tc.toolName }} · {{ tc.status === 'ERROR' ? t('skill.failed') : t('skill.success') }} · {{ tc.durationMs }}ms
        </div>
        <div class="hint mt8" v-if="testForm.usage">
          {{ t('skill.inputTokens') }} {{ testForm.usage.inputTokens }} / {{ t('skill.outputTokens') }} {{ testForm.usage.outputTokens }} tokens
        </div>
      </div>
    </el-dialog>

    <!-- 版本 -->
    <el-drawer v-model="versionsVisible" :title="t('skill.versionHistory')" size="420px">
      <el-table :data="versions" size="small">
        <el-table-column prop="version" :label="t('skill.version')" width="80" />
        <el-table-column prop="createdAt" :label="t('skill.time')" />
        <el-table-column :label="t('skill.actions')" width="90">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="rollback(row.version)">{{ t('skill.rollback') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="versions.length === 0" :description="t('skill.noVersions')" :image-size="60" />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import { useUserStore } from '../stores/user'

const { t } = useI18n()

const userStore = useUserStore()
const route = useRoute()
const router = useRouter()
const canManage = computed(() => {
  const perms = userStore.userInfo?.permissions || []
  return userStore.userInfo?.isPlatformAdmin || perms.includes('skill:manage')
})

const list = ref<any[]>([])
const loading = ref(false)
const keyword = ref('')
const type = ref('')

const toolOptions = ref<any[]>([])
const kbOptions = ref<any[]>([])

const editVisible = ref(false)
const saving = ref(false)
const editForm = reactive({
  id: 0, name: '', description: '', category: '', tags: '',
  prompt: '', params: '[]',
  scriptLanguage: 'NONE', scriptCode: '', scriptTimeoutMs: 5000,
  toolIds: [] as number[], kbIds: [] as number[]
})
const scriptArgs = ref('{}')
const scriptOutput = ref('')
const scriptTesting = ref(false)
const scriptPlaceholder = computed(() => editForm.scriptLanguage === 'PYTHON'
  ? '# 可用 params（或 args）读取调用参数，print 输出结果\nresult = params.get("a", 0) + params.get("b", 0)\nprint(result)'
  : '// 可用 params（或 args）读取调用参数，console.log 输出结果\nconsole.log(Number(params.a || 0) + Number(params.b || 0));')

const testVisible = ref(false)
const testing = ref(false)
const testForm = reactive({ id: 0, name: '', input: '', answer: '', toolCalls: [] as any[], usage: null as any })

const versionsVisible = ref(false)
const versions = ref<any[]>([])
const versionSkillId = ref(0)

onMounted(async () => {
  await Promise.all([load(), loadOptions()])
  if (route.query.create === '1') {
    if (canManage.value) openCreate()
    else ElMessage.warning(t('skill.noPermission'))
    const query = { ...route.query }
    delete query.create
    router.replace({ query })
  }
})

async function load() {
  loading.value = true
  try {
    const res = await http.get('/skills', {
      params: { keyword: keyword.value || undefined, type: type.value || undefined }
    }) as any
    list.value = res?.list || []
  } finally {
    loading.value = false
  }
}

async function loadOptions() {
  const [toolsRes, kbRes] = await Promise.all([
    http.get('/tools') as any,
    http.get('/knowledge-base', { params: { pageSize: 100 } }) as any
  ])
  toolOptions.value = toolsRes?.list || []
  kbOptions.value = kbRes?.list || []
}

function depText(deps?: any[]) {
  if (!deps?.length) return '-'
  const tools = deps.filter(d => d.depType === 'TOOL').length
  const kbs = deps.filter(d => d.depType === 'KNOWLEDGE_BASE').length
  return [tools ? `${t('skill.depsTools')}${tools}` : '', kbs ? `${t('skill.depsKbs')}${kbs}` : ''].filter(Boolean).join(' / ') || '-'
}

function openCreate() {
  Object.assign(editForm, {
    id: 0, name: '', description: '', category: '', tags: '', prompt: '', params: '[]',
    scriptLanguage: 'NONE', scriptCode: '', scriptTimeoutMs: 5000,
    toolIds: [], kbIds: []
  })
  scriptArgs.value = '{}'
  scriptOutput.value = ''
  editVisible.value = true
}

async function openEdit(row: any) {
  const detail = await http.get(`/skills/${row.id}`) as any
  const deps: any[] = detail.dependencies || []
  Object.assign(editForm, {
    id: detail.id,
    name: detail.name || '',
    description: detail.description || '',
    category: detail.category || '',
    tags: (detail.tags || []).join(','),
    prompt: detail.prompt || '',
    params: JSON.stringify(detail.params || [], null, 2),
    scriptLanguage: detail.scriptLanguage || 'NONE',
    scriptCode: detail.scriptCode || '',
    scriptTimeoutMs: detail.scriptTimeoutMs || 5000,
    toolIds: deps.filter(d => d.depType === 'TOOL').map(d => d.depId),
    kbIds: deps.filter(d => d.depType === 'KNOWLEDGE_BASE').map(d => d.depId)
  })
  scriptArgs.value = '{}'
  scriptOutput.value = ''
  editVisible.value = true
}

async function testScript() {
  if (!editForm.scriptCode.trim()) { ElMessage.warning(t('skill.scriptRequired')); return }
  let args: any = {}
  if (scriptArgs.value.trim()) {
    try { args = JSON.parse(scriptArgs.value) } catch { ElMessage.error(t('skill.testArgsInvalid')); return }
  }
  scriptTesting.value = true
  scriptOutput.value = ''
  try {
    const res = await http.post('/skills/script/test', {
      scriptLanguage: editForm.scriptLanguage,
      scriptCode: editForm.scriptCode,
      scriptTimeoutMs: editForm.scriptTimeoutMs,
      params: editForm.params,
      args
    }) as any
    scriptOutput.value = `${t('skill.output')} (${res.durationMs}ms)\n${res.output || t('skill.noOutput')}`
      + (res.stderr ? `\n[stderr]\n${res.stderr}` : '')
      + (res.truncated ? `\n${t('skill.truncated')}` : '')
  } finally {
    scriptTesting.value = false
  }
}

function parseJson(text: string, label: string) {
  if (!text || !text.trim()) return null
  try {
    return JSON.parse(text)
  } catch {
    ElMessage.error(`${label} ${t('skill.invalidJson')}`)
    return undefined
  }
}

async function save() {
  if (!editForm.name.trim()) { ElMessage.warning(t('skill.nameRequired')); return }
  const params = parseJson(editForm.params, t('skill.params'))
  if (params === undefined) return
  if (editForm.scriptLanguage !== 'NONE' && !editForm.scriptCode.trim()) {
    ElMessage.warning(t('skill.scriptCodeRequired')); return
  }
  saving.value = true
  try {
    const payload = {
      name: editForm.name.trim(),
      description: editForm.description,
      category: editForm.category,
      tags: editForm.tags.split(/[,，]/).map(t => t.trim()).filter(Boolean),
      prompt: editForm.prompt,
      params: params || [],
      scriptLanguage: editForm.scriptLanguage,
      scriptCode: editForm.scriptCode,
      scriptTimeoutMs: editForm.scriptTimeoutMs,
      toolIds: editForm.toolIds,
      knowledgeBaseIds: editForm.kbIds
    }
    if (editForm.id) {
      await http.put(`/skills/${editForm.id}`, payload)
    } else {
      await http.post('/skills', payload)
    }
    ElMessage.success(t('skill.saved'))
    editVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

function openTest(row: any) {
  Object.assign(testForm, { id: row.id, name: row.name, input: '', answer: '', toolCalls: [], usage: null })
  testVisible.value = true
}

async function runTest() {
  if (!testForm.input.trim()) { ElMessage.warning(t('skill.inputRequired')); return }
  testing.value = true
  try {
    const res = await http.post(`/skills/${testForm.id}/test`, { input: testForm.input }) as any
    testForm.answer = res?.answer || ''
    testForm.toolCalls = res?.toolCalls || []
    testForm.usage = res?.usage || null
  } finally {
    testing.value = false
  }
}

async function publish(row: any) {
  const res = await http.post(`/skills/${row.id}/publish`) as any
  ElMessage.success(`${t('skill.published')} ${res.version}`)
}

async function openVersions(row: any) {
  versionSkillId.value = row.id
  versions.value = await http.get(`/skills/${row.id}/versions`) as any
  versionsVisible.value = true
}

async function rollback(version: string) {
  await ElMessageBox.confirm(`${t('skill.rollbackConfirm')}${version}${t('skill.rollbackSuffix')}`, t('skill.rollbackTitle'), { type: 'warning' })
  await http.post(`/skills/${versionSkillId.value}/versions/${version}/rollback`)
  ElMessage.success(t('skill.rolledBack'))
  versionsVisible.value = false
  await load()
}

async function remove(row: any) {
  const refs = await http.get(`/skills/${row.id}/references`) as any
  const agents: string[] = refs?.agents || []
  const extra = agents.length
    ? `${t('skill.refsPrefix')}${agents.length}${t('skill.refsSuffix')}${agents.join('、')}${t('skill.refsSuffix2')}`
    : t('skill.refsNone')
  await ElMessageBox.confirm(`${t('skill.deleteConfirm')}${row.name}${t('skill.deleteSuffix')}${extra}`, t('skill.deleteTitle'),
    { type: 'warning', confirmButtonText: t('skill.delete'), cancelButtonText: t('skill.cancel') })
  const res = await http.delete(`/skills/${row.id}`) as any
  ElMessage.success(res?.softDeleted ? t('skill.softDeleted') : t('skill.deleted'))
  await load()
}
</script>

<style scoped>
.page { background: #fff; border: 1px solid #e4e7ed; border-radius: 6px; padding: 14px; }
.toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.title { font-weight: 600; font-size: 15px; white-space: nowrap; flex-shrink: 0; }
.sub { color: #909399; font-size: 12px; margin-left: 8px; }
.spacer { flex: 1; }
.deps { color: #606266; font-size: 12px; }
.hint { color: #909399; font-size: 12px; margin-left: 8px; }
.mb12 { margin-bottom: 12px; }
.mt8 { margin-top: 8px; }
.test-result { margin-top: 12px; border: 1px solid #ebeef5; border-radius: 6px; padding: 10px; }
.test-label { font-size: 12px; color: #909399; margin-bottom: 4px; }
.test-output { white-space: pre-wrap; font-size: 13px; line-height: 1.6; }
.test-tool { font-size: 12px; color: #606266; }
.ml8 { margin-left: 8px; }
.code-area :deep(textarea) {
  font-family: Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  line-height: 1.55;
}
.script-test { width: 100%; }
.script-output {
  margin: 8px 0 0;
  padding: 8px 10px;
  background: #f5f7fa;
  border-radius: 6px;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  line-height: 1.6;
  max-height: 220px;
  overflow: auto;
}
</style>