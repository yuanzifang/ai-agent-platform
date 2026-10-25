<template>
  <div class="markdown-body" @click="onClick" v-html="html"></div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
// 仅打包常用语言，避免全量 highlight.js 语言包把 Markdown chunk 推到 ~1MB。
import hljs from 'highlight.js/lib/common'
import 'highlight.js/styles/github.css'
import { ElMessage } from 'element-plus'

const props = defineProps<{ content: string }>()

function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

const renderer = new marked.Renderer()
renderer.code = (token: any) => {
  const text = String(token.text ?? '')
  const lang = String(token.lang ?? '').split(/\s+/)[0]
  let highlighted: string
  try {
    highlighted = lang && hljs.getLanguage(lang)
      ? hljs.highlight(text, { language: lang }).value
      : hljs.highlightAuto(text).value
  } catch {
    highlighted = escapeHtml(text)
  }
  return `<div class="code-block"><div class="code-head"><span>${escapeHtml(lang || 'text')}</span>`
    + `<button class="copy-code" type="button">复制</button></div>`
    + `<pre><code class="hljs">${highlighted}</code></pre></div>`
}
renderer.link = (token: any) => {
  const href = String(token.href ?? '#')
  const title = token.title ? ` title="${escapeHtml(String(token.title))}"` : ''
  return `<a href="${escapeHtml(href)}" target="_blank" rel="noopener noreferrer"${title}>${token.text ?? ''}</a>`
}

marked.setOptions({ gfm: true, breaks: true, renderer })

const html = computed(() => {
  const raw = marked.parse(props.content || '') as string
  return DOMPurify.sanitize(raw, { ADD_ATTR: ['target', 'rel'] })
})

function onClick(e: MouseEvent) {
  const target = e.target as HTMLElement
  if (!target.classList.contains('copy-code')) return
  const code = target.closest('.code-block')?.querySelector('code')?.textContent || ''
  navigator.clipboard.writeText(code)
    .then(() => ElMessage.success('代码已复制'))
    .catch(() => ElMessage.error('复制失败'))
}
</script>

<style>
.markdown-body { line-height: 1.65; word-break: break-word; white-space: normal; }
.markdown-body > :first-child { margin-top: 0; }
.markdown-body > :last-child { margin-bottom: 0; }
.markdown-body p { margin: 0 0 8px; }
.markdown-body h1, .markdown-body h2, .markdown-body h3, .markdown-body h4 {
  margin: 14px 0 8px; font-weight: 600; line-height: 1.35;
}
.markdown-body h1 { font-size: 20px; }
.markdown-body h2 { font-size: 18px; }
.markdown-body h3 { font-size: 16px; }
.markdown-body ul, .markdown-body ol { margin: 4px 0 8px; padding-left: 22px; }
.markdown-body li { margin: 2px 0; }
.markdown-body table { border-collapse: collapse; margin: 8px 0; display: block; overflow-x: auto; }
.markdown-body th, .markdown-body td { border: 1px solid #dcdfe6; padding: 5px 9px; font-size: 13px; }
.markdown-body th { background: #f5f7fa; font-weight: 600; }
.markdown-body blockquote {
  margin: 8px 0; padding: 4px 12px; color: #606266; border-left: 3px solid #dcdfe6; background: #fafafa;
}
.markdown-body code:not(pre code) {
  background: rgba(0, 0, 0, 0.06); border-radius: 3px; padding: 1px 5px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 0.9em;
}
.markdown-body .code-block { margin: 8px 0; border: 1px solid #e4e7ed; border-radius: 6px; overflow: hidden; }
.markdown-body .code-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 3px 10px; background: #f5f7fa; border-bottom: 1px solid #e4e7ed;
  font-size: 12px; color: #909399;
}
.markdown-body .copy-code {
  border: 0; background: transparent; color: #409eff; cursor: pointer; font-size: 12px; padding: 2px 4px;
}
.markdown-body .copy-code:hover { text-decoration: underline; }
.markdown-body pre { margin: 0; padding: 10px 12px; overflow-x: auto; background: #fff; }
.markdown-body pre code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12.5px; }
.markdown-body a { color: #409eff; text-decoration: none; }
.markdown-body a:hover { text-decoration: underline; }
.markdown-body hr { border: 0; border-top: 1px solid #e4e7ed; margin: 12px 0; }
</style>
