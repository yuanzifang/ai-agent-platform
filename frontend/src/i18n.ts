import { createI18n } from 'vue-i18n'

import zhCN from './locales/zh-CN'
import enUS from './locales/en-US'

const messages = {
  'zh-CN': zhCN,
  'en-US': enUS
}


export type Locale = 'zh-CN' | 'en-US'
const saved = localStorage.getItem('locale')
const locale: Locale = saved === 'en-US' ? 'en-US' : 'zh-CN'

function escapeMessageBraces<T>(value: T): T {
  if (typeof value === 'string') {
    return value.replace(/[{}]/g, m => m === '{' ? "{'{'}" : "{'}'}") as unknown as T
  }
  if (Array.isArray(value)) return value.map(v => escapeMessageBraces(v)) as unknown as T
  if (value && typeof value === 'object') {
    const out: Record<string, any> = {}
    for (const [k, v] of Object.entries(value as Record<string, any>)) {
      out[k] = escapeMessageBraces(v)
    }
    return out as T
  }
  return value
}

export const i18n = createI18n({
  legacy: false,
  locale,
  fallbackLocale: 'zh-CN',
  messages: escapeMessageBraces(messages)
})

export function setLocale(next: Locale) {
  i18n.global.locale.value = next
  localStorage.setItem('locale', next)
  document.documentElement.lang = next
  // 拉取后端覆盖层（失败静默——静态包兜底，管理端配错不影响可用性）
  applyRemoteMessages(next)
}

/**
 * 后端 i18n 覆盖层：GET /api/v1/i18n/messages/{lang} 返回扁平 {key: value}，
 * 按点号分层写回当前 locale（浅层 merge，已有键覆盖、新键新增）。
 * 静态语言包打底 → DB 条目覆盖/扩展；新语言经管理端入库并在 /locales 出现。
 */
export async function applyRemoteMessages(lang: string) {
  try {
    const res = await fetch(`/api/v1/i18n/messages/${encodeURIComponent(lang)}`)
    const body = await res.json()
    if (!res.ok || body?.code !== 0 || !body.data) return
    const flat: Record<string, string> = body.data
    // 深合并到当前消息树；类型上借道 any 绕过 vue-i18n 对联合字面量的严格推断
    const merged: any = JSON.parse(JSON.stringify(i18n.global.getLocaleMessage(lang)))
    for (const [key, value] of Object.entries(flat)) {
      const parts = key.split('.')
      let node = merged
      for (let i = 0; i < parts.length - 1; i++) {
        if (!node[parts[i]] || typeof node[parts[i]] !== 'object') node[parts[i]] = {}
        node = node[parts[i]]
      }
      node[parts[parts.length - 1]] = escapeMessageBraces(value)
    }
    i18n.global.setLocaleMessage(lang, merged)
  } catch {
    // 网络失败保持静态包
  }
}

/** 数据库配置的语言列表（含静态包自带），供语言选择器。 */
export async function remoteLocales(): Promise<string[]> {
  try {
    const res = await fetch('/api/v1/i18n/locales')
    const body = await res.json()
    return res.ok && body?.code === 0 && Array.isArray(body.data) ? body.data : []
  } catch {
    return []
  }
}

// 启动时合并当前语言的远端覆盖（登录页在挂载前即生效）
applyRemoteMessages(locale)
