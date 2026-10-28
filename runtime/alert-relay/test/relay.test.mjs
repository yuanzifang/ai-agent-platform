import test from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import net from 'node:net'
import crypto from 'node:crypto'
import { spawn } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const serverPath = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../server.mjs')

async function freePort() {
  return new Promise((resolve, reject) => {
    const socket = net.createServer()
    socket.once('error', reject)
    socket.listen(0, '127.0.0.1', () => {
      const port = socket.address().port
      socket.close(() => resolve(port))
    })
  })
}

async function startRelay(env = {}) {
  const port = await freePort()
  let output = ''
  const child = spawn(process.execPath, [serverPath], {
    env: { ...process.env, PORT: String(port), ALERT_WEBHOOK_ENV: 'test', ...env },
    stdio: ['ignore', 'pipe', 'pipe']
  })
  child.stdout.on('data', c => { output += c })
  child.stderr.on('data', c => { output += c })
  for (let i = 0; i < 80; i++) {
    if (child.exitCode != null) throw new Error(`relay exited\n${output}`)
    try {
      if ((await fetch(`http://127.0.0.1:${port}/healthz`)).ok) return { port, child }
    } catch { /* retry */ }
    await new Promise(r => setTimeout(r, 50))
  }
  throw new Error(`relay not ready\n${output}`)
}

async function startSmtp() {
  const messages = []
  const server = net.createServer(socket => {
    let buffer = ''
    let data = ''
    let inData = false
    socket.write('220 localhost ESMTP\r\n')
    socket.on('data', chunk => {
      buffer += chunk.toString('utf8')
      while (true) {
        const idx = buffer.indexOf('\r\n')
        if (idx < 0) break
        const line = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        if (inData) {
          if (line === '.') {
            messages.push(data)
            data = ''
            inData = false
            socket.write('250 OK\r\n')
          } else {
            data += line + '\n'
          }
          continue
        }
        const cmd = line.split(' ')[0].toUpperCase()
        if (cmd === 'EHLO' || cmd === 'HELO') socket.write('250-localhost\r\n250 OK\r\n')
        else if (cmd === 'MAIL' || cmd === 'RCPT' || cmd === 'RSET' || cmd === 'NOOP') socket.write('250 OK\r\n')
        else if (cmd === 'DATA') { inData = true; socket.write('354 End data with <CR><LF>.<CR><LF>\r\n') }
        else if (cmd === 'QUIT') { socket.write('221 Bye\r\n'); socket.end() }
        else socket.write('250 OK\r\n')
      }
    })
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  return { server, port: server.address().port, messages }
}

async function startReceiver(response = '{"errcode":0}') {
  const requests = []
  const server = http.createServer((req, res) => {
    let body = ''
    req.on('data', c => { body += c })
    req.on('end', () => {
      requests.push({ url: req.url, body })
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(response)
    })
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  return { server, port: server.address().port, requests }
}

const payload = {
  status: 'firing',
  alerts: [{
    status: 'firing',
    labels: { alertname: 'AiAgentBackendDown', severity: 'critical', instance: 'backend:8080' },
    annotations: { summary: '后端不可用', description: '连续 2 分钟抓取失败' }
  }]
}

test('中继 Alertmanager 文本并投递群机器人', async (t) => {
  const receiver = await startReceiver()
  const relay = await startRelay({ ALERT_WEBHOOK_URL: `http://127.0.0.1:${receiver.port}/hook` })
  t.after(() => { relay.child.kill('SIGTERM'); receiver.server.close() })

  const res = await fetch(`http://127.0.0.1:${relay.port}/alertmanager`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  })
  assert.equal(res.status, 200)
  assert.equal((await res.json()).sent, 1)
  assert.match(receiver.requests[0].body, /\[firing\] AiAgentBackendDown/)
  assert.match(receiver.requests[0].body, /severity=critical/)
  assert.match(receiver.requests[0].body, /summary=后端不可用/)
})

test('钉钉 secret 放 query 且 body 不泄漏签名', async (t) => {
  const receiver = await startReceiver()
  const relay = await startRelay({
    ALERT_WEBHOOK_URL: `http://127.0.0.1:${receiver.port}/dingtalk/robot/send?access_token=x`,
    ALERT_WEBHOOK_SECRET: 'secret'
  })
  t.after(() => { relay.child.kill('SIGTERM'); receiver.server.close() })

  await fetch(`http://127.0.0.1:${relay.port}/alertmanager`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  })
  assert.match(receiver.requests[0].url, /timestamp=/)
  assert.match(receiver.requests[0].url, /sign=/)
  assert.doesNotMatch(receiver.requests[0].body, /sign/)
})

test('未配置 webhook 时返回 configured=false，不抛错', async (t) => {
  const relay = await startRelay()
  t.after(() => relay.child.kill('SIGTERM'))
  const res = await fetch(`http://127.0.0.1:${relay.port}/alertmanager`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  })
  const body = await res.json()
  assert.equal(res.status, 200)
  assert.equal(body.configured, false)
  assert.equal(body.sent, 0)
})

test('群机器人返回非 0 错误码时返回 502 供 Alertmanager 重试', async (t) => {
  const receiver = await startReceiver('{"code":19001}')
  const relay = await startRelay({ ALERT_WEBHOOK_URL: `http://127.0.0.1:${receiver.port}/hook` })
  t.after(() => { relay.child.kill('SIGTERM'); receiver.server.close() })
  const res = await fetch(`http://127.0.0.1:${relay.port}/alertmanager`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  })
  const body = await res.json()
  assert.equal(res.status, 502)
  assert.equal(body.sent, 0)
})


