import http from 'node:http'
import crypto from 'node:crypto'
import nodemailer from 'nodemailer'

/**
 * Alertmanager → 多通道告警中继（零业务依赖，邮件使用 nodemailer）：
 * - webhook：钉钉/企微/飞书，支持按 URL/type 识别与钉钉加签
 * - email：SMTP（复用平台 SMTP_* 配置）
 * - aliyun-sms：阿里云短信 RPC 签名
 * - 按 severity 路由 critical/warning/default
 * - critical 告警可按延迟升级到额外通道；resolved 会取消升级
 *
 * 该进程独立于后端，因此 AiAgentBackendDown 时仍可发送告警。
 */
const PORT = Number(process.env.PORT || 8080)
const MAX_BODY = 1024 * 1024
const ENV = process.env.ALERT_WEBHOOK_ENV || 'prod'

function bool(name, fallback = false) {
  const v = process.env[name]
  if (v == null || v === '') return fallback
  return ['1', 'true', 'yes', 'on'].includes(String(v).toLowerCase())
}
function csv(name, fallback) {
  const raw = process.env[name]
  return String(raw == null || raw === '' ? fallback : raw)
    .split(',').map(s => s.trim().toLowerCase()).filter(Boolean)
}
function trimBase(value) {
  return String(value || '').replace(/\/+$/, '')
}

const WEBHOOK_URL = process.env.ALERT_WEBHOOK_URL || ''
const WEBHOOK_SECRET = process.env.ALERT_WEBHOOK_SECRET || ''
const WEBHOOK_TYPE = (process.env.ALERT_WEBHOOK_TYPE || 'auto').toLowerCase()

const EMAIL_ENABLED = bool('ALERT_EMAIL_ENABLED')
const EMAIL_FROM = process.env.ALERT_EMAIL_FROM || process.env.SMTP_USERNAME || ''
const EMAIL_TO = process.env.ALERT_EMAIL_TO || ''
const SMTP_HOST = process.env.SMTP_HOST || ''
const SMTP_PORT = Number(process.env.SMTP_PORT || 587)
const SMTP_USERNAME = process.env.SMTP_USERNAME || ''
const SMTP_PASSWORD = process.env.SMTP_PASSWORD || ''
const SMTP_SECURE = bool('SMTP_SECURE', SMTP_PORT === 465)
const SMTP_REQUIRE_TLS = bool('SMTP_TLS', false)

const SMS_ENABLED = bool('ALERT_SMS_ENABLED')
const SMS_ACCESS_KEY_ID = process.env.ALERT_SMS_ACCESS_KEY_ID || ''
const SMS_ACCESS_KEY_SECRET = process.env.ALERT_SMS_ACCESS_KEY_SECRET || ''
const SMS_ENDPOINT = process.env.ALERT_SMS_ENDPOINT || 'https://dysmsapi.aliyuncs.com/'
const SMS_SIGN_NAME = process.env.ALERT_SMS_SIGN_NAME || ''
const SMS_TEMPLATE_CODE = process.env.ALERT_SMS_TEMPLATE_CODE || ''
const SMS_PHONE_NUMBERS = process.env.ALERT_SMS_PHONE_NUMBERS || ''
const SMS_REGION = process.env.ALERT_SMS_REGION_ID || 'cn-hangzhou'

const DEFAULT_CHANNELS = csv('ALERT_RELAY_DEFAULT_CHANNELS', 'webhook')
const WARNING_CHANNELS = csv('ALERT_RELAY_WARNING_CHANNELS', 'webhook,email')
const CRITICAL_CHANNELS = csv('ALERT_RELAY_CRITICAL_CHANNELS', 'webhook,email')
const ESCALATION_CHANNELS = csv('ALERT_ESCALATION_CHANNELS', 'sms')
const ESCALATION_SEVERITIES = csv('ALERT_ESCALATION_SEVERITIES', 'critical')
const ESCALATION_DELAY_SECONDS = Math.max(0, Number(process.env.ALERT_ESCALATION_DELAY_SECONDS || 0))
const escalationTimers = new Map()
const escalated = new Set()

let mailTransport

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

function feishuSign(timestamp, secret) {
  const key = `${timestamp}\n${secret}`
  return crypto.createHmac('sha256', key).update('').digest('base64')
}

function webhookKind() {
  if (WEBHOOK_TYPE !== 'auto') {
    const type = WEBHOOK_TYPE.toUpperCase()
    if (['DINGTALK', 'WECOM', 'FEISHU'].includes(type)) return type
  }
  const u = WEBHOOK_URL.toLowerCase()
  if (u.includes('open.feishu.cn') || u.includes('open.larksuite.com') || u.includes('feishu')) return 'FEISHU'
  if (u.includes('qyapi.weixin.qq.com') || u.includes('weixin') || u.includes('wecom')) return 'WECOM'
  return 'DINGTALK'
}

