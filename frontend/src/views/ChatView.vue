<template>
  <div class="chat-page">
    <!-- 左侧：会话列表 -->
    <div class="sessions">
      <el-button type="primary" class="new-btn" @click="newConversation">＋ 新会话</el-button>
      <div class="session-list">
        <div v-for="s in sessions" :key="s.id" class="session-item"
             :class="{ active: s.id === conversationId }" @click="openConversation(s.id)">
          <div class="s-title">{{ s.title || '新会话' }}</div>
          <div class="s-sub">{{ formatTime(s.lastActiveAt) }}</div>
        </div>
        <el-empty v-if="sessions.length === 0" description="暂无会话" :image-size="60" />
      </div>
    </div>

    <!-- 右侧：消息区 -->
    <div class="chat-main">
      <div class="chat-head">
        <span class="agent-name">{{ agentName }}</span>
        <el-button size="small" :disabled="messages.length === 0" @click="share">分享</el-button>
      </div>

      <div class="messages" ref="msgBox">
        <div v-if="openingStatement && messages.length === 0" class="msg assistant">
          <div class="bubble">{{ openingStatement }}</div>
        </div>
        <div v-for="m in messages" :key="m.id ?? 'pending'" class="msg" :class="m.role.toLowerCase()">
          <div class="bubble">
            <div class="content">{{ m.content }}<span v-if="m.streaming" class="cursor">▍</span></div>
            <div v-if="m.status === 'STOPPED'" class="stopped-tag">已停止</div>
            <div v-if="(m.citations || []).length" class="citations">
              <el-collapse>
                <el-collapse-item :title="`引用来源 ${m.citations.length} 条`">
                  <div v-for="(c, i) in m.citations" :key="i" class="citation">
                    <span class="score">{{ (c.score * 100).toFixed(0) }}%</span>
                    <span class="c-text">{{ clip(c.content, 80) }}</span>
                  </div>
                </el-collapse-item>
              </el-collapse>
            </div>
            <div v-if="m.role === 'ASSISTANT' && m.id && !m.streaming" class="msg-ops">
              <el-button size="small" text :type="m.feedback === 'UP' ? 'primary' : ''" @click="feedback(m, 'UP')">👍</el-button>
              <el-button size="small" text :type="m.feedback === 'DOWN' ? 'danger' : ''" @click="feedback(m, 'DOWN')">👎</el-button>
              <el-button v-if="isLastAssistant(m)" size="small" text @click="regenerate(m)">重新生成</el-button>
            </div>
          </div>
        </div>
      </div>

      <div class="input-bar">
        <div v-if="attachments.length" class="attach-chips">
          <el-tag v-for="(a, i) in attachments" :key="a.attachmentId" closable @close="attachments.splice(i, 1)">
            📎 {{ a.fileName }}
          </el-tag>
        </div>
        <div class="input-row">
          <el-button circle :type="recording ? 'danger' : ''" :title="recording ? '松开结束' : '按住说话'"
                     @mousedown="startRecord" @mouseup="stopRecord" @mouseleave="recording && stopRecord()">
            <el-icon><Microphone /></el-icon>
          </el-button>
          <label class="attach-btn" title="附件">
            <el-icon><Paperclip /></el-icon>
            <input type="file" hidden @change="uploadAttachment" />
          </label>
          <el-input v-model="input" type="textarea" :rows="2" resize="none" placeholder="输入消息，Enter 发送（Shift+Enter 换行）"
                    @keydown.enter.exact.prevent="send" :disabled="streaming" />
          <el-button v-if="streaming" type="warning" @click="stopGen">停止</el-button>
          <el-button v-else type="primary" :disabled="!input.trim() || !conversationId" @click="send">发送</el-button>
        </div>
        <div v-if="recording" class="recording-tip">录音中…松开鼠标结束并转文字</div>
      </div>
    </div>

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
import { nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Microphone, Paperclip } from '@element-plus/icons-vue'
import http from '../api/http'
import { useUserStore } from '../stores/user'

interface SessionItem { id: number; agentId: number; agentName: string; title: string; lastActiveAt: string }
interface ChatMessage {
  id?: number; role: string; content: string; status?: string; feedback?: string | null
  citations?: any[]; streaming?: boolean
}
interface Attachment { attachmentId: number; fileName: string }

