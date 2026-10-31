#!/usr/bin/env node
/* 校验 frontend/src/i18n.ts 的 zh-CN / en-US key 完全一致。 */
const fs = require('fs')
const path = require('path')
const ts = require('typescript')

const file = path.resolve(__dirname, '..', 'src', 'i18n.ts')
const src = fs.readFileSync(file, 'utf8')
const sf = ts.createSourceFile('i18n.ts', src, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)

let initializer = null
function walk(node) {
  if (ts.isVariableStatement(node)) {
    for (const decl of node.declarationList.declarations) {
      if (ts.isIdentifier(decl.name) && decl.name.text === 'messages' && decl.initializer) {
        initializer = decl.initializer
      }
    }
  }
  ts.forEachChild(node, walk)
}
walk(sf)
if (!initializer) {
  console.error('i18n messages initializer not found')
  process.exit(1)
}

const js = ts.transpileModule(`const messages = ${initializer.getText(sf)};\nmodule.exports = messages;`, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText
const mod = { exports: {} }
new Function('module', 'exports', js)(mod, mod.exports)

function flatten(value, prefix = '', out = {}) {
  for (const [key, child] of Object.entries(value || {})) {
    const full = prefix ? `${prefix}.${key}` : key
    if (child && typeof child === 'object' && !Array.isArray(child)) flatten(child, full, out)
    else out[full] = child
  }
  return out
}

const zh = flatten(mod.exports['zh-CN'] || {})
const en = flatten(mod.exports['en-US'] || {})
const missingEn = Object.keys(zh).filter((key) => !(key in en)).sort()
const missingZh = Object.keys(en).filter((key) => !(key in zh)).sort()
console.log(`i18n keys: zh-CN=${Object.keys(zh).length}, en-US=${Object.keys(en).length}`)
if (missingEn.length || missingZh.length) {
  if (missingEn.length) console.error(`missing in en-US:\n${missingEn.join('\n')}`)
  if (missingZh.length) console.error(`missing in zh-CN:\n${missingZh.join('\n')}`)
  process.exit(1)
}
console.log('i18n key parity OK')
