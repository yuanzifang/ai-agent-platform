<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">技能管理</span>
      <span class="sub">内置技能只读；自定义技能含提示词与工具/知识库依赖，Agent 引用后运行时注入</span>
      <div class="spacer" />
      <el-input v-model="keyword" placeholder="搜索名称/描述" clearable style="width: 190px"
                @keyup.enter="load" @clear="load" />
      <el-select v-model="type" placeholder="类型" clearable style="width: 110px" @change="load">
        <el-option label="内置" value="BUILTIN" />
        <el-option label="自定义" value="CUSTOM" />
      </el-select>
      <el-button @click="load">搜索</el-button>
      <el-button type="primary" :disabled="!canManage" @click="openCreate">新增技能</el-button>
    </div>

    <el-alert v-if="!canManage" type="info" :closable="false" show-icon class="mb12"
              title="只读模式：新增/验证技能需要 Owner 或管理员权限（skill:manage）" />

    <el-table :data="list" size="small" v-loading="loading">
      <template #empty>
        <el-empty description="暂无技能">
          <el-button type="primary" :disabled="!canManage" @click="openCreate">新增技能</el-button>
        </el-empty>
      </template>
      <el-table-column label="名称" width="200">
        <template #default="{ row }">
          {{ row.name }}
          <el-tag v-if="row.isBuiltin" size="small" type="info">内置</el-tag>
          <el-tag v-if="row.hasScript" size="small" type="warning" class="ml8">
            {{ row.scriptLanguage === 'PYTHON' ? 'Python 脚本' : 'JS 脚本' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="category" label="分类" width="90" />
      <el-table-column prop="description" label="描述" min-width="240" show-overflow-tooltip />
      <el-table-column label="依赖" width="160">
        <template #default="{ row }">
          <span class="deps">{{ depText(row.dependencies) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">
            {{ row.status === 'ENABLED' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="270">
        <template #default="{ row }">
          <el-button size="small" text :disabled="!canManage" @click="openTest(row)">验证</el-button>
          <template v-if="canManage && !row.isBuiltin">
            <el-button size="small" text @click="openEdit(row)">编辑</el-button>
            <el-button size="small" text @click="publish(row)">发布</el-button>
            <el-button size="small" text @click="openVersions(row)">版本</el-button>
            <el-button size="small" text type="danger" @click="remove(row)">删除</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建/编辑 -->
    <el-dialog v-model="editVisible" :title="editForm.id ? '编辑技能' : '新建技能'" width="780px">
      <el-form label-width="90px">
        <el-form-item label="名称" required>
          <el-input v-model="editForm.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" maxlength="512" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="editForm.category" placeholder="如：内容 / 分析" style="width: 220px" />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="editForm.tags" placeholder="逗号分隔" />
        </el-form-item>
        <el-form-item label="提示词">
          <el-input v-model="editForm.prompt" type="textarea" :rows="5"
                    placeholder="技能行为说明，运行时注入到 Agent 系统提示词" />
        </el-form-item>
        <el-form-item label="参数定义">
          <el-input v-model="editForm.params" type="textarea" :rows="3"
                    placeholder='JSON 数组，如 [{"key":"lang","type":"string","defaultValue":"英文"}]' />
        </el-form-item>
        <el-form-item label="脚本">
          <el-select v-model="editForm.scriptLanguage" style="width: 200px">
            <el-option label="不启用" value="NONE" />
            <el-option label="Python 3" value="PYTHON" />
            <el-option label="JavaScript (Node)" value="JAVASCRIPT" />
          </el-select>
          <span class="hint">启用后作为工具 skill_&lt;id&gt; 供 Agent 调用；stdout 即结果</span>
        </el-form-item>
        <template v-if="editForm.scriptLanguage !== 'NONE'">
          <el-alert type="warning" :closable="false" show-icon class="mb12"
                    title="脚本在服务器本机进程执行（有超时/输出上限），请只运行可信代码；生产环境建议使用隔离执行器。" />
          <el-form-item label="脚本代码">
            <el-input v-model="editForm.scriptCode" type="textarea" :rows="10" class="code-area"
                      :placeholder="scriptPlaceholder" />
          </el-form-item>
          <el-form-item label="执行超时">
            <el-input-number v-model="editForm.scriptTimeoutMs" :min="500" :max="60000" :step="500" />
            <span class="hint">ms；脚本内用 params 读取参数，print(结果) 输出</span>
          </el-form-item>
          <el-form-item label="脚本测试">
            <div class="script-test">
              <el-input v-model="scriptArgs" type="textarea" :rows="2"
                        placeholder='测试参数 JSON，如 {"text":"hello"}' />
              <div class="mt8">
                <el-button type="primary" plain :loading="scriptTesting" @click="testScript">运行脚本</el-button>
                <span class="hint">只执行脚本，不调用模型，不需要先保存</span>
              </div>
              <pre v-if="scriptOutput" class="script-output">{{ scriptOutput }}</pre>
            </div>
          </el-form-item>
        </template>
        <el-form-item label="依赖工具">
          <el-select v-model="editForm.toolIds" multiple filterable style="width: 100%"
                     placeholder="可选：该技能自动启用的工具">
            <el-option v-for="t in toolOptions" :key="t.id" :value="t.id"
                       :label="t.name + (t.isBuiltin ? '（内置）' : '')" />
          </el-select>
        </el-form-item>
        <el-form-item label="依赖知识库">
          <el-select v-model="editForm.kbIds" multiple filterable style="width: 100%"
                     placeholder="可选：该技能自动检索的知识库">
            <el-option v-for="k in kbOptions" :key="k.id" :value="k.id" :label="k.name" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 测试 -->
    <el-dialog v-model="testVisible" :title="`验证技能：${testForm.name}`" width="640px">
      <el-input v-model="testForm.input" type="textarea" :rows="3" placeholder="输入验证内容" />
      <div class="mt8">
        <el-button type="primary" :loading="testing" @click="runTest">开始验证</el-button>
        <span class="hint">验证使用当前配置与模型，不写入正式 Trace</span>
      </div>
      <div v-if="testForm.answer" class="test-result">
        <div class="test-label">输出</div>
        <div class="test-output">{{ testForm.answer }}</div>
        <div v-if="testForm.toolCalls.length" class="test-label mt8">工具调用</div>
        <div v-for="(t, i) in testForm.toolCalls" :key="i" class="test-tool">
          ⚙ {{ t.displayName || t.toolName }} · {{ t.status === 'ERROR' ? '失败' : '成功' }} · {{ t.durationMs }}ms
        </div>
        <div class="hint mt8" v-if="testForm.usage">
          输入 {{ testForm.usage.inputTokens }} / 输出 {{ testForm.usage.outputTokens }} tokens
        </div>
      </div>
    </el-dialog>

    <!-- 版本 -->
    <el-drawer v-model="versionsVisible" title="版本历史" size="420px">
      <el-table :data="versions" size="small">
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column prop="createdAt" label="时间" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="rollback(row.version)">回滚</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="versions.length === 0" description="暂无版本，请先发布" :image-size="60" />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import { useUserStore } from '../stores/user'

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
    else ElMessage.warning('当前账号无新增技能权限')
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
  return [tools ? `工具 ${tools}` : '', kbs ? `知识库 ${kbs}` : ''].filter(Boolean).join(' / ') || '-'
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
  if (!editForm.scriptCode.trim()) { ElMessage.warning('请先填写脚本代码'); return }
  let args: any = {}
  if (scriptArgs.value.trim()) {
    try { args = JSON.parse(scriptArgs.value) } catch { ElMessage.error('测试参数不是合法 JSON'); return }
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
    scriptOutput.value = `输出（${res.durationMs}ms）\n${res.output || '（无输出）'}`
      + (res.stderr ? `\n[stderr]\n${res.stderr}` : '')
      + (res.truncated ? '\n…（输出已截断）' : '')
  } finally {
    scriptTesting.value = false
  }
}

function parseJson(text: string, label: string) {
  if (!text || !text.trim()) return null
  try {
    return JSON.parse(text)
  } catch {
    ElMessage.error(`${label} 不是合法 JSON`)
    return undefined
  }
}

async function save() {
  if (!editForm.name.trim()) { ElMessage.warning('名称必填'); return }
  const params = parseJson(editForm.params, '参数定义')
  if (params === undefined) return
  if (editForm.scriptLanguage !== 'NONE' && !editForm.scriptCode.trim()) {
    ElMessage.warning('启用脚本时请填写脚本代码'); return
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
    ElMessage.success('已保存')
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
  if (!testForm.input.trim()) { ElMessage.warning('请输入测试内容'); return }
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
  ElMessage.success(`已发布 ${res.version}`)
}

async function openVersions(row: any) {
  versionSkillId.value = row.id
  versions.value = await http.get(`/skills/${row.id}/versions`) as any
  versionsVisible.value = true
}

async function rollback(version: string) {
  await ElMessageBox.confirm(`回滚到 ${version} 将生成新版本，继续？`, '回滚确认', { type: 'warning' })
  await http.post(`/skills/${versionSkillId.value}/versions/${version}/rollback`)
  ElMessage.success('已回滚')
  versionsVisible.value = false
  await load()
}

async function remove(row: any) {
  const refs = await http.get(`/skills/${row.id}/references`) as any
  const agents: string[] = refs?.agents || []
  const extra = agents.length
    ? `该技能被 ${agents.length} 个 Agent 引用（${agents.join('、')}），将软删除并在 Agent 中显示「已失效」。`
    : '该技能未被引用，将永久删除。'
  await ElMessageBox.confirm(`确认删除技能「${row.name}」？${extra}`, '删除技能',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  const res = await http.delete(`/skills/${row.id}`) as any
  ElMessage.success(res?.softDeleted ? '已软删除（Agent 中显示已失效）' : '已删除')
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