const route = useRoute()
const userStore = useUserStore()
const agentId = Number(route.params.agentId)
const agentName = ref('')
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
let recorder: MediaRecorder | null = null
let audioChunks: Blob[] = []
let aborter: AbortController | null = null

onMounted(async () => {
  const agents = await http.get('/agent', { params: { pageSize: 100 } }) as any
  const agent = (agents.list || []).find((a: any) => a.id === agentId)
  agentName.value = agent?.name || '对话'
  await loadSessions()
  if (sessions.value.length > 0) {
    await openConversation(sessions.value.find(s => s.agentId === agentId)?.id ?? sessions.value[0].id)
  }
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
  const detail = await http.get(`/conversations/${id}`) as any
  openingStatement.value = detail.openingStatement || ''
  messages.value = (detail.messages || []).map((m: any) => ({
    id: m.id, role: m.role, content: m.content, status: m.status,
    feedback: m.feedback, citations: []
  }))
  scrollToBottom()
}

async function send() {
  const text = input.value.trim()
  if (!text || !conversationId.value || streaming.value) return
  input.value = ''
  const attachmentIds = attachments.value.map(a => a.attachmentId)
  attachments.value = []
  messages.value.push({ role: 'USER', content: text })
  const pending: ChatMessage = { role: 'ASSISTANT', content: '', streaming: true, citations: [] }
  messages.value.push(pending)
  await streamCall(`/conversations/${conversationId.value}/messages`, {
    message: text, stream: true, attachmentIds
  }, pending)
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
    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
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
        handleEvent(event, JSON.parse(data), pending)
      }
    }
  } catch (e: any) {
    if (e.name !== 'AbortError') {
      ElMessage.error(e.message || '发送失败')
      pending.content = pending.content || '（生成失败）'
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
  } else if (event === 'moderation') {
    ElMessage.warning(data.tip || '内容审核提示')
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

async function stopGen() {
  aborter?.abort()
  const last = [...messages.value].reverse().find(m => m.role === 'ASSISTANT' && m.streaming)
  if (conversationId.value && last?.id) {
    await http.post(`/conversations/${conversationId.value}/messages/${last.id}/stop`, {}).catch(() => {})
  }
}

async function feedback(m: ChatMessage, type: string) {
  if (!conversationId.value || !m.id) return
  await http.post(`/conversations/${conversationId.value}/messages/${m.id}/feedback`, { type })
  m.feedback = m.feedback === type ? null : type
  if (m.feedback) ElMessage.success(m.feedback === 'UP' ? '已点赞' : '已点踩')
  else {
    // 取消反馈再提交一次空值不支持，直接恢复展示
    m.feedback = type
  }
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

async function uploadAttachment(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file || !conversationId.value) return
  const fd = new FormData()
  fd.append('file', file)
  const data = await http.post(`/conversations/${conversationId.value}/attachments`, fd, {
    headers: { 'Content-Type': 'multipart/form-data' }
  }) as any
  attachments.value.push({ attachmentId: data.attachmentId, fileName: data.fileName })
  ElMessage.success(`附件已上传：${data.fileName}`)
  ;(e.target as HTMLInputElement).value = ''
}

// ---------- 录音转文字（本地 Qwen3-ASR） ----------
async function startRecord() {
  if (recording.value || streaming.value) return
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    recorder = new MediaRecorder(stream, { mimeType: 'audio/webm' })
    audioChunks = []
    recorder.ondataavailable = (e) => audioChunks.push(e.data)
    recorder.onstop = async () => {
      stream.getTracks().forEach(t => t.stop())
      await transcribe()
    }
    recorder.start()
    recording.value = true
  } catch {
    ElMessage.error('无法访问麦克风，请检查浏览器权限')
  }
}

async function stopRecord() {
  if (!recording.value || !recorder) return
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
.s-title {
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
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
.stopped-tag {
  margin-top: 6px;
  font-size: 12px;
  color: #e6a23c;
}
.citations {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
}
.citation {
  display: flex;
  gap: 8px;
  padding: 2px 0;
}
.score {
  color: #409eff;
  flex-shrink: 0;
}
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
.recording-tip {
  margin-top: 6px;
  font-size: 12px;
  color: #f56c6c;
}
.share-tip {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
}
</style>
