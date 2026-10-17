<template>
  <div class="edit-page">
    <!-- 顶栏 -->
    <div class="topbar">
      <el-button @click="$router.push('/agents')" text>
        <el-icon><ArrowLeft /></el-icon> 返回列表
      </el-button>
      <div class="title">
        编辑 Agent
        <el-tag size="small" :type="form.status === 'PUBLISHED' ? 'success' : 'info'" class="ml8">
          {{ form.status === 'PUBLISHED' ? '已发布' : '草稿' }}
        </el-tag>
      </div>
      <div class="actions">
        <el-button @click="openVersions">版本</el-button>
        <el-button @click="evalEntry">评测</el-button>
        <el-button type="success" plain @click="publish">发布</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
    </div>

    <div class="edit-body">
      <!-- 左侧：配置面板 -->
      <div class="panels">
        <el-collapse v-model="activePanels">
          <el-collapse-item name="base">
            <template #title><span class="p-title">基本信息</span></template>
            <el-form label-width="70px">
              <el-form-item label="名称"><el-input v-model="form.name" maxlength="64" /></el-form-item>
              <el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
              <el-form-item label="分类"><el-input v-model="form.category" placeholder="如：客服" style="width: 200px" /></el-form-item>
              <el-form-item label="标签"><el-input v-model="tagsStr" placeholder="逗号分隔，如：售后,售前" /></el-form-item>
            </el-form>
          </el-collapse-item>

          <el-collapse-item name="model">
            <template #title><span class="p-title">模型选择</span></template>
            <el-form label-width="70px">
              <el-form-item label="模型" required>
                <el-select v-model="form.modelName" style="width: 320px">
                  <el-option v-for="m in models" :key="m.modelName" :value="m.modelName"
                             :label="m.displayName || m.modelName" />
                </el-select>
              </el-form-item>
              <el-form-item label="温度">
                <el-slider v-model="form.temperature" :min="0" :max="2" :step="0.1" style="width: 320px" />
              </el-form-item>
            </el-form>
          </el-collapse-item>

          <el-collapse-item name="prompt">
            <template #title><span class="p-title">提示词</span><span class="req">*</span></template>
            <el-input v-model="form.systemPrompt" type="textarea" :rows="8"
                      placeholder="系统提示词，可用 {{变量key}} 占位（见「占位变量」面板）" />
          </el-collapse-item>

          <el-collapse-item name="welcome">
            <template #title><span class="p-title">欢迎语</span></template>
            <el-input v-model="form.openingStatement" type="textarea" :rows="3"
                      placeholder="对话开始时展示给用户的第一句话，支持 {{变量key}}" />
          </el-collapse-item>

          <el-collapse-item name="capability">
            <template #title><span class="p-title">Agent 能力</span></template>
            <el-form label-width="90px">
              <el-form-item label="流式输出"><el-switch v-model="form.capabilities.streamOutput" /></el-form-item>
              <el-form-item label="函数调用"><el-switch v-model="form.capabilities.functionCall" /></el-form-item>
              <el-form-item label="多模态输入"><el-switch v-model="form.capabilities.multimodal" /></el-form-item>
            </el-form>
          </el-collapse-item>

          <el-collapse-item name="memory">
            <template #title><span class="p-title">记忆</span></template>
            <el-form label-width="90px">
              <el-form-item label="启用记忆"><el-switch v-model="form.memory.enabled" /></el-form-item>
              <el-form-item label="窗口条数">
                <el-input-number v-model="form.memory.windowSize" :min="2" :max="50" :disabled="!form.memory.enabled" />
              </el-form-item>
            </el-form>
          </el-collapse-item>

          <el-collapse-item name="knowledge">
            <template #title><span class="p-title">知识库</span>
              <el-tag size="small" class="ml8">{{ enabledKbCount ? `已绑定 ${enabledKbCount} 个` : '未绑定' }}</el-tag>
            </template>
            <div v-if="kbRows.length === 0" class="hint">暂无知识库，请先在知识库模块创建并上传文档</div>
            <div v-for="kb in kbRows" :key="kb.knowledgeBaseId" class="kb-row">
              <el-switch v-model="kb.enabled" />
              <div class="kb-name">{{ kb.name }}</div>
              <div class="kb-params" v-if="kb.enabled">
                topK <el-input-number v-model="kb.topK" :min="1" :max="20" size="small" />
                阈值 <el-input-number v-model="kb.scoreThreshold" :min="0" :max="1" :step="0.05" size="small" />
              </div>
            </div>
          </el-collapse-item>

          <el-collapse-item name="variable">
            <template #title><span class="p-title">占位变量</span>
              <el-tag size="small" class="ml8">{{ form.variables.length }} 个</el-tag>
            </template>
            <el-button size="small" @click="addVariable">＋ 添加变量</el-button>
            <el-table :data="form.variables" size="small" v-if="form.variables.length" class="mt8">
              <el-table-column label="key" width="140">
                <template #default="{ row }"><el-input v-model="row.key" placeholder="user_name" size="small" /></template>
              </el-table-column>
              <el-table-column label="类型" width="110">
                <template #default="{ row }">
                  <el-select v-model="row.type" size="small">
                    <el-option v-for="t in ['STRING','NUMBER','SELECT','FILE']" :key="t" :value="t" :label="t" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="必填" width="60">
                <template #default="{ row }"><el-checkbox v-model="row.required" /></template>
              </el-table-column>
              <el-table-column label="默认值" width="130">
                <template #default="{ row }"><el-input v-model="row.defaultValue" size="small" /></template>
              </el-table-column>
              <el-table-column label="描述">
                <template #default="{ row }"><el-input v-model="row.description" size="small" /></template>
              </el-table-column>
              <el-table-column width="60">
                <template #default="{ $index }">
                  <el-button text type="danger" size="small" @click="form.variables.splice($index, 1)">删</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-collapse-item>

          <el-collapse-item name="access">
            <template #title><span class="p-title">可见性与授权</span>
              <el-tag size="small" class="ml8">{{ form.visibility === 'PRIVATE' ? '私有' : '租户可见' }}</el-tag>
            </template>
            <el-form label-width="90px">
              <el-form-item label="可见性">
                <el-radio-group v-model="form.visibility">
                  <el-radio value="TENANT">租户内全员可见</el-radio>
                  <el-radio value="PRIVATE">私有（仅授权用户）</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item v-if="form.visibility === 'PRIVATE'" label="授权用户">
                <el-select v-model="grantedIds" multiple placeholder="选择可使用的成员" style="width: 100%">
                  <el-option v-for="m in members.filter(m => m.id !== ownerId)"
                             :key="m.id" :value="m.id"
                             :label="`${m.displayName}(${m.username})`" />
                </el-select>
              </el-form-item>
            </el-form>
          </el-collapse-item>

          <el-collapse-item name="p1" disabled>
            <template #title><span class="p-title">技能 / 工具 / 子 Agent</span>
              <el-tag size="small" type="info" class="ml8">P1 交付</el-tag>
            </template>
          </el-collapse-item>
        </el-collapse>
      </div>

      <!-- 右侧：调试 -->
      <div class="debug">
        <div class="debug-head">调试（用当前表单配置，未保存的修改也生效）</div>
        <div class="debug-msgs" ref="dbgBox">
          <div v-for="(m, i) in debugMsgs" :key="i" class="d-msg" :class="m.role.toLowerCase()">
            <div class="d-bubble">
              <div class="d-content">{{ m.content }}</div>
              <div v-if="m.citations?.length" class="d-cites">
                引用 {{ m.citations.length }} 条：
                <span v-for="(c, j) in m.citations" :key="j" class="d-cite">
                  [{{ j + 1 }}] {{ (c.score * 100).toFixed(0) }}% {{ clip(c.content, 30) }}
                </span>
              </div>
              <div v-if="m.usage" class="d-usage">
                {{ m.latencyMs }}ms · 输入 {{ m.usage.inputTokens }} / 输出 {{ m.usage.outputTokens }} tokens
              </div>
            </div>
          </div>
          <el-empty v-if="debugMsgs.length === 0" description="发一条消息试试 RAG 与提示词效果" :image-size="60" />
        </div>
        <div class="debug-input">
          <el-input v-model="debugInput" placeholder="调试消息" @keydown.enter.prevent="debugSend"
                    :disabled="debugRunning" />
          <el-button type="primary" :loading="debugRunning" @click="debugSend">发送</el-button>
        </div>
      </div>
    </div>

    <!-- 版本抽屉 -->
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
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import http from '../api/http'

