<template>
  <div class="widget">
    <header class="w-head">
      <div class="w-avatar">
        <img v-if="info.avatarUrl" :src="info.avatarUrl" alt="" />
        <span v-else>{{ (info.name || 'AI').slice(0, 1) }}</span>
      </div>
      <div class="w-title">
        <div class="w-name">{{ info.name || t('widget.defaultName') }}</div>
        <div class="w-status"><span class="dot" /> {{ t('widget.online') }}</div>
      </div>
      <el-button size="small" text @click="reset">{{ t('widget.newChat') }}</el-button>
    </header>

    <main ref="listEl" class="w-body">
      <div v-if="fatal" class="w-error">{{ fatal }}</div>
      <div v-for="(m, i) in messages" :key="i" class="w-row" :class="m.role">
        <div class="w-bubble">{{ m.content }}<span v-if="m.role === 'assistant' && sending && i === messages.length - 1" class="cursor">▍</span></div>
      </div>
    </main>

    <footer class="w-foot-wrap">
      <div v-if="attachments.length" class="w-attachments">
        <span v-for="(a, i) in attachments" :key="a.attachmentId" class="w-attach">
          <img v-if="a.previewUrl" :src="a.previewUrl" alt="" />
          <span>{{ a.fileName }}</span>
          <button type="button" class="w-remove" :title="t('widget.remove')" @click="removeAttachment(i)">×</button>
        </span>
      </div>
      <div v-if="recording || transcribing" class="w-recording">
        <span class="w-pulse" />
        {{ transcribing ? t('widget.transcribing') : t('widget.recording') }}
      </div>
      <div v-if="notice" class="w-notice">{{ notice }}</div>
      <div class="w-foot">
        <button type="button" class="w-tool" :class="{ danger: recording }"
                :title="recording ? t('widget.stopRecord') : t('widget.voice')"
                :disabled="!!fatal || sending || transcribing" @click="toggleRecord">
          <el-icon><Microphone /></el-icon>
        </button>
        <label class="w-tool" :title="t('widget.attach')">
          <el-icon><Paperclip /></el-icon>
          <input type="file" accept="image/png,image/jpeg,image/webp,image/gif" hidden
                 :disabled="!!fatal || sending || uploading" @change="uploadFile" />
        </label>
        <textarea v-model="input" rows="1" :placeholder="t('widget.inputPh')"
                  :disabled="!!fatal" @keydown.enter.exact.prevent="send" />
        <el-button type="primary" :loading="sending" :disabled="!!fatal || (!input.trim() && !attachments.length)"
                   @click="send">{{ t('widget.send') }}</el-button>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Microphone, Paperclip } from '@element-plus/icons-vue'

const { t } = useI18n()

const route = useRoute()
const key = String(route.query.key || '')
const info = reactive<any>({ name: '', openingStatement: '', avatarUrl: '' })
const messages = ref<{ role: 'user' | 'assistant'; content: string }[]>([])
const input = ref('')
const sending = ref(false)
const fatal = ref('')
const listEl = ref<HTMLElement>()
const sessionId = ref('')
const attachments = ref<{ attachmentId: number; fileName: string; previewUrl?: string }[]>([])
const uploading = ref(false)
const recording = ref(false)
const transcribing = ref(false)
const notice = ref('')
let recorder: MediaRecorder | null = null
let audioChunks: Blob[] = []
let mediaStream: MediaStream | null = null
let noticeTimer: ReturnType<typeof setTimeout> | null = null

function flash(message: string) {
  notice.value = message
  if (noticeTimer) clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => { notice.value = '' }, 5000)
}

function newSession() {
  const id = (crypto as any).randomUUID
    ? (crypto as any).randomUUID().replace(/-/g, '')
    : (Date.now().toString(36) + Math.random().toString(36).slice(2))
  sessionId.value = `w-${id}`
  localStorage.setItem(`widget_session_${key.slice(-12)}`, sessionId.value)
}

