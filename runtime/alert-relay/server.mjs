import http from 'node:http'
import crypto from 'node:crypto'

/**
 * Alertmanager → 群机器人中继（零依赖）：
 * Alertmanager 只负责把告警 POST 到这里；中继独立于后端运行，
 * 因此后端宕机（AiAgentBackendDown）时告警仍能送达。
 * 支持钉钉（可选 secret query 加签）/企微/飞书 text 消息。
 */
const PORT = Number(process.env.PORT || 8080)
const WEBHOOK_URL = process.env.ALERT_WEBHOOK_URL || ''
const WEBHOOK_SECRET = process.env.ALERT_WEBHOOK_SECRET || ''
const ENV = process.env.ALERT_WEBHOOK_ENV || 'prod'
const MAX_BODY = 1024 * 1024

function alertText(payload, alert) {
  const labels = alert?.labels || {}
  const annotations = alert?.annotations || {}
  const status = String(alert?.status || payload?.status || 'firing')
  const name = String(labels.alertname || 'unknown')
  const lines = [`【AI Agent 平台·${ENV}】[${status}] ${name}`]
  const fields = [
    ['status', status],
    ['severity', labels.severity],
    ['instance', labels.instance],
    ['summary', annotations.summary],
    ['description', annotations.description]
  ]
  for (const [key, value] of fields) {
    if (value != null && String(value).trim() !== '') lines.push(`${key}=${value}`)
  }
  return lines.join('\n')
}

function dingtalkSign(timestamp, secret) {
  const text = `${timestamp}\n${secret}`
  const digest = crypto.createHmac('sha256', secret).update(text).digest('base64')
  return encodeURIComponent(digest)
}

function targetUrl(url) {
  if (!WEBHOOK_SECRET || !url.toLowerCase().includes('dingtalk')) return url
  const ts = Date.now()
  const sep = url.includes('?') ? '&' : '?'
  return `${url}${sep}timestamp=${ts}&sign=${dingtalkSign(ts, WEBHOOK_SECRET)}`
}

async function sendText(text) {
  if (!WEBHOOK_URL) return false
  const body = JSON.stringify({ msgtype: 'text', text: { content: text } })
  const res = await fetch(targetUrl(WEBHOOK_URL), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
    signal: AbortSignal.timeout(5000)
  })
  const raw = await res.text()
  if (!res.ok) return false
  if (!raw.trim()) return true
  try {
    const parsed = JSON.parse(raw)
    if (Object.hasOwn(parsed, 'errcode')) return Number(parsed.errcode) === 0
    if (Object.hasOwn(parsed, 'code')) return Number(parsed.code) === 0
    return true
  } catch {
    return true
  }
}

const server = http.createServer(async (req, res) => {
  const send = (status, payload) => {
    res.writeHead(status, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(payload))
  }
  const url = new URL(req.url || '/', 'http://localhost')
  if (url.pathname === '/healthz') {
    res.writeHead(200, { 'Content-Type': 'text/plain' })
    res.end('ok')
    return
  }
  if (url.pathname !== '/alertmanager' || req.method !== 'POST') {
    send(404, { error: 'not found' })
    return
  }
  try {
    const chunks = []
    let size = 0
    for await (const chunk of req) {
      size += chunk.length
      if (size > MAX_BODY) {
        send(413, { error: 'payload too large' })
        return
      }
      chunks.push(chunk)
    }
    const payload = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')
    const alerts = Array.isArray(payload.alerts) ? payload.alerts : []
    if (!WEBHOOK_URL) {
      send(200, { alerts: alerts.length, sent: 0, configured: false })
      return
    }
    let sent = 0
    for (const alert of alerts) {
      if (await sendText(alertText(payload, alert))) sent++
    }
    // 有投递失败时返回 5xx，让 Alertmanager 重试；部分成功可能造成重复，优先保证不丢告警。
    send(sent === alerts.length ? 200 : 502, { alerts: alerts.length, sent, configured: true })
  } catch (error) {
    console.error('alert relay failed:', error.message)
    send(502, { error: 'relay failed' })
  }
})

server.listen(PORT, '0.0.0.0', () => {
  console.log(JSON.stringify({ msg: 'alert-relay started', port: PORT, configured: Boolean(WEBHOOK_URL) }))
})

for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => server.close(() => process.exit(0)))
}