const route = useRoute()
const router = useRouter()
const agentId = Number(route.params.id)

const activePanels = ref(['base', 'model', 'prompt', 'knowledge', 'variable'])
const models = ref<any[]>([])
const kbRows = ref<KbRow[]>([])
const versions = ref<any[]>([])
const members = ref<any[]>([])
const grantedIds = ref<number[]>([])
const ownerId = ref<number>(0)
const versionsVisible = ref(false)
const saving = ref(false)

const form = reactive({
  status: 'DRAFT', updatedAt: '',
  name: '', description: '', category: '', visibility: 'TENANT',
  modelName: '', temperature: 0.7,
  systemPrompt: '', openingStatement: '',
  capabilities: { streamOutput: true, functionCall: false, multimodal: false },
  memory: { enabled: true, windowSize: 10 },
  variables: [] as VariableRow[]
})
const tagsStr = ref('')
const enabledKbCount = computed(() => kbRows.value.filter(k => k.enabled).length)

interface KbRow { knowledgeBaseId: number; name: string; enabled: boolean; topK: number; scoreThreshold: number }
interface VariableRow { key: string; type: string; required: boolean; defaultValue: string; description: string }

const debugMsgs = ref<any[]>([])
const debugInput = ref('')
const debugRunning = ref(false)
const dbgBox = ref<HTMLElement>()