async function loadInfo() {
  if (!key) { fatal.value = t('widget.missingKey'); return }
  try {
    const res = await fetch('/api/v1/open/agent/info', { headers: { 'X-Api-Key': key } })
    const body = await res.json()
    if (!res.ok || body.code !== 0) throw new Error(body.message || t('widget.loadFail'))
    Object.assign(info, body.data || {})
    messages.value = [{ role: 'assistant', content: info.openingStatement || `${t('widget.greetingPrefix')}${info.name || t('widget.defaultName')}${t('widget.greetingSuffix')}` }]
  } catch (e: any) {
    fatal.value = e.message || t('widget.invalidConfig')
  }
}

async function uploadFile(event: Event) {
  const inputEl = event.target as HTMLInputElement
  const file = inputEl.files?.[0]
  inputEl.value = ''
  if (!file) return
  if (file.size > 12 * 1024 * 1024) {
    flash(t('widget.attachmentTooLarge'))
    return
  }
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    fd.append('session_id', sessionId.value)
    const res = await fetch('/api/v1/open/attachments', {
      method: 'POST', headers: { 'X-Api-Key': key }, body: fd
    })
    const body = await res.json()
    if (!res.ok || body.code !== 0) throw new Error(body.message || t('widget.uploadFail'))
    const data = body.data || {}
    attachments.value.push({
      attachmentId: data.attachmentId,
      fileName: data.fileName || file.name,
      previewUrl: file.type.startsWith('image/') ? URL.createObjectURL(file) : undefined
    })
    flash(t('widget.attachmentUploaded'))
  } catch (e: any) {
    flash(`${t('widget.uploadFail')}${e.message || ''}`)
  } finally {
    uploading.value = false
  }
}

function removeAttachment(index: number) {
  const item = attachments.value[index]
  if (item?.previewUrl) URL.revokeObjectURL(item.previewUrl)
  attachments.value.splice(index, 1)
}

async function toggleRecord() {
  if (transcribing.value || sending.value) return
  if (recording.value) {
    stopRecording()
    return
  }
  if (!window.isSecureContext) {
    flash(t('widget.micSecure'))
    return
  }
  if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
    flash(t('widget.micUnsupported'))
    return
  }
  try {
    mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true })
    const mime = MediaRecorder.isTypeSupported('audio/webm') ? 'audio/webm' : ''
    recorder = mime ? new MediaRecorder(mediaStream, { mimeType: mime }) : new MediaRecorder(mediaStream)
    audioChunks = []
    recorder.ondataavailable = e => { if (e.data.size > 0) audioChunks.push(e.data) }
    recorder.onstop = () => {
      mediaStream?.getTracks().forEach(track => track.stop())
      mediaStream = null
      if (audioChunks.length) void transcribe(new Blob(audioChunks, { type: 'audio/webm' }))
    }
    recorder.start()
    recording.value = true
  } catch {
    mediaStream?.getTracks().forEach(track => track.stop())
    mediaStream = null
    flash(t('widget.micDenied'))
  }
}

function stopRecording() {
  if (!recording.value) return
  recording.value = false
  try { recorder?.stop() } catch { /* ignore */ }
}

async function transcribe(blob: Blob) {
  if (!blob.size) return
  transcribing.value = true
  try {
    const fd = new FormData()
    fd.append('audio', blob, 'speech.webm')
    const res = await fetch('/api/v1/open/asr/transcribe', {
      method: 'POST', headers: { 'X-Api-Key': key }, body: fd
    })
    const body = await res.json()
    if (!res.ok || body.code !== 0) throw new Error(body.message || t('widget.voiceFail'))
    const text = String(body.data?.text || '').trim()
    if (text) {
      input.value = (input.value ? input.value + ' ' : '') + text
      flash(t('widget.speechDone'))
    } else {
      flash(t('widget.speechEmpty'))
    }
  } catch (e: any) {
    flash(`${t('widget.voiceFail')}${e.message || ''}`)
  } finally {
    transcribing.value = false
  }
}

