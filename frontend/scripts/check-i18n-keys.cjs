#!/usr/bin/env node
/* 校验 frontend/src/locales/zh-CN.ts 与 en-US.ts 的 key 完全一致。 */
const fs = require('fs')
const path = require('path')
const ts = require('typescript')

function loadLocale(file) {
  const source = fs.readFileSync(file, 'utf8')
  const js = ts.transpileModule(source.replace(/export\s+default\s+/, 'module.exports = '), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  const mod = { exports: {} }
  new Function('module', 'exports', js)(mod, mod.exports)
  return mod.exports
}

function flatten(value, prefix = '', out = {}) {
  for (const [key, child] of Object.entries(value || {})) {
    const full = prefix ? `${prefix}.${key}` : key
    if (child && typeof child === 'object' && !Array.isArray(child)) flatten(child, full, out)
    else out[full] = child
  }
  return out
}

const localeDir = path.resolve(__dirname, '..', 'src', 'locales')
const zh = flatten(loadLocale(path.join(localeDir, 'zh-CN.ts')))
const en = flatten(loadLocale(path.join(localeDir, 'en-US.ts')))
const missingEn = Object.keys(zh).filter((key) => !(key in en)).sort()
const missingZh = Object.keys(en).filter((key) => !(key in zh)).sort()
console.log(`i18n keys: zh-CN=${Object.keys(zh).length}, en-US=${Object.keys(en).length}`)
if (missingEn.length || missingZh.length) {
  if (missingEn.length) console.error(`missing in en-US:\n${missingEn.join('\n')}`)
  if (missingZh.length) console.error(`missing in zh-CN:\n${missingZh.join('\n')}`)
  process.exit(1)
}
console.log('i18n key parity OK')