onMounted(async () => {
  const [detail, modelList, kbList] = await Promise.all([
    http.get(`/agent/${agentId}`) as any,
    http.get('/provider/models') as any,
    http.get('/knowledge-base', { params: { pageSize: 100 } }) as any
  ])
  form.visibility = detail.visibility || 'TENANT'
  http.get(`/agent/${agentId}/grants`).then((g: any) => { grantedIds.value = g || [] }).catch(() => {})
  http.get('/users').then((ms: any) => { members.value = ms || [] }).catch(() => {})
  http.get(`/agent/${agentId}`).then(() => {}).catch(() => {})
  models.value = (modelList || []).filter((m: any) => !m.purpose || String(m.purpose).toUpperCase() === 'CHAT')

  form.status = detail.status
  form.updatedAt = detail.updatedAt || ''
  form.name = detail.name || ''
  form.description = detail.description || ''
  form.category = detail.category || ''
  tagsStr.value = ((detail.tags as string[]) || []).join(',')
  const mc = detail.modelConfig || {}
  form.modelName = mc.modelName || ''
  form.temperature = mc.temperature ?? 0.7
  form.systemPrompt = detail.systemPrompt || ''
  form.openingStatement = detail.openingStatement || ''
  const caps = detail.agentCapabilities || {}
  form.capabilities = { streamOutput: caps.streamOutput !== false, functionCall: !!caps.functionCall, multimodal: !!caps.multimodal }
  const mem = detail.memoryConfig || {}
  form.memory = { enabled: mem.enabled !== false, windowSize: mem.windowSize ?? 10 }
  form.variables = ((detail.variables as any[]) || []).map(v => ({
    key: v.key || '', type: v.type || 'STRING', required: !!v.required,
    defaultValue: v.defaultValue ?? '', description: v.description || ''
  }))

  const bound = new Map<number, any>()
  for (const b of detail.knowledgeBases || []) bound.set(b.knowledgeBaseId, b)
  const kbListArr: any[] = Array.isArray(kbList) ? kbList : (kbList?.list || [])
  kbRows.value = kbListArr.map(kb => {
    const b = bound.get(kb.id)
    const rc = b?.retrievalConfig || {}
    return {
      knowledgeBaseId: kb.id, name: kb.name,
      enabled: b ? b.enabled !== false : false,
      topK: rc.topK ?? 5, scoreThreshold: rc.scoreThreshold ?? 0.5
    }
  })
})

function buildPayload() {
  return {
    name: form.name,
    description: form.description,
    category: form.category,
    tags: tagsStr.value.split(/[,，]/).map(t => t.trim()).filter(Boolean),
    systemPrompt: form.systemPrompt,
    openingStatement: form.openingStatement,
    agentCapabilities: { ...form.capabilities },
    memoryConfig: { ...form.memory },
    modelConfig: { modelName: form.modelName, temperature: form.temperature },
    variables: form.variables.filter(v => v.key),
    visibility: form.visibility,
    knowledgeBases: kbRows.value.filter(k => k.enabled).map(k => ({
      knowledgeBaseId: k.knowledgeBaseId, enabled: true,
      retrievalConfig: { topK: k.topK, scoreThreshold: k.scoreThreshold }
    }))
  }
}

