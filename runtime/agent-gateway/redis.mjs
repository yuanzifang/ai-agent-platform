import net from 'node:net'

/**
 * 极简 Redis 客户端（RESP2，零依赖）：
 * 只用于运行时的限流/配额/用量计数，支持 redis://[:password@]host:port/db
 */
export function createRedis(url) {
  const parsed = new URL(url)
  const host = parsed.hostname
  const port = Number(parsed.port || 6379)
  const password = parsed.password ? decodeURIComponent(parsed.password) : ''
  const db = Number((parsed.pathname || '/0').replace(/^\//, '') || 0)

  let socket = null
  let connecting = null
  let ready = false
  let failed = false
  let buffer = Buffer.alloc(0)
  const pending = []

  function readReply(buf, offset) {
    const lineEnd = buf.indexOf('\r\n', offset)
    if (lineEnd < 0) return null
    const type = String.fromCharCode(buf[offset])
    const line = buf.toString('utf8', offset + 1, lineEnd)
    if (type === '+') return { value: line, used: lineEnd + 2 }
    if (type === '-') return { error: line, used: lineEnd + 2 }
    if (type === ':') return { value: Number(line), used: lineEnd + 2 }
    if (type === '$') {
      const len = Number(line)
      if (len < 0) return { value: null, used: lineEnd + 2 }
      const start = lineEnd + 2
      const end = start + len
      if (buf.length < end + 2) return null
      return { value: buf.toString('utf8', start, end), used: end + 2 }
    }
    if (type === '*') {
      const count = Number(line)
      const items = []
      let cursor = lineEnd + 2
      for (let i = 0; i < count; i++) {
        const item = readReply(buf, cursor)
        if (!item) return null
        items.push(item.error ? null : item.value)
        cursor = item.used
      }
      return { value: items, used: cursor }
    }
    return { error: `unsupported RESP type ${type}`, used: lineEnd + 2 }
  }

  function drain() {
    while (true) {
      const reply = readReply(buffer, 0)
      if (!reply) return
      buffer = buffer.subarray(reply.used)
      const waiter = pending.shift()
      if (!waiter) continue
      if (reply.error) waiter.reject(new Error(reply.error))
      else waiter.resolve(reply.value)
    }
  }

  function raw(command) {
    return new Promise((resolve, reject) => {
      const parts = [`*${command.length}\r\n`]
      for (const arg of command) {
        const text = String(arg)
        parts.push(`$${Buffer.byteLength(text)}\r\n${text}\r\n`)
      }
      pending.push({ resolve, reject })
      socket.write(parts.join(''))
    })
  }

  function connect() {
    if (connecting) return connecting
    connecting = new Promise((resolve, reject) => {
      let settled = false
      const client = net.createConnection({ host, port })
      socket = client
      client.on('data', chunk => {
        buffer = Buffer.concat([buffer, chunk])
        drain()
      })
      client.once('error', error => {
        failed = true
        ready = false
        if (!settled) {
          settled = true
          reject(error)
        }
        while (pending.length) pending.shift().reject(error)
      })
      client.on('close', () => {
        failed = true
        ready = false
        socket = null
        connecting = null
      })
      client.once('connect', async () => {
        try {
          if (password) await raw(['AUTH', password])
          if (db) await raw(['SELECT', String(db)])
          ready = true
          if (!settled) {
            settled = true
            resolve()
          }
        } catch (error) {
          failed = true
          if (!settled) {
            settled = true
            reject(error)
          }
        }
      })
    })
    return connecting
  }

  async function command(args) {
    if (failed) throw new Error('redis unavailable')
    if (!ready) await connect()
    return raw(args)
  }

  return {
    command,
    available: () => !failed && Boolean(url)
  }
}
