<template>
  <div class="chat-page">
    <!-- 左侧：会话列表 -->
    <div class="sessions">
      <el-button type="primary" class="new-btn" @click="newConversation">＋ 新会话</el-button>
      <div class="session-list">
        <div v-for="s in sessions" :key="s.id" class="session-item"
             :class="{ active: s.id === conversationId }" @click="openConversation(s.id)">
          <div class="s-row">
            <span v-if="s.pinned" class="s-pin">📌</span>
            <div class="s-title">{{ s.title || '新会话' }}</div>
            <el-dropdown trigger="click" @command="(cmd: string) => sessionCommand(cmd, s)" @click.stop>
              <span class="s-more" @click.stop>⋯</span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="rename">重命名</el-dropdown-item>
                  <el-dropdown-item command="pin">{{ s.pinned ? '取消置顶' : '置顶' }}</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
          <div class="s-sub">{{ formatTime(s.lastActiveAt) }}</div>
        </div>
        <el-empty v-if="sessions.length === 0" description="暂无会话" :image-size="60" />
      </div>
    </div>

    <!-- 右侧：消息区 -->
    <div class="chat-main" @dragover.prevent="dragging = true" @dragleave.prevent="dragging = false"
         @drop.prevent="onDrop">
      <div v-if="dragging" class="drop-mask">松开以上传附件</div>
      <div class="chat-head">
        <span class="agent-name">{{ agentName }}</span>
        <el-button size="small" :disabled="messages.length === 0" @click="share">分享</el-button>
      </div>

      <div class="messages" ref="msgBox">
        <div v-if="openingStatement && messages.length === 0" class="opening">
          <div class="msg assistant">
            <div class="bubble"><MarkdownContent :content="openingStatement" /></div>
          </div>
          <div class="suggestions">
            <span class="suggestions-label">猜你想问</span>
            <el-button v-for="q in suggestedQuestions" :key="q" size="small" round
                       :disabled="streaming" @click="askSuggestion(q)">{{ q }}</el-button>
          </div>
        </div>
        <div v-for="m in messages" :key="m.id ?? 'pending'" class="msg" :class="m.role.toLowerCase()">
          <div class="bubble">
            <MarkdownContent v-if="m.role === 'ASSISTANT'" :content="m.content" />
            <div v-else class="content">{{ m.content }}</div>
            <span v-if="m.streaming" class="cursor">▍</span>
            <div v-if="(m.tools || []).length" class="tool-steps">
              <div v-for="(t, i) in m.tools" :key="i" class="tool-step" :class="toolStatusClass(t.status)">
                <span class="tool-dot" />
                <div class="tool-body">
                  <div class="tool-title">
                    <span>{{ t.subAgentName || t.name }}</span>
                    <el-tag size="small" effect="plain" class="tool-type">{{ toolTypeText(t.toolType) }}</el-tag>
                  </div>
                  <div class="tool-meta">
                    <template v-if="t.status === 'RUNNING'">执行中…</template>
                    <template v-else>
                      {{ toolStatusText(t.status) }}<span v-if="t.durationMs"> · {{ t.durationMs }}ms</span>
                    </template>
                  </div>
                </div>
              </div>
            </div>
            <div v-if="(m.attachments || []).length" class="msg-attachments">
              <span v-for="(a, i) in m.attachments" :key="i" class="attach-chip">📎 {{ a.fileName }}</span>
            </div>
            <div v-if="m.status === 'STOPPED'" class="stopped-tag">已停止</div>
            <div v-if="m.status === 'BLOCKED'" class="blocked-tag">已按安全策略拦截</div>
            <div v-if="(m.citations || []).length" class="citations">
              <el-collapse>
                <el-collapse-item :title="`引用来源 ${m.citations?.length ?? 0} 条`">
                  <div v-for="(c, i) in m.citations" :key="i" class="citation">
                    <div class="c-head">
                      <span class="score">{{ (c.score * 100).toFixed(0) }}%</span>
                      <span class="c-doc">{{ c.documentName || c.knowledgeBaseName || '知识片段' }}</span>
                      <el-button v-if="c.knowledgeBaseId" text size="small"
                                 @click="openCitation(c)">查看原文</el-button>
                    </div>
                    <div class="c-text">{{ c.content }}</div>
                  </div>
                </el-collapse-item>
              </el-collapse>
            </div>
            <div v-if="m.role === 'USER' && !m.streaming" class="msg-ops user-ops">
              <el-button size="small" text @click="editResend(m)">编辑重发</el-button>
            </div>
            <div v-if="m.role === 'ASSISTANT' && m.id && !m.streaming" class="msg-ops">
              <el-button size="small" text :type="m.feedback === 'UP' ? 'primary' : ''" @click="feedback(m, 'UP')">👍</el-button>
              <el-button size="small" text :type="m.feedback === 'DOWN' ? 'danger' : ''" @click="feedback(m, 'DOWN')">👎</el-button>
              <el-button size="small" text @click="copyMessage(m)">复制</el-button>
              <el-button v-if="isLastAssistant(m)" size="small" text @click="regenerate(m)">重新生成</el-button>
            </div>
          </div>
        </div>
      </div>

      <div class="input-bar">
        <div v-if="attachments.length" class="attach-chips">
          <el-tag v-for="(a, i) in attachments" :key="a.attachmentId" closable @close="attachments.splice(i, 1)">
            <img v-if="a.previewUrl" :src="a.previewUrl" class="attach-preview" alt="" />
            <span v-else>📎</span> {{ a.fileName }}
          </el-tag>
        </div>
        <div class="write-gate">
          <el-checkbox v-model="allowWriteTools" size="small">允许写操作工具（本次）</el-checkbox>
          <span class="hint">默认阻断 POST/PUT/DELETE、MCP 写工具与脚本；仅本次消息生效</span>
        </div>
        <div class="input-row">
          <el-button circle :type="recording ? (recordCancel ? 'info' : 'danger') : ''"
                     :title="recording ? '松开结束，移出按钮取消' : '按住说话'"
                     @mousedown="startRecord($event)" @mouseup="stopRecord($event)"
                     @mouseleave="recording && stopRecord($event)">
            <el-icon><Microphone /></el-icon>
          </el-button>
          <label class="attach-btn" title="附件">
            <el-icon><Paperclip /></el-icon>
            <input type="file" hidden @change="uploadAttachment" />
          </label>
          <el-input v-model="input" type="textarea" :rows="2" resize="none" placeholder="输入消息，Enter 发送（Shift+Enter 换行）"
                    @keydown.enter.exact.prevent="send" @paste="onPaste" :disabled="streaming" />
          <el-button v-if="streaming" type="warning" @click="stopGen">停止</el-button>
          <el-button v-else type="primary" :disabled="!input.trim() || !conversationId" @click="send">发送</el-button>
        </div>
        <div v-if="recording" class="recording-bar" :class="{ cancel: recordCancel }">
          <div class="wave">
            <span v-for="(h, i) in volumeBars" :key="i" :style="{ height: h + 'px' }" />
          </div>
          <span>{{ recordCancel ? '松开取消录音' : '录音中…松开转文字（移出按钮取消）' }}</span>
        </div>
      </div>
    </div>

    <el-dialog v-model="variablesVisible" title="填写对话变量" width="520px">
      <div class="hint mb8">该 Agent 配置了必填变量，填写后开始对话。</div>
      <el-form label-width="130px">
        <el-form-item v-for="def in variableDefs" :key="def.key" :required="def.required === true || String(def.required) === 'true'"
                      :label="variableLabel(def)">
          <el-input v-model="variableValues[def.key]"
                    :placeholder="def.description || def.defaultValue || ''" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="variablesVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmVariables">开始对话</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="shareVisible" title="分享只读链接" width="480px">
      <el-input :model-value="shareUrl" readonly>
        <template #append>
          <el-button @click="copyShare">复制</el-button>
        </template>
      </el-input>
      <div class="share-tip">链接 7 天内有效，打开可只读本会话（需后台 /share/{token} 页面或直接调接口）。</div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Microphone, Paperclip } from '@element-plus/icons-vue'