async function save() {
  if (!form.name.trim() || !form.modelName || !form.systemPrompt.trim()) {
    ElMessage.warning('名称、模型、提示词必填')
    return
  }
  saving.value = true
  try {
    const res = await http.put(`/agent/${agentId}`, buildPayload(), {
      headers: form.updatedAt ? { 'If-Match': form.updatedAt } : {}
    }) as any
    form.updatedAt = res.updatedAt
    if (form.visibility === 'PRIVATE') {
      await http.put(`/agent/${agentId}/grants`, { userIds: grantedIds.value }).catch(() => {})
    }
    ElMessage.success('已保存')
  } finally {
    saving.value = false
  }
}

async function publish() {
  await save()
  const res = await http.post(`/agent/${agentId}/publish`) as any
  form.status = 'PUBLISHED'
  ElMessage.success(`已发布 ${res.version}`)
}

async function openVersions() {
  versions.value = await http.get(`/agent/${agentId}/versions`) as any
  versionsVisible.value = true
}

async function rollback(version: string) {
  await ElMessageBox.confirm(`回滚到 ${version} 将生成新版本，继续？`, '回滚确认', { type: 'warning' })
  await http.post(`/agent/${agentId}/versions/${version}/rollback`)
  ElMessage.success('已回滚（生成新版本），页面即将刷新')
  versionsVisible.value = false
  setTimeout(() => router.go(0), 600)
}

function addVariable() {
  form.variables.push({ key: '', type: 'STRING', required: false, defaultValue: '', description: '' })
}

async function debugSend() {
  const text = debugInput.value.trim()
  if (!text || debugRunning.value) return
  debugInput.value = ''
  debugMsgs.value.push({ role: 'USER', content: text })
  const reply = { role: 'ASSISTANT', content: '', citations: [] as any[], usage: null as any, latencyMs: 0 }
  debugMsgs.value.push(reply)
  debugRunning.value = true
  try {
    const res = await http.post('/agent/debug/chat', { config: buildPayload(), message: text }) as any
    reply.content = res.answer
    reply.citations = res.citations || []
    reply.usage = res.usage
    reply.latencyMs = res.latencyMs
  } catch (e: any) {
    reply.content = '调试失败：' + (e.message || e)
  } finally {
    debugRunning.value = false
    scrollDbg()
  }
}

function scrollDbg() {
  nextTick(() => { if (dbgBox.value) dbgBox.value.scrollTop = dbgBox.value.scrollHeight })
}

function evalEntry() {
  ElMessage.info('评测中心随 P1 交付（一次性测评/在线监控/预发布测评），入口已预留')
}

function clip(text: string, n: number) {
  text = text || ''
  return text.length > n ? text.slice(0, n) + '…' : text
}
</script>

<style scoped>
.edit-page {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.topbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 16px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
}
.title {
  font-weight: 600;
  font-size: 15px;
  flex: 1;
  display: flex;
  align-items: center;
}
.edit-body {
  flex: 1;
  display: flex;
  gap: 12px;
  margin-top: 12px;
  min-height: 0;
}
.panels {
  flex: 1.4;
  overflow-y: auto;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 0 16px 16px;
}
.p-title {
  font-weight: 600;
  font-size: 14px;
}
.req {
  color: #f56c6c;
  margin-left: 4px;
}
.ml8 {
  margin-left: 8px;
}
.mt8 {
  margin-top: 8px;
}
.hint {
  color: #909399;
  font-size: 13px;
}
.kb-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px dashed #ebeef5;
}
.kb-name {
  min-width: 140px;
  font-size: 14px;
}
.kb-params {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #606266;
}
.debug {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  min-width: 0;
}
.debug-head {
  padding: 10px 14px;
  font-weight: 600;
  border-bottom: 1px solid #e4e7ed;
  font-size: 14px;
}
.debug-msgs {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.d-msg {
  display: flex;
}
.d-msg.user {
  justify-content: flex-end;
}
.d-bubble {
  max-width: 88%;
  padding: 8px 12px;
  border-radius: 8px;
  background: #f4f4f5;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.d-msg.user .d-bubble {
  background: #409eff;
  color: #fff;
}
.d-cites {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}
.d-cite {
  display: block;
}
.d-usage {
  margin-top: 4px;
  font-size: 12px;
  color: #c0c4cc;
}
.debug-input {
  display: flex;
  gap: 8px;
  padding: 10px;
  border-top: 1px solid #e4e7ed;
}
</style>