function webhookBody(text) {
  const kind = webhookKind()
  if (kind === 'FEISHU') {
    const body = { msg_type: 'text', content: { text } }
    if (WEBHOOK_SECRET) {
      const ts = Math.floor(Date.now() / 1000)
      body.timestamp = String(ts)
      body.sign = feishuSign(ts, WEBHOOK_SECRET)
    }
    return body
  }
  return { msgtype: 'text', text: { content: text } }
}

function webhookTarget() {
  if (!WEBHOOK_SECRET || webhookKind() !== 'DINGTALK') return WEBHOOK_URL
  const ts = Date.now()
  const sep = WEBHOOK_URL.includes('?') ? '&' : '?'
  return `${WEBHOOK_URL}${sep}timestamp=${ts}&sign=${dingtalkSign(ts, WEBHOOK_SECRET)}`
}

async function sendWebhook(alert, text) {
  if (!WEBHOOK_URL) return false
  const res = await fetch(webhookTarget(), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(webhookBody(text)),
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

function emailEnabled() {
  return EMAIL_ENABLED && Boolean(EMAIL_FROM && EMAIL_TO && SMTP_HOST)
}

function emailTransport() {
  if (!mailTransport) {
    mailTransport = nodemailer.createTransport({
      host: SMTP_HOST,
      port: SMTP_PORT,
      secure: SMTP_SECURE,
      requireTLS: SMTP_REQUIRE_TLS,
      auth: SMTP_USERNAME ? { user: SMTP_USERNAME, pass: SMTP_PASSWORD } : undefined,
      connectionTimeout: 5000,
      greetingTimeout: 5000,
      socketTimeout: 8000
    })
  }
  return mailTransport
}

async function sendEmail(alert, text) {
  if (!emailEnabled()) return false
  try {
    const labels = alert?.labels || {}
    const status = String(alert?.status || 'firing')
    await emailTransport().sendMail({
      from: EMAIL_FROM,
      to: EMAIL_TO.split(/[,;]/).map(s => s.trim()).filter(Boolean),
      subject: `[${status}] ${labels.alertname || 'AI Agent 平台告警'}`,
      text
    })
    return true
  } catch (error) {
    console.error('alert relay email failed:', error.message)
    return false
  }
}

function percentEncode(value) {
  return encodeURIComponent(String(value))
    .replace(/[!'()*]/g, c => '%' + c.charCodeAt(0).toString(16).toUpperCase())
}

function smsEnabled() {
  return SMS_ENABLED && Boolean(SMS_ACCESS_KEY_ID && SMS_ACCESS_KEY_SECRET
    && SMS_SIGN_NAME && SMS_TEMPLATE_CODE && SMS_PHONE_NUMBERS)
}

async function sendSms(alert, text) {
  if (!smsEnabled()) return false
  try {
    const labels = alert?.labels || {}
    const content = `[${alert?.status || 'firing'}] ${labels.alertname || '告警'}\n${text}`.slice(0, 500)
    const params = {
      AccessKeyId: SMS_ACCESS_KEY_ID,
      Action: 'SendSms',
      Format: 'JSON',
      PhoneNumbers: SMS_PHONE_NUMBERS,
      RegionId: SMS_REGION,
      SignName: SMS_SIGN_NAME,
      SignatureMethod: 'HMAC-SHA1',
      SignatureNonce: crypto.randomUUID(),
      SignatureVersion: '1.0',
      TemplateCode: SMS_TEMPLATE_CODE,
      TemplateParam: JSON.stringify({ content }),
      Timestamp: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
      Version: '2017-05-25'
    }
    const canonical = Object.keys(params).sort()
      .map(k => `${percentEncode(k)}=${percentEncode(params[k])}`).join('&')
    const stringToSign = `GET&%2F&${percentEncode(canonical)}`
    const signature = crypto.createHmac('sha1', `${SMS_ACCESS_KEY_SECRET}&`)
      .update(stringToSign).digest('base64')
    const endpoint = SMS_ENDPOINT.startsWith('http') ? SMS_ENDPOINT : `https://${SMS_ENDPOINT}`
    const sep = endpoint.includes('?') ? '&' : '?'
    const url = `${trimBase(endpoint)}${sep}${canonical}&Signature=${percentEncode(signature)}`
    const res = await fetch(url, { signal: AbortSignal.timeout(5000) })
    const raw = await res.text()
    if (!res.ok) return false
    try {
      return JSON.parse(raw)?.Code === 'OK'
    } catch {
      return false
    }
  } catch (error) {
    console.error('alert relay sms failed:', error.message)
    return false
  }
}

function channelEnabled(name) {
  if (name === 'webhook') return Boolean(WEBHOOK_URL)
  if (name === 'email') return emailEnabled()
  if (name === 'aliyun-sms' || name === 'sms') return smsEnabled()
  return false
}

function normalizeChannel(name) {
  return name === 'sms' ? 'aliyun-sms' : name
}

function severityChannels(alert, route) {
  const severity = String(alert?.labels?.severity || '').toLowerCase()
  if (route === 'critical' || severity === 'critical') return CRITICAL_CHANNELS
  if (route === 'warning' || severity === 'warning') return WARNING_CHANNELS
  return DEFAULT_CHANNELS
}

function alertKey(alert) {
  if (alert?.fingerprint) return String(alert.fingerprint)
  const labels = alert?.labels || {}
  return `${labels.alertname || 'unknown'}|${labels.instance || ''}|${alert?.startsAt || ''}`
}

function scheduleEscalation(alert, text) {
  if (ESCALATION_DELAY_SECONDS <= 0) return
  const severity = String(alert?.labels?.severity || '').toLowerCase()
  if (!ESCALATION_SEVERITIES.includes(severity)) return
  const key = alertKey(alert)
  if (escalationTimers.has(key) || escalated.has(key)) return
  const timer = setTimeout(async () => {
    escalationTimers.delete(key)
    escalated.add(key)
    const results = await sendChannels(alert, text, ESCALATION_CHANNELS)
    console.log(JSON.stringify({ msg: 'alert escalated', key, severity, results }))
  }, ESCALATION_DELAY_SECONDS * 1000)
  timer.unref?.()
  escalationTimers.set(key, timer)
}

function cancelEscalation(alert) {
  const key = alertKey(alert)
  const timer = escalationTimers.get(key)
  if (timer) clearTimeout(timer)
  escalationTimers.delete(key)
  escalated.delete(key)
}

async function sendChannels(alert, text, channels) {
  const results = {}
  for (const raw of channels) {
    const channel = normalizeChannel(raw)
    if (!channelEnabled(channel)) continue
    let ok = false
    if (channel === 'webhook') ok = await sendWebhook(alert, text)
    else if (channel === 'email') ok = await sendEmail(alert, text)
    else if (channel === 'aliyun-sms') ok = await sendSms(alert, text)
    results[channel] = ok
  }
  return results
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
    const route = String(url.searchParams.get('route') || '').toLowerCase()
    const configured = ['webhook', 'email', 'aliyun-sms'].some(channelEnabled)
    if (!configured) {
      send(200, { alerts: alerts.length, sent: 0, configured: false, channels: {} })
      return
    }
    let sentAlerts = 0
    const channelTotals = {}
    for (const alert of alerts) {
      const text = alertText(payload, alert)
      const status = String(alert?.status || payload?.status || 'firing').toLowerCase()
      if (status === 'resolved') cancelEscalation(alert)
      const results = await sendChannels(alert, text, severityChannels(alert, route))
      for (const [channel, ok] of Object.entries(results)) {
        channelTotals[channel] = (channelTotals[channel] || 0) + (ok ? 1 : 0)
      }
      if (Object.values(results).some(Boolean)) sentAlerts++
      if (status !== 'resolved') scheduleEscalation(alert, text)
    }
    // 只有“每条告警至少一个通道成功”才返回 2xx；部分通道失败不重试，避免重复投递。
    send(sentAlerts === alerts.length ? 200 : 502, {
      alerts: alerts.length,
      sent: sentAlerts,
      configured: true,
      channels: channelTotals
    })
  } catch (error) {
    console.error('alert relay failed:', error.message)
    send(502, { error: 'relay failed' })
  }
})

server.listen(PORT, '0.0.0.0', () => {
  console.log(JSON.stringify({
    msg: 'alert-relay started',
    port: PORT,
    configured: ['webhook', 'email', 'aliyun-sms'].some(channelEnabled),
    routes: { critical: CRITICAL_CHANNELS, warning: WARNING_CHANNELS, default: DEFAULT_CHANNELS },
    escalation: { seconds: ESCALATION_DELAY_SECONDS, channels: ESCALATION_CHANNELS }
  }))
})

for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => {
    for (const timer of escalationTimers.values()) clearTimeout(timer)
    server.close(() => process.exit(0))
  })
}