import http from '../api/http'
import MarkdownContent from '../components/MarkdownContent.vue'
import { useUserStore } from '../stores/user'

interface SessionItem { id: number; agentId: number; agentName: string; title: string; lastActiveAt: string; pinned?: boolean }
interface ChatMessage {
  id?: number; role: string; content: string; status?: string; feedback?: string | null
  citations?: any[]; tools?: any[]; streaming?: boolean; attachments?: any[]
  traceId?: string
}
interface Attachment { attachmentId: number; fileName: string; previewUrl?: string }

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const agentId = Number(route.params.agentId)
const agentName = ref('')
const agentStreamEnabled = ref(true)
const openingStatement = ref('')
const sessions = ref<SessionItem[]>([])
const conversationId = ref<number | null>(null)
const messages = ref<ChatMessage[]>([])
const input = ref('')
const attachments = ref<Attachment[]>([])
const streaming = ref(false)
const recording = ref(false)
const shareVisible = ref(false)
const shareUrl = ref('')
const msgBox = ref<HTMLElement>()
const variableDefs = ref<any[]>([])
const variableValues = ref<Record<string, string>>({})
const variablesVisible = ref(false)
const pendingSend = ref<{ text: string } | null>(null)
const allowWriteTools = ref(false)
const dragging = ref(false)
const volumeBars = ref<number[]>(Array.from({ length: 18 }, () => 3))
const recordCancel = ref(false)
let recorder: MediaRecorder | null = null
let audioChunks: Blob[] = []
let aborter: AbortController | null = null
let recordStartX = 0
let recordStartY = 0
let userStopped = false
let audioContext: AudioContext | null = null
let rafId = 0