test('critical 告警路由到短信通道', async (t) => {
  const receiver = await startReceiver('{"Code":"OK"}')
  const relay = await startRelay({
    ALERT_SMS_ENABLED: 'true',
    ALERT_SMS_ACCESS_KEY_ID: 'ak',
    ALERT_SMS_ACCESS_KEY_SECRET: 'sk',
    ALERT_SMS_ENDPOINT: `http://127.0.0.1:${receiver.port}/sms`,
    ALERT_SMS_SIGN_NAME: 'sign',
    ALERT_SMS_TEMPLATE_CODE: 'tpl',
    ALERT_SMS_PHONE_NUMBERS: '13800000000',
    ALERT_RELAY_CRITICAL_CHANNELS: 'aliyun-sms',
    ALERT_RELAY_WARNING_CHANNELS: 'webhook',
    ALERT_WEBHOOK_URL: ''
  })
  t.after(() => { relay.child.kill('SIGTERM'); receiver.server.close() })

  const res = await fetch(`http://127.0.0.1:${relay.port}/alertmanager?route=critical`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  })
  const body = await res.json()
  assert.equal(res.status, 200)
  assert.equal(body.channels['aliyun-sms'], 1)
  assert.match(receiver.requests[0].url, /Action=SendSms/)
  assert.match(receiver.requests[0].url, /PhoneNumbers=13800000000/)
})

test('邮件通道通过 SMTP 发送', async (t) => {
  const smtp = await startSmtp()
  const relay = await startRelay({
    ALERT_EMAIL_ENABLED: 'true',
    ALERT_EMAIL_FROM: 'alert@example.test',
    ALERT_EMAIL_TO: 'ops@example.test',
    SMTP_HOST: '127.0.0.1',
    SMTP_PORT: String(smtp.port),
    SMTP_SECURE: 'false',
    SMTP_TLS: 'false',
    ALERT_WEBHOOK_URL: '',
    ALERT_RELAY_CRITICAL_CHANNELS: 'email'
  })
  t.after(() => { relay.child.kill('SIGTERM'); smtp.server.close() })

  const res = await fetch(`http://127.0.0.1:${relay.port}/alertmanager?route=critical`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  })
  const body = await res.json()
  assert.equal(res.status, 200)
  assert.equal(body.channels.email, 1)
  assert.equal(smtp.messages.length, 1)
  assert.match(smtp.messages[0], /Subject: \[firing\] AiAgentBackendDown/)
  assert.match(smtp.messages[0], /status=3Dfiring/)
})

test('critical 超时后升级到额外通道，resolved 可取消', async (t) => {
  const receiver = await startReceiver()
  const relay = await startRelay({
    ALERT_WEBHOOK_URL: `http://127.0.0.1:${receiver.port}/hook`,
    ALERT_RELAY_CRITICAL_CHANNELS: 'webhook',
    ALERT_ESCALATION_CHANNELS: 'webhook',
    ALERT_ESCALATION_SEVERITIES: 'critical',
    ALERT_ESCALATION_DELAY_SECONDS: '0.15'
  })
  t.after(() => { relay.child.kill('SIGTERM'); receiver.server.close() })

  await fetch(`http://127.0.0.1:${relay.port}/alertmanager?route=critical`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
  })
  assert.equal(receiver.requests.length, 1)
  await new Promise(r => setTimeout(r, 350))
  assert.equal(receiver.requests.length, 2, '超时后应多投递一次升级通道')
})