async function send() {
  const text = input.value.trim() || (attachments.value.length ? t('widget.imagePrompt') : '')
  if (!text || sending.value || fatal.value) return
  const attachmentIds = attachments.value.map(a => a.attachmentId)
  input.value = ''
  attachments.value = []
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
        attachmentIds,
        messages: [{ role: 'user', content: text }]
      })
    })
    if (!res.ok || !res.body) throw new Error(`${t('widget.requestFail')}${res.status}）`)
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
          if (chunk.error) throw new Error(chunk.error.message || t('widget.generateFail'))
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
    messages.value[index].content = messages.value[index].content || `${t('widget.replyFailPrefix')}${e.message || t('widget.serviceError')}`
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
  stopRecording()
  attachments.value.forEach(a => { if (a.previewUrl) URL.revokeObjectURL(a.previewUrl) })
  attachments.value = []
  newSession()
  messages.value = [{ role: 'assistant', content: info.openingStatement || `${t('widget.greetingPrefix')}${info.name || t('widget.defaultName')}${t('widget.greetingSuffix')}` }]
  input.value = ''
}

onMounted(() => {
  const saved = localStorage.getItem(`widget_session_${key.slice(-12)}`)
  if (saved) sessionId.value = saved
  else newSession()
  void loadInfo()
})

onBeforeUnmount(() => {
  stopRecording()
  mediaStream?.getTracks().forEach(track => track.stop())
  attachments.value.forEach(a => { if (a.previewUrl) URL.revokeObjectURL(a.previewUrl) })
  if (noticeTimer) clearTimeout(noticeTimer)
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
.w-foot-wrap { background: #fff; border-top: 1px solid #e5e7eb; }
.w-attachments { display: flex; flex-wrap: wrap; gap: 6px; padding: 8px 10px 0; }
.w-attach { display: inline-flex; align-items: center; gap: 5px; max-width: 220px; padding: 3px 6px; border: 1px solid #dbeafe; border-radius: 6px; color: #1d4ed8; font-size: 12px; overflow: hidden; }
.w-attach img { width: 24px; height: 24px; border-radius: 4px; object-fit: cover; }
.w-attach span { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.w-remove { border: 0; background: transparent; color: #6b7280; cursor: pointer; font-size: 15px; line-height: 1; padding: 0 2px; }
.w-recording { display: flex; align-items: center; gap: 7px; padding: 7px 12px 0; color: #dc2626; font-size: 12px; }
.w-pulse { width: 7px; height: 7px; border-radius: 50%; background: #ef4444; animation: pulse 1s infinite; }
@keyframes pulse { 50% { opacity: .25; } }
.w-notice { padding: 6px 12px 0; color: #b45309; font-size: 12px; }
.w-foot { display: flex; gap: 8px; padding: 10px; align-items: flex-end; }
.w-tool { width: 34px; height: 34px; flex: 0 0 34px; border: 1px solid #d1d5db; border-radius: 8px; background: #fff; color: #4b5563; display: inline-flex; align-items: center; justify-content: center; cursor: pointer; }
.w-tool:hover { border-color: #2563eb; color: #2563eb; }
.w-tool.danger { border-color: #ef4444; color: #ef4444; }
.w-tool:disabled { opacity: .45; cursor: not-allowed; }
.w-foot textarea { flex: 1; resize: none; border: 1px solid #d1d5db; border-radius: 8px; padding: 9px 10px; font-size: 14px; line-height: 1.4; max-height: 110px; outline: none; }
.w-foot textarea:focus { border-color: #2563eb; }
.w-error { margin: 20px; padding: 12px; border-radius: 8px; background: #fef2f2; color: #b91c1c; font-size: 13px; }
@media (max-width: 480px) {
  .w-head { padding: 9px 10px; }
  .w-body { padding: 12px 10px; }
  .w-foot { gap: 5px; padding: 8px; }
  .w-tool { width: 31px; height: 31px; flex-basis: 31px; }
}
</style>