const suggestedQuestions = computed(() => {
  const text = openingStatement.value || ''
  const questions = (text.match(/[^。！？!?\n]{6,60}[？?]/g) || []).map(s => s.trim())
  if (questions.length) return questions.slice(0, 3)
  return [`${agentName.value}能做什么？`, '给我一个使用示例', '使用前需要注意什么？']
})

watch(input, (value) => {
  if (conversationId.value) localStorage.setItem(`chat:draft:${conversationId.value}`, value)
})

onMounted(async () => {
  window.addEventListener('mousemove', onRecordMove)
  const agents = await http.get('/agent', { params: { pageSize: 100 } }) as any
  const agent = (agents.list || []).find((a: any) => a.id === agentId)
  agentName.value = agent?.name || '对话'
  // 能力配置：关闭流式输出时走非流式 JSON
  const detail = await http.get(`/agent/${agentId}`) as any
  agentStreamEnabled.value = detail?.agentCapabilities?.streamOutput !== false
  variableDefs.value = Array.isArray(detail?.variables) ? detail.variables : []
  for (const def of variableDefs.value) {
    if (def?.key && def.defaultValue != null && variableValues.value[def.key] == null) {
      variableValues.value[def.key] = String(def.defaultValue)
    }
  }
  await loadSessions()
  if (sessions.value.length > 0) {
    await openConversation(sessions.value.find(s => s.agentId === agentId)?.id ?? sessions.value[0].id)
  } else {
    // 新 Agent 首次进入：自动创建会话，避免输入框/发送按钮不可用
    await newConversation()
  }
})

onUnmounted(() => {
  window.removeEventListener('mousemove', onRecordMove)
  cancelAnimationFrame(rafId)
  if (audioContext) audioContext.close().catch(() => {})
})

async function loadSessions() {
  const data = await http.get('/conversations', { params: { pageSize: 50 } }) as any
  sessions.value = (data.list || []).filter((s: SessionItem) => s.agentId === agentId)
}

async function newConversation() {
  const data = await http.post('/conversations', { agentId }) as any
  await loadSessions()
  await openConversation(data.id)
}

async function openConversation(id: number) {
  if (streaming.value) stopGen()
  conversationId.value = id
  // 恢复草稿时不要覆盖用户已开始输入的内容
  const draft = localStorage.getItem(`chat:draft:${id}`) || ''
  if (!input.value) input.value = draft
  attachments.value = []
  messages.value = []
  const detail = await http.get(`/conversations/${id}`) as any
  openingStatement.value = detail.openingStatement || ''
  // 加载期间用户可能已发送消息：本地已有消息时不覆盖（避免清掉流式回答）
  if (messages.value.length === 0) {
    messages.value = (detail.messages || []).map((m: any) => ({
      id: m.id, role: m.role, content: m.content, status: m.status,
      feedback: m.feedback, citations: [], attachments: m.attachments || []
    }))
  }
  scrollToBottom()
}

