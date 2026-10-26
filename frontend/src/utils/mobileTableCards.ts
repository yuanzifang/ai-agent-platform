/**
 * 移动端「宽表格 → 卡片列表」渐进增强。
 *
 * 桌面端（>768px）不做任何处理；窄屏下给可卡片化的 el-table 打上 `m-table-cards`
 * 并按表头文字写入 `data-label`，样式在 styles/index.css 里配合渲染成一行一条的卡片。
 *
 * 不适用的表格保持原来的横向滚动：
 * - 固定列（操作列 fixed）：列会被复制渲染，卡片化会重复/丢失内容
 * - 选择列、展开行、树形表格：交互依赖表格结构
 */
const CARD_CLASS = 'm-table-cards'
const LABEL_ATTR = 'data-label'
const UNLABELED_CLASS = 'm-cell-unlabeled'
const MOBILE_QUERY = '(max-width: 768px)'

type Entry = { stop: () => void }

const entries = new Map<HTMLElement, Entry>()
let enabled = false
let documentObserver: MutationObserver | null = null

function headerCells(table: HTMLElement): HTMLElement[] {
  return Array.from(
    table.querySelectorAll<HTMLElement>('.el-table__header-wrapper thead th')
  )
}

/** 表头文字（列显隐由 v-if 控制，每次渲染都重新取） */
function labelsOf(table: HTMLElement): string[] {
  return headerCells(table).map(th => (th.querySelector('.cell')?.textContent || '').trim())
}

function isEligible(table: HTMLElement): boolean {
  // 固定列在 Element Plus 2.8 用 sticky 列实现（el-table-fixed-column--*），旧版本用 el-table__fixed*
  if (table.querySelector(
    '.el-table__fixed-right, .el-table__fixed-left, .el-table__fixed,' +
    '.el-table-fixed-column--right, .el-table-fixed-column--left'
  )) {
    return false
  }
  for (const th of headerCells(table)) {
    // 选择列 / 展开列
    if (th.classList.contains('el-table-column--selection')) return false
    if (th.classList.contains('el-table__expand-column')) return false
    if (th.querySelector('.el-checkbox')) return false
  }
  if (table.querySelector('.el-table__row--level-1, .el-table__row--level-2')) {
    return false
  }
  // 只有一列的表格没必要卡片化
  return headerCells(table).length > 1
}

function applyLabels(table: HTMLElement) {
  const labels = labelsOf(table)
  const rows = table.querySelectorAll<HTMLElement>('.el-table__body-wrapper tbody tr')
  rows.forEach(tr => {
    Array.from(tr.querySelectorAll<HTMLElement>('td')).forEach((td, i) => {
      const label = labels[i]
      if (label) {
        td.setAttribute(LABEL_ATTR, label)
        td.classList.remove(UNLABELED_CLASS)
      } else {
        td.classList.add(UNLABELED_CLASS)
      }
      // 空单元格（没有文字也没有任何控件）在卡片里没有意义，直接隐藏
      const empty = !(td.textContent || '').trim() &&
        !td.querySelector('button, img, svg, input, .el-button, .el-switch, .el-checkbox, .el-tag, .el-avatar')
      td.classList.toggle('m-cell-empty', empty)
    })
  })
}

function enhance(table: HTMLElement, attempt = 0) {
  if (!enabled || entries.has(table)) return
  // el-table 的表头/表体是异步渲染的，等它出现再决定
  if (headerCells(table).length < 2) {
    if (attempt < 20) setTimeout(() => enhance(table, attempt + 1), 60)
    return
  }
  if (!isEligible(table)) return

  table.classList.add(CARD_CLASS)
  applyLabels(table)

  let frame = 0
  const observer = new MutationObserver(() => {
    if (frame) return
    frame = requestAnimationFrame(() => {
      frame = 0
      applyLabels(table)
    })
  })
  observer.observe(table, { childList: true, subtree: true })

  entries.set(table, {
    stop() {
      observer.disconnect()
      if (frame) cancelAnimationFrame(frame)
      table.classList.remove(CARD_CLASS)
      table.querySelectorAll<HTMLElement>(`td[${LABEL_ATTR}]`).forEach(td => {
        td.removeAttribute(LABEL_ATTR)
        td.classList.remove(UNLABELED_CLASS, 'm-cell-empty')
      })
    }
  })
}

function enable() {
  enabled = true
  document.querySelectorAll<HTMLElement>('.el-table').forEach(el => enhance(el))
  if (documentObserver) return
  // 只关心「新出现的表格」，避免聊天流式输出时的节点变更造成无谓扫描
  documentObserver = new MutationObserver(mutations => {
    for (const mutation of mutations) {
      mutation.addedNodes.forEach(node => {
        if (!(node instanceof HTMLElement)) return
        if (node.matches('.el-table')) {
          enhance(node as HTMLElement)
        } else {
          node.querySelectorAll<HTMLElement>('.el-table').forEach(el => enhance(el))
        }
      })
    }
  })
  documentObserver.observe(document.body, { childList: true, subtree: true })
}

function disable() {
  enabled = false
  documentObserver?.disconnect()
  documentObserver = null
  entries.forEach(entry => entry.stop())
  entries.clear()
}

export function setupMobileTableCards() {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
  const mql = window.matchMedia(MOBILE_QUERY)
  if (mql.matches) enable()
  mql.addEventListener('change', e => (e.matches ? enable() : disable()))
}
