<template>
  <div class="widget">
    <header class="w-head">
      <div class="w-avatar">
        <img v-if="info.avatarUrl" :src="info.avatarUrl" alt="" />
        <span v-else>{{ (info.name || 'AI').slice(0, 1) }}</span>
      </div>
      <div class="w-title">
        <div class="w-name">{{ info.name || 'AI 助手' }}</div>
        <div class="w-status"><span class="dot" /> 在线</div>
      </div>
      <el-button size="small" text @click="reset">新对话</el-button>
    </header>

    <main ref="listEl" class="w-body">
      <div v-if="fatal" class="w-error">{{ fatal }}</div>
      <div v-for="(m, i) in messages" :key="i" class="w-row" :class="m.role">
        <div class="w-bubble">{{ m.content }}<span v-if="m.role === 'assistant' && sending && i === messages.length - 1" class="cursor">▍</span></div>
      </div>
    </main>

    <footer class="w-foot">
      <textarea v-model="input" rows="1" placeholder="输入问题，Enter 发送，Shift+Enter 换行"
                :disabled="!!fatal" @keydown.enter.exact.prevent="send" />
      <el-button type="primary" :loading="sending" :disabled="!!fatal || !input.trim()" @click="send">发送</el-button>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const key = String(route.query.key || '')
const info = reactive<any>({ name: '', openingStatement: '', avatarUrl: '' })
const messages = ref<{ role: 'user' | 'assistant'; content: string }[]>([])
const input = ref('')
const sending = ref(false)
const fatal = ref('')
const listEl = ref<HTMLElement>()
const sessionId = ref('')

function newSession() {
  const id = (crypto as any).randomUUID
    ? (crypto as any).randomUUID().replace(/-/g, '')
    : (Date.now().toString(36) + Math.random().toString(36).slice(2))
  sessionId.value = `w-${id}`
  localStorage.setItem(`widget_session_${key.slice(-12)}`, sessionId.value)
}

async function loadInfo() {
  if (!key) { fatal.value = '缺少挂件 Key，请在 Agent 集成中生成网页挂件链接。'; return }
  try {
    const res = await fetch('/api/v1/open/agent/info', { headers: { 'X-Api-Key': key } })
    const body = await res.json()
    if (!res.ok || body.code !== 0) throw new Error(body.message || '加载失败')
    Object.assign(info, body.data || {})
    messages.value = [{ role: 'assistant', content: info.openingStatement || `你好，我是${info.name || 'AI 助手'}，有什么可以帮你？` }]
  } catch (e: any) {
    fatal.value = e.message || '挂件配置无效'
  }
}

async function send() {
  const text = input.value.trim()
  if (!text || sending.value || fatal.value) return
  input.value = ''
  messages.value.push({ role: 'user', content: text })
  const index = messages.value.length
  messages.value.push({ role: 'assistant', content: '' })
  sending.value = true
  await scrollBottom()
  try {
    const res = await fetch('/api/v1/open/chat/completions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Api-Key': key },
      body: JSON.stringify({
        stream: true,
        session_id: sessionId.value,
        messages: [{ role: 'user', content: text }]
      })
    })
    if (!res.ok || !res.body) throw new Error(`请求失败（HTTP ${res.status}）`)
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''
      for (const line of lines) {
        const trimmed = line.trim()
        if (!trimmed.startsWith('data:')) continue
        const payload = trimmed.slice(5).trim()
        if (!payload || payload === '[DONE]') continue
        try {
          const chunk = JSON.parse(payload)
          if (chunk.error) throw new Error(chunk.error.message || '生成失败')
          const content = chunk.choices?.[0]?.delta?.content
          if (content) {
            messages.value[index].content += content
            await scrollBottom()
          }
        } catch (e: any) {
          if (e.message && !e.message.includes('JSON')) throw e
        }
      }
    }
  } catch (e: any) {
    messages.value[index].content = messages.value[index].content || `抱歉，暂时无法回复：${e.message || '服务异常'}`
  } finally {
    sending.value = false
    await scrollBottom()
  }
}

async function scrollBottom() {
  await nextTick()
  if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
}

function reset() {
  newSession()
  messages.value = [{ role: 'assistant', content: info.openingStatement || `你好，我是${info.name || 'AI 助手'}，有什么可以帮你？` }]
  input.value = ''
}

onMounted(() => {
  const saved = localStorage.getItem(`widget_session_${key.slice(-12)}`)
  if (saved) sessionId.value = saved
  else newSession()
  loadInfo()
})
</script>

<style scoped>
.widget { height: 100vh; display: flex; flex-direction: column; background: #f5f7fb; color: #1f2937; font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', sans-serif; }
.w-head { display: flex; align-items: center; gap: 10px; padding: 12px 14px; background: #fff; border-bottom: 1px solid #e5e7eb; }
.w-avatar { width: 36px; height: 36px; border-radius: 50%; background: linear-gradient(135deg, #2563eb, #7c3aed); color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 700; overflow: hidden; }
.w-avatar img { width: 100%; height: 100%; object-fit: cover; }
.w-title { flex: 1; min-width: 0; }
.w-name { font-weight: 600; font-size: 14px; }
.w-status { font-size: 12px; color: #6b7280; display: flex; align-items: center; gap: 5px; }
.dot { width: 7px; height: 7px; border-radius: 50%; background: #22c55e; display: inline-block; }
.w-body { flex: 1; overflow-y: auto; padding: 16px 14px; }
.w-row { display: flex; margin-bottom: 12px; }
.w-row.user { justify-content: flex-end; }
.w-bubble { max-width: 82%; padding: 10px 12px; border-radius: 12px; font-size: 14px; line-height: 1.65; white-space: pre-wrap; word-break: break-word; background: #fff; box-shadow: 0 1px 2px rgba(0,0,0,.05); }
.w-row.user .w-bubble { background: #2563eb; color: #fff; border-bottom-right-radius: 3px; }
.w-row.assistant .w-bubble { border-bottom-left-radius: 3px; }
.cursor { color: #2563eb; animation: blink 1s infinite; }
@keyframes blink { 50% { opacity: 0; } }
.w-foot { display: flex; gap: 8px; padding: 10px; background: #fff; border-top: 1px solid #e5e7eb; align-items: flex-end; }
.w-foot textarea { flex: 1; resize: none; border: 1px solid #d1d5db; border-radius: 8px; padding: 9px 10px; font-size: 14px; line-height: 1.4; max-height: 110px; outline: none; }
.w-foot textarea:focus { border-color: #2563eb; }
.w-error { margin: 20px; padding: 12px; border-radius: 8px; background: #fef2f2; color: #b91c1c; font-size: 13px; }
</style>