function requiredVariableMissing(def: any): boolean {
  const required = def?.required === true || String(def?.required) === 'true'
  if (!required || !def?.key) return false
  const value = variableValues.value[def.key] ?? def.defaultValue
  return value == null || String(value).trim() === ''
}

function missingVariables(): any[] {
  return variableDefs.value.filter(requiredVariableMissing)
}

function variableLabel(def: any): string {
  return def?.label || def?.name || def?.key || ''
}

async function send() {
  const text = input.value.trim()
  if (!text || !conversationId.value || streaming.value) return
  if (missingVariables().length) {
    pendingSend.value = { text }
    variablesVisible.value = true
    return
  }
  await doSend(text)
}

async function doSend(text: string) {
  if (!conversationId.value) return
  input.value = ''
  localStorage.removeItem(`chat:draft:${conversationId.value}`)
  const attachmentIds = attachments.value.map(a => a.attachmentId)
  attachments.value = []
  messages.value.push({ role: 'USER', content: text })
  const pending: ChatMessage = { role: 'ASSISTANT', content: '', streaming: true, citations: [] }
  messages.value.push(pending)
  await streamCall(`/conversations/${conversationId.value}/messages`, {
    message: text, stream: agentStreamEnabled.value, attachmentIds,
    variables: { ...variableValues.value },
    allowWriteTools: allowWriteTools.value
  }, pending)
  allowWriteTools.value = false
}

function confirmVariables() {
  const missing = missingVariables()
  if (missing.length) {
    ElMessage.warning(`请填写必填变量：${missing.map(variableLabel).join('、')}`)
    return
  }
  const payload = pendingSend.value
  pendingSend.value = null
  variablesVisible.value = false
  if (payload) doSend(payload.text)
}

async function editResend(m: ChatMessage) {
  try {
    const { value } = await ElMessageBox.prompt('编辑后将在新会话中发送（保留原对话，形成分支）',
      '编辑重发', { inputValue: m.content, confirmButtonText: '发送', cancelButtonText: '取消' })
    if (!value?.trim()) return
    const data = await http.post('/conversations', {
      agentId, title: `分支：${value.trim().slice(0, 30)}`
    }) as any
    await loadSessions()
    await openConversation(data.id)
    await doSend(value.trim())
  } catch { /* 取消 */ }
}

function askSuggestion(question: string) {
  if (streaming.value) return
  input.value = question
  send()
}

async function regenerate(m: ChatMessage) {
  if (!conversationId.value || !m.id || streaming.value) return
  const pending: ChatMessage = { role: 'ASSISTANT', content: '', streaming: true, citations: [] }
  messages.value.push(pending)
  await streamCall(`/conversations/${conversationId.value}/messages/${m.id}/regenerate`, {}, pending)
}

/** SSE：fetch POST + 手动解析 event 流（EventSource 不支持 POST/鉴权头）。 */
async function streamCall(url: string, body: any, pending: ChatMessage) {
  streaming.value = true
  userStopped = false
  aborter = new AbortController()
  try {
    const resp = await fetch('/api/v1' + url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${userStore.accessToken}` },
      body: JSON.stringify(body),
      signal: aborter.signal
    })
    if (!resp.ok || !resp.body) {
      const errText = await resp.text().catch(() => '')
      throw new Error(`请求失败 ${resp.status} ${errText.slice(0, 200)}`)
    }
    // 非流式分支（Agent 关闭流式输出 / stream=false）：直接消费 JSON
    const contentType = resp.headers.get('content-type') || ''
    if (contentType.includes('application/json')) {
      const json = await resp.json()
      if (json.code !== 0) throw new Error(json.message || '发送失败')
      const data = json.data || {}
      pending.content = data.answer || ''
      ;(pending.citations ||= []).push(...(data.citations || []))
      if (data.messageId) pending.id = data.messageId
      return
    }
    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let receivedDone = false
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const blocks = buffer.split('\n\n')
      buffer = blocks.pop() || ''
      for (const block of blocks) {
        let event = 'message', data = ''
        for (const line of block.split('\n')) {
          if (line.startsWith('event:')) event = line.slice(6).trim()
          else if (line.startsWith('data:')) data += line.slice(5).trim()
        }
        if (!data) continue
        if (event === 'done') receivedDone = true
        handleEvent(event, JSON.parse(data), pending)
      }
    }
    if (!receivedDone && !aborter?.signal.aborted && !userStopped) {
      const resumed = await resumeStream(pending)
      if (!resumed) await recoverStream()
    }
  } catch (e: any) {
    if (e.name !== 'AbortError' && !userStopped) {
      ElMessage.warning('连接中断，正在续传…')
      const resumed = await resumeStream(pending)
      if (!resumed) await recoverStream()
    }
  } finally {
    pending.streaming = false
    streaming.value = false
    scrollToBottom()
  }
}

function handleEvent(event: string, data: any, pending: ChatMessage) {
  if (event === 'delta' && data.content) {
    pending.content += data.content
    scrollToBottom()
  } else if (event === 'citation') {
    (pending.citations ||= []).push(data)
  } else if (event === 'tool_start') {
    upsertTool(pending, data, true)
    scrollToBottom()
  } else if (event === 'tool') {
    upsertTool(pending, data, false)
    scrollToBottom()
  } else if (event === 'moderation') {
    ElMessage.warning(data.tip || '内容审核提示')
  } else if (event === 'meta') {
    if (data.traceId) pending.traceId = data.traceId
  } else if (event === 'usage') {
    // usage 汇总在 done 里，这里忽略
  } else if (event === 'done') {
    if (data.messageId) pending.id = data.messageId
    if (data.status === 'STOPPED' || data.status === 'BLOCKED') pending.status = data.status
    loadSessions()
  } else if (event === 'error') {
    ElMessage.error(data.message || '生成失败')
  }
}

function upsertTool(pending: ChatMessage, data: any, running: boolean) {
  const tools = (pending.tools ||= [])
  const key = data.toolName || data.name
  const idx = tools.findIndex((t: any) => (t.toolName || t.name) === key && (running || t.status === 'RUNNING'))
  if (idx >= 0) {
    tools[idx] = { ...tools[idx], ...data, status: running ? 'RUNNING' : (data.status || 'SUCCESS') }
  } else {
    tools.push({ ...data, status: data.status || (running ? 'RUNNING' : 'SUCCESS') })
  }
}

function toolTypeText(type?: string) {
  const map: Record<string, string> = {
    RAG: '知识库', API: 'API', MCP: 'MCP', SKILL: '技能',
    BUILTIN: '内置', SUB_AGENT: '子 Agent'
  }
  return map[type || ''] || (type || '工具')
}

function toolStatusText(status?: string) {
  if (status === 'RUNNING') return '执行中'
  if (status === 'ERROR') return '失败'
  if (status === 'CANCELLED') return '已取消'
  return '完成'
}

function toolStatusClass(status?: string) {
  if (status === 'RUNNING') return 'running'
  if (status === 'ERROR') return 'error'
  if (status === 'CANCELLED') return 'cancelled'
  return 'success'
}

/** 真正续传：按已收到的字符 offset 拉取 Redis 快照的剩余增量，直到 done。 */
async function resumeStream(pending: ChatMessage): Promise<boolean> {
  if (!conversationId.value) return false
  aborter = new AbortController()
  try {
    const offset = (pending.content || '').length
    const resp = await fetch(`/api/v1/conversations/${conversationId.value}/resume?offset=${offset}`, {
      headers: { Authorization: `Bearer ${userStore.accessToken}` },
      signal: aborter.signal
    })
    if (!resp.ok || !resp.body) return false
    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let gotDone = false
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const blocks = buffer.split('\n\n')
      buffer = blocks.pop() || ''
      for (const block of blocks) {
        let event = 'message', data = ''
        for (const line of block.split('\n')) {
          if (line.startsWith('event:')) event = line.slice(6).trim()
          else if (line.startsWith('data:')) data += line.slice(5).trim()
        }
        if (!data) continue
        if (event === 'done') gotDone = true
        handleEvent(event, JSON.parse(data), pending)
      }
    }
    return gotDone
  } catch (e: any) {
    if (e.name !== 'AbortError') console.warn('续传失败', e?.message)
    return false
  }
}

/** 兜底恢复：续传不可用时重新拉取已持久化消息。 */
async function recoverStream() {
  if (!conversationId.value) return
  for (let attempt = 0; attempt < 2; attempt++) {
    await new Promise(resolve => setTimeout(resolve, 600 + attempt * 600))
    try {
      const detail = await http.get(`/conversations/${conversationId.value}`) as any
      const rows = detail.messages || []
      const hasAssistant = rows.some((m: any) => m.role === 'ASSISTANT')
      if (hasAssistant) {
        messages.value = rows.map((m: any) => ({
          id: m.id, role: m.role, content: m.content, status: m.status,
          feedback: m.feedback, citations: [], attachments: m.attachments || []
        }))
        ElMessage.info('连接中断，已恢复已生成内容')
        await loadSessions()
        scrollToBottom()
        return
      }
    } catch (e: any) {
      console.warn('断线恢复失败', e?.message)
    }
  }
  ElMessage.error('连接中断，请重新发送或刷新页面')
}

async function copyMessage(m: ChatMessage) {
  try {
    await navigator.clipboard.writeText(m.content || '')
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败')
  }
}

function openCitation(c: any) {
  if (!c?.knowledgeBaseId) return
  router.push({ path: `/knowledge/${c.knowledgeBaseId}`,
    query: { doc: c.documentId || '', chunk: c.chunkId || '' } })
}

async function sessionCommand(command: string, session: SessionItem) {
  if (command === 'rename') {
    try {
      const { value } = await ElMessageBox.prompt('输入新的会话名称', '重命名会话',
        { inputValue: session.title, confirmButtonText: '保存', cancelButtonText: '取消' })
      if (!value?.trim()) return
      await http.put(`/conversations/${session.id}`, { title: value.trim() })
      await loadSessions()
      ElMessage.success('已重命名')
    } catch { /* 取消 */ }
  } else if (command === 'pin') {
    await http.put(`/conversations/${session.id}`, { pinned: !session.pinned })
    await loadSessions()
  }
}

async function stopGen() {
  userStopped = true
  aborter?.abort()
  const last = [...messages.value].reverse().find(m => m.role === 'ASSISTANT' && m.streaming)
  if (conversationId.value && last?.id) {
    await http.post(`/conversations/${conversationId.value}/messages/${last.id}/stop`, {}).catch(() => {})
  }
}

async function feedback(m: ChatMessage, type: string) {
  if (!conversationId.value || !m.id) return
  await http.post(`/conversations/${conversationId.value}/messages/${m.id}/feedback`, { type })
  // 后端 upsert：重复提交同类型 = 取消，不同类型 = 切换
  m.feedback = m.feedback === type ? null : type
  if (m.feedback) ElMessage.success(m.feedback === 'UP' ? '已点赞' : '已点踩')
  else ElMessage.info('已取消反馈')
}

async function share() {
  const data = await http.post(`/conversations/${conversationId.value}/share`, {}) as any
  shareUrl.value = `${location.origin}/share/${data.shareToken}`
  shareVisible.value = true
}

function copyShare() {
  navigator.clipboard.writeText(shareUrl.value)
  ElMessage.success('已复制')
}

async function uploadFile(file: File) {
  if (!file || !conversationId.value) return
  if (file.size > 20 * 1024 * 1024) {
    ElMessage.warning('附件不能超过 20MB')
    return
  }
  const fd = new FormData()
  fd.append('file', file)
  const data = await http.post(`/conversations/${conversationId.value}/attachments`, fd, {
    headers: { 'Content-Type': 'multipart/form-data' }
  }) as any
  const previewUrl = file.type.startsWith('image/') ? URL.createObjectURL(file) : undefined
  attachments.value.push({ attachmentId: data.attachmentId, fileName: data.fileName, previewUrl })
  ElMessage.success(`附件已上传：${data.fileName}`)
}

async function uploadAttachment(e: Event) {
  const inputEl = e.target as HTMLInputElement
  const file = inputEl.files?.[0]
  if (file) await uploadFile(file)
  inputEl.value = ''
}

function onDrop(e: DragEvent) {
  dragging.value = false
  const files = e.dataTransfer?.files
  if (!files?.length) return
  for (const file of Array.from(files)) uploadFile(file)
}

function onPaste(e: ClipboardEvent) {
  const items = e.clipboardData?.items
  if (!items) return
  for (const item of Array.from(items)) {
    if (!item.type.startsWith('image/')) continue
    const file = item.getAsFile()
    if (file) {
      e.preventDefault()
      uploadFile(file)
      break
    }
  }
}

// ---------- 录音转文字（本地 Qwen3-ASR） ----------
async function startRecord(event: MouseEvent) {
  if (recording.value || streaming.value) return
  recordStartX = event.clientX
  recordStartY = event.clientY
  recordCancel.value = false
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    recorder = new MediaRecorder(stream, { mimeType: 'audio/webm' })
    audioChunks = []
    recorder.ondataavailable = (e) => audioChunks.push(e.data)
    recorder.onstop = async () => {
      stream.getTracks().forEach(t => t.stop())
      cancelAnimationFrame(rafId)
      if (audioContext) { audioContext.close().catch(() => {}); audioContext = null }
      volumeBars.value = Array.from({ length: 18 }, () => 3)
      if (recordCancel.value) {
        audioChunks = []
        ElMessage.info('已取消录音')
        return
      }
      await transcribe()
    }
    // 音量波形
    audioContext = new AudioContext()
    const source = audioContext.createMediaStreamSource(stream)
    const analyser = audioContext.createAnalyser()
    analyser.fftSize = 64
    source.connect(analyser)
    const data = new Uint8Array(analyser.frequencyBinCount)
    const tick = () => {
      if (!recording.value) return
      analyser.getByteFrequencyData(data)
      volumeBars.value = Array.from({ length: 18 },
        (_, i) => 3 + Math.round((data[i + 2] || 0) / 255 * 22))
      rafId = requestAnimationFrame(tick)
    }
    recorder.start()
    recording.value = true
    tick()
  } catch {
    ElMessage.error('无法访问麦克风，请检查浏览器权限')
  }
}

function onRecordMove(event: MouseEvent) {
  if (!recording.value) return
  const distance = Math.hypot(event.clientX - recordStartX, event.clientY - recordStartY)
  recordCancel.value = distance > 80
}

async function stopRecord(event?: MouseEvent) {
  if (!recording.value || !recorder) return
  if (event) onRecordMove(event)
  recording.value = false
  recorder.stop()
}

async function transcribe() {
  if (audioChunks.length === 0) return
  const blob = new Blob(audioChunks, { type: 'audio/webm' })
  if (blob.size === 0) return
  const fd = new FormData()
  fd.append('audio', blob, 'speech.webm')
  try {
    const data = await http.post('/asr/transcribe', fd, {
      headers: { 'Content-Type': 'multipart/form-data' }
    }) as any
    if (data?.text) {
      input.value = (input.value ? input.value + ' ' : '') + data.text
      ElMessage.success('语音已转文字')
    }
  } catch (e: any) {
    ElMessage.error('转写失败：' + (e.message || '未知错误'))
  }
}

function isLastAssistant(m: ChatMessage) {
  const assistants = messages.value.filter(x => x.role === 'ASSISTANT' && !x.streaming)
  return assistants.length > 0 && assistants[assistants.length - 1] === m
}

function scrollToBottom() {
  nextTick(() => {
    if (msgBox.value) msgBox.value.scrollTop = msgBox.value.scrollHeight
  })
}

function formatTime(t?: string) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(5, 16)
}

function clip(text: string, n: number) {
  text = text || ''
  return text.length > n ? text.slice(0, n) + '…' : text
}
</script>

<style scoped>
.tool-steps { display: flex; flex-direction: column; gap: 6px; margin-top: 8px; }
.tool-step {
  display: flex; gap: 8px; padding: 7px 10px;
  border: 1px solid #e4e7ed; border-radius: 6px; background: #fafbfc;
  font-size: 12px; color: #606266;
}
.tool-step.running { border-color: #c6e2ff; background: #f2f8ff; }
.tool-step.error { border-color: #fbc4c4; background: #fef2f2; }
.tool-step.cancelled { border-color: #f5dab1; background: #fdf6ec; opacity: .85; }
.tool-dot {
  width: 8px; height: 8px; border-radius: 50%; background: #67c23a;
  margin-top: 5px; flex-shrink: 0;
}
.tool-step.running .tool-dot { background: #409eff; animation: tool-pulse 1s infinite; }
.tool-step.error .tool-dot { background: #f56c6c; }
.tool-step.cancelled .tool-dot { background: #e6a23c; }
@keyframes tool-pulse { 50% { opacity: .3; } }
.tool-title { display: flex; align-items: center; gap: 6px; font-weight: 600; color: #303133; }
.tool-meta { color: #909399; margin-top: 1px; }
.chat-page {
  display: flex;
  height: 100%;
  gap: 12px;
}
.sessions {
  width: 220px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 10px;
}
.new-btn {
  margin-bottom: 10px;
}
.session-list {
  flex: 1;
  overflow-y: auto;
}
.session-item {
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  margin-bottom: 4px;
}
.session-item:hover {
  background: #f5f7fa;
}
.session-item.active {
  background: #ecf5ff;
}
.s-row { display: flex; align-items: center; gap: 4px; }
.s-pin { font-size: 11px; flex-shrink: 0; }
.s-more {
  margin-left: auto; color: #909399; cursor: pointer; padding: 0 2px;
  opacity: 0; transition: opacity .15s; flex-shrink: 0;
}
.session-item:hover .s-more { opacity: 1; }
.s-title {
  font-size: 13px; min-width: 0;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.s-sub {
  font-size: 12px;
  color: #c0c4cc;
}
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  min-width: 0;
}
.chat-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 16px;
  border-bottom: 1px solid #e4e7ed;
}
.agent-name {
  font-weight: 600;
}
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.msg {
  display: flex;
}
.msg.user {
  justify-content: flex-end;
}
.msg.assistant {
  justify-content: flex-start;
}
.bubble {
  max-width: 72%;
  padding: 10px 14px;
  border-radius: 10px;
  background: #f4f4f5;
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.msg.user .bubble {
  background: #409eff;
  color: #fff;
}
.cursor {
  animation: blink 0.8s infinite;
}
@keyframes blink {
  50% { opacity: 0; }
}
.msg-attachments {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.attach-chip {
  font-size: 12px;
  background: rgba(255, 255, 255, 0.25);
  border-radius: 4px;
  padding: 2px 6px;
}
.stopped-tag {
  margin-top: 6px;
  font-size: 12px;
  color: #e6a23c;
}
.blocked-tag {
  margin-top: 6px;
  font-size: 12px;
  color: #f56c6c;
}
.citations {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
}
.citation { padding: 6px 0; border-bottom: 1px dashed #ebeef5; }
.citation:last-child { border-bottom: 0; }
.c-head { display: flex; align-items: center; gap: 8px; margin-bottom: 3px; }
.score { color: #409eff; flex-shrink: 0; }
.c-doc {
  color: #606266; font-weight: 500; overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap; max-width: 60%;
}
.c-text { color: #909399; line-height: 1.55; white-space: pre-wrap; }
.suggestions {
  display: flex; flex-wrap: wrap; align-items: center; gap: 8px;
  margin: 10px 0 0 6px;
}
.suggestions-label { font-size: 12px; color: #909399; }
.drop-mask {
  position: absolute; inset: 0; z-index: 20;
  display: flex; align-items: center; justify-content: center;
  border: 2px dashed #409eff; border-radius: 8px;
  background: rgba(64, 158, 255, .08); color: #409eff; font-size: 16px;
}
.chat-main { position: relative; }
.attach-preview { width: 18px; height: 18px; object-fit: cover; border-radius: 3px; vertical-align: middle; }
.msg-ops {
  margin-top: 6px;
  display: flex;
  gap: 2px;
}
.msg-ops .el-button {
  padding: 4px 8px;
}
.input-bar {
  border-top: 1px solid #e4e7ed;
  padding: 10px 16px;
}
.attach-chips {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.input-row {
  display: flex;
  gap: 10px;
  align-items: flex-end;
}
.attach-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  border: 1px solid #dcdfe6;
  cursor: pointer;
  flex-shrink: 0;
}
.write-gate { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.write-gate .hint { font-size: 12px; color: #909399; }
.recording-bar {
  margin-top: 8px; display: flex; align-items: center; gap: 10px;
  font-size: 12px; color: #f56c6c;
}
.recording-bar.cancel { color: #909399; }
.wave { display: flex; align-items: center; gap: 2px; height: 26px; }
.wave span {
  width: 3px; min-height: 3px; border-radius: 2px; background: #f56c6c;
  transition: height .05s linear;
}
.recording-bar.cancel .wave span { background: #c0c4cc; }
.msg.user .msg-ops { justify-content: flex-end; }
.msg.user .msg-ops .el-button { color: rgba(255, 255, 255, .9); }
.share-tip {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
}
</style>
