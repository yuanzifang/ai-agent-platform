import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import http from '../api/http'

export function useAgentWorkflowEditor(ctx: any) {
  const { agentId, versions, form } = ctx
  const { t } = useI18n()
  const router = useRouter()

// ---- 工作流 / 人工审批 ----
const workflowVisible = ref(false)
const workflowConfigured = ref(false)
const workflowSaving = ref(false)
const workflowRunning = ref(false)
const workflowResuming = ref(false)
const wfAddType = ref('LLM')
const wfTypes = [
  { label: t('wf.typeLlm'), value: 'LLM' },
  { label: t('wf.typeAgent'), value: 'AGENT' },
  { label: 'Agent Handoff', value: 'HANDOFF' },
  { label: t('wf.typeState'), value: 'STATE' },
  { label: t('wf.typeTool'), value: 'TOOL' },
  { label: t('wf.typeKnowledge'), value: 'KNOWLEDGE' },
  { label: t('wf.typeCondition'), value: 'CONDITION' },
  { label: t('wf.typeParallel'), value: 'PARALLEL' },
  { label: t('wf.typeLoop'), value: 'LOOP' },
  { label: t('wf.typeJoin'), value: 'JOIN' },
  { label: t('wf.typeApproval'), value: 'APPROVAL' }
]
const workflowForm = reactive({ name: '', status: 'ENABLED', nodes: [] as any[], edges: [] as any[] })
const workflowRunInput = ref('')
const workflowRunResult = ref<any>(null)
const workflowPublishedVersion = ref('')
const workflowHasUnpublishedChanges = ref(false)
const workflowPublishing = ref(false)
const wfVersionsVisible = ref(false)
const wfVersions = ref<any[]>([])
const wfMode = ref<'list' | 'canvas'>('list')
const wfSelected = ref('')
const wfLinkMode = ref(false)
const wfLinkFrom = ref('')
const wfHistory = ref<string[]>([])
const wfHistoryIndex = ref(-1)
const wfSelectedEdge = ref<number | null>(null)
const wfDraggingType = ref('')
const wfDropActive = ref(false)
const wfLinkDrag = ref<null | { from: string; branch: string; x1: number; y1: number; x2: number; y2: number }>(null)
const wfNodePanel = ref(false)
const wfRewireMode = ref(false)
const wfMultiSelected = ref<string[]>([])
const wfClipboardSize = ref(0)
const wfReplayIndex = ref(-1)
const wfReplayPlaying = ref(false)
let wfReplayTimer: ReturnType<typeof setTimeout> | null = null
const wfBoxSelect = ref<null | { x1: number; y1: number; x2: number; y2: number }>(null)
let wfClipboard: { nodes: any[]; edges: any[] } | null = null
let wfNodeEditBefore = ''
const wfSelectedNode = computed(() => workflowForm.nodes.find((n: any) => n.id === wfSelected.value) || null)
const wfBoxStyle = computed(() => {
  const b = wfBoxSelect.value
  if (!b) return {}
  return {
    left: Math.min(b.x1, b.x2) + 'px',
    top: Math.min(b.y1, b.y2) + 'px',
    width: Math.abs(b.x2 - b.x1) + 'px',
    height: Math.abs(b.y2 - b.y1) + 'px'
  }
})
const wfCanvasEl = ref<HTMLElement>()
const wfDiffVisible = ref(false)
const wfDiffTitle = ref('')
const wfDiffRows = ref<any[]>([])
const wfZoom = ref(1)
const wfTemplatesVisible = ref(false)
const wfTemplates = ref<any[]>([])
function applyWorkflowData(data: any) {
  workflowConfigured.value = !!data?.configured
  workflowForm.name = data?.name || `${form.name}${t('agentMsg.workflowNameSuffix')}`
  workflowForm.status = data?.status || 'ENABLED'
  workflowPublishedVersion.value = data?.publishedVersion || ''
  workflowHasUnpublishedChanges.value = !!data?.hasUnpublishedChanges
  workflowForm.nodes = (data?.definition?.nodes || []).map((n: any) => ({
    ...n, ...(n.type === 'CONDITION' ? { conditionSource: n.conditionSource || 'last' } : {}),
    ...(n.type === 'LOOP' ? { itemsText: JSON.stringify(n.items || []) } : {})
  }))
  workflowForm.edges = (data?.definition?.edges || []).map((e: any) => ({ ...e }))
}

async function openWorkflow() {
  applyWorkflowData(await http.get(`/agent/${agentId}/workflow`) as any)
  ensureWorkflowPositions()
  wfMode.value = 'list'
  wfSelected.value = ''
  if (!workflowForm.nodes.length) {
    workflowForm.nodes = [
      { id: 'start', type: 'START', name: t('agentMsg.defaultStart'), next: 'end' },
      { id: 'end', type: 'END', name: t('agentMsg.defaultEnd') }
    ]
    ensureWorkflowPositions()
  }
  resetWorkflowHistory()
  workflowRunInput.value = ''
  workflowRunResult.value = null
  workflowVisible.value = true
}

function wfTypeText(type: string) {
  return ({ START: t('wf.typeStart'), END: t('wf.typeEnd'), LLM: t('wf.typeLlm'), AGENT: t('wf.typeAgent'), HANDOFF: t('wf.typeHandoff'), STATE: t('wf.typeState'), TOOL: t('wf.typeTool'), KNOWLEDGE: t('wf.typeKnowledge'), CONDITION: t('wf.typeCondition'), APPROVAL: t('wf.typeApproval'), PARALLEL: t('wf.typeParallel'), LOOP: t('wf.typeLoop'), JOIN: t('wf.typeJoin') } as any)[type] || type
}

function wfTagType(type: string) {
  return ({ START: 'info', END: 'info', LLM: 'success', AGENT: 'warning', HANDOFF: 'warning', STATE: 'info', CONDITION: 'primary', APPROVAL: 'danger' } as any)[type] || 'info'
}

function wfTargets(currentId: string) {
  return workflowForm.nodes.filter((n: any) => n.id !== currentId)
}

function wfBranchTargets(currentId: string) {
  return workflowForm.nodes.filter((n: any) => n.id !== currentId
    && !['START', 'END', 'JOIN', 'PARALLEL', 'LOOP'].includes(n.type))
}

function ensureWorkflowPositions() {
  workflowForm.nodes.forEach((n: any, i: number) => {
    if (!n.position || typeof n.position.x !== 'number' || typeof n.position.y !== 'number') {
      n.position = { x: 60 + (i % 3) * 230, y: 40 + Math.floor(i / 3) * 120 }
    }
  })
}

// ---------- 画布右侧节点配置面板 ----------
function openNodePanel(n: any) {
  if (wfNodeEditBefore) closeNodePanel()
  wfSelected.value = n.id
  wfSelectedEdge.value = null
  wfNodeEditBefore = snapshotWorkflow()
  wfNodePanel.value = true
}

function closeNodePanel() {
  if (wfNodeEditBefore) {
    commitWorkflowHistory(wfNodeEditBefore)
    wfNodeEditBefore = ''
  }
  wfNodePanel.value = false
}

function openNodeInList() {
  const id = wfSelected.value
  closeNodePanel()
  editWorkflowNode(id)
}

function removeSelectedNode() {
  const index = workflowForm.nodes.findIndex((x: any) => x.id === wfSelected.value)
  wfNodePanel.value = false
  wfNodeEditBefore = ''
  if (index >= 0) removeWorkflowNode(index)
}

// ---------- 画布撤销/重做 ----------
function snapshotWorkflow() {
  return JSON.stringify({ nodes: workflowForm.nodes, edges: workflowForm.edges })
}

function resetWorkflowHistory() {
  wfHistory.value = [snapshotWorkflow()]
  wfHistoryIndex.value = 0
  wfSelectedEdge.value = null
  wfLinkDrag.value = null
}

function commitWorkflowHistory(before: string) {
  const after = snapshotWorkflow()
  if (after === before) return
  if (wfHistory.value[wfHistoryIndex.value] === before) {
    wfHistory.value.splice(wfHistoryIndex.value + 1)
  } else {
    wfHistory.value = [before]
    wfHistoryIndex.value = 0
  }
  wfHistory.value.push(after)
  if (wfHistory.value.length > 60) wfHistory.value.shift()
  wfHistoryIndex.value = wfHistory.value.length - 1
}

function restoreWorkflow(snapshot: string) {
  const data = JSON.parse(snapshot)
  workflowForm.nodes = data.nodes || []
  workflowForm.edges = data.edges || []
  ensureWorkflowPositions()
  wfSelected.value = ''
  wfSelectedEdge.value = null
  wfNodePanel.value = false
  wfNodeEditBefore = ''
  wfRewireMode.value = false
  wfMultiSelected.value = []
}

function undoWorkflow() {
  if (wfHistoryIndex.value <= 0) return
  wfHistoryIndex.value -= 1
  restoreWorkflow(wfHistory.value[wfHistoryIndex.value])
}

function redoWorkflow() {
  if (wfHistoryIndex.value >= wfHistory.value.length - 1) return
  wfHistoryIndex.value += 1
  restoreWorkflow(wfHistory.value[wfHistoryIndex.value])
}

function onWorkflowKeydown(e: KeyboardEvent) {
  if (!workflowVisible.value) return
  const el = e.target as HTMLElement | null
  if (el && /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName)) return
  const meta = e.metaKey || e.ctrlKey
  if (meta && e.key.toLowerCase() === 'c' && wfMode.value === 'canvas') {
    e.preventDefault()
    copySelectedNodes()
    return
  }
  if (meta && e.key.toLowerCase() === 'v' && wfMode.value === 'canvas') {
    e.preventDefault()
    pasteNodes()
    return
  }
  if (meta && e.key.toLowerCase() === 'z') {
    e.preventDefault()
    if (e.shiftKey) redoWorkflow()
    else undoWorkflow()
    return
  }
  if (e.key === 'Escape' && wfRewireMode.value) {
    e.preventDefault()
    cancelRewire()
    return
  }
  if ((e.key === 'Delete' || e.key === 'Backspace') && wfMultiSelected.value.length > 1) {
    e.preventDefault()
    removeMultiSelected()
    return
  }
  if ((e.key === 'Delete' || e.key === 'Backspace') && wfSelectedEdge.value !== null) {
    e.preventDefault()
    deleteSelectedEdge()
  }
}

// ---------- 画布交互：节点面板拖入 / 端口连线 / 选中连线 ----------
function onPaletteDragStart(ev: DragEvent, type: string) {
  wfDraggingType.value = type
  if (ev.dataTransfer) {
    ev.dataTransfer.setData('text/plain', type)
    ev.dataTransfer.effectAllowed = 'copy'
  }
}

function onCanvasDrop(ev: DragEvent) {
  wfDropActive.value = false
  const type = wfDraggingType.value || ev.dataTransfer?.getData('text/plain') || ''
  wfDraggingType.value = ''
  if (!type) return
  const canvas = wfCanvasEl.value?.querySelector('.wf-canvas') as HTMLElement | null
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  const x = Math.max(0, (ev.clientX - rect.left) / wfZoom.value - 95)
  const y = Math.max(0, (ev.clientY - rect.top) / wfZoom.value - 28)
  insertWorkflowNode(type, { x: Math.round(x), y: Math.round(y) })
}

function startLinkDrag(ev: MouseEvent, n: any, branch = '') {
  ensureWorkflowPositions()
  const canvas = wfCanvasEl.value?.querySelector('.wf-canvas') as HTMLElement | null
  if (!canvas) return
  const x1 = n.position.x + 190
  const y1 = n.position.y + (branch === 'true' ? 16 : branch === 'false' ? 52 : 34)
  wfLinkDrag.value = { from: n.id, branch, x1, y1, x2: x1, y2: y1 }
  const toCanvas = (e: MouseEvent) => {
    const rect = canvas.getBoundingClientRect()
    return { x: (e.clientX - rect.left) / wfZoom.value, y: (e.clientY - rect.top) / wfZoom.value }
  }
  const move = (e: MouseEvent) => {
    if (!wfLinkDrag.value) return
    const point = toCanvas(e)
    wfLinkDrag.value.x2 = point.x
    wfLinkDrag.value.y2 = point.y
  }
  const up = (e: MouseEvent) => {
    window.removeEventListener('mousemove', move)
    window.removeEventListener('mouseup', up)
    const fromId = wfLinkDrag.value?.from
    const branch = wfLinkDrag.value?.branch || ''
    wfLinkDrag.value = null
    if (!fromId) return
    const target = (document.elementFromPoint(e.clientX, e.clientY) as HTMLElement | null)
      ?.closest('.wf-canvas-node') as HTMLElement | null
    const toId = target?.dataset.nodeId
    if (toId && toId !== fromId) connectNodes(fromId, toId, branch)
  }
  window.addEventListener('mousemove', move)
  window.addEventListener('mouseup', up)
  ev.preventDefault()
}

function connectNodes(fromId: string, toId: string, branch = '') {
  const from = workflowForm.nodes.find((x: any) => x.id === fromId)
  if (!from) return
  const to = workflowForm.nodes.find((x: any) => x.id === toId)
  if (!to) return
  if (from.type === 'PARALLEL' || from.type === 'LOOP') {
    ElMessage.warning(t('wf.connectUnsupported'))
    return
  }
  const before = snapshotWorkflow()
  if (from.type === 'CONDITION') {
    const label = branch === 'false' ? 'false' : 'true'
    if (label === 'true') from.trueNext = toId
    else from.falseNext = toId
    // 条件分支由 trueNext/falseNext 推导，清理同分支的旧 Graph edge，避免双线。
    workflowForm.edges = workflowForm.edges.filter((e: any) =>
      !(e.from === fromId && String(e.label || 'true') === label))
    wfSelectedEdge.value = null
    commitWorkflowHistory(before)
    ElMessage.success(t('agentMsg.edgeAdded'))
    return
  }
  // 普通节点/汇合节点：画布连线直接更新语义 next，而不是只加一条不会生效的边。
  from.next = toId
  workflowForm.edges = workflowForm.edges.filter((e: any) => e.from !== fromId)
  wfSelectedEdge.value = null
  commitWorkflowHistory(before)
  ElMessage.success(t('agentMsg.edgeAdded'))
}

function startRewireEdge() {
  if (wfSelectedEdge.value === null) return
  wfRewireMode.value = true
  wfSelected.value = ''
  ElMessage.info(t('wf.rewireHint'))
}

function finishRewire(target: any) {
  const index = wfSelectedEdge.value
  const edge = index === null ? null : workflowForm.edges[index]
  wfRewireMode.value = false
  if (index === null || !edge) return
  if (edge.from === target.id) {
    ElMessage.warning(t('wf.rewireSelf'))
    return
  }
  if (workflowForm.edges.some((e: any, i: number) => i !== index && e.from === edge.from && e.to === target.id)) {
    ElMessage.info(t('agentMsg.edgeExists'))
    return
  }
  const before = snapshotWorkflow()
  workflowForm.edges[index].to = target.id
  wfSelectedEdge.value = null
  commitWorkflowHistory(before)
  ElMessage.success(t('wf.edgeRewired'))
}

function cancelRewire() {
  if (!wfRewireMode.value) return false
  wfRewireMode.value = false
  ElMessage.info(t('wf.rewireCancelled'))
  return true
}

function selectEdge(edge: any) {
  wfRewireMode.value = false
  if (!edge.explicit) {
    wfSelectedEdge.value = null
    ElMessage.info(t('wf.derivedEdgeHint'))
    return
  }
  wfSelectedEdge.value = edge.index
}

function deleteSelectedEdge() {
  const index = wfSelectedEdge.value
  wfRewireMode.value = false
  if (index === null) return
  const before = snapshotWorkflow()
  workflowForm.edges.splice(index, 1)
  wfSelectedEdge.value = null
  commitWorkflowHistory(before)
}

function removeGraphEdge(index: number) {
  const before = snapshotWorkflow()
  workflowForm.edges.splice(index, 1)
  wfSelectedEdge.value = null
  commitWorkflowHistory(before)
}

function fitCanvas() {
  const wrap = wfCanvasEl.value
  if (!wrap) return
  const scale = Math.min(1.2, Math.max(0.5, Math.min(
    (wrap.clientWidth - 24) / wfCanvasSize.value.w,
    (wrap.clientHeight - 24) / wfCanvasSize.value.h
  )))
  wfZoom.value = Number(scale.toFixed(2))
  wrap.scrollLeft = 0
  wrap.scrollTop = 0
}

function nodeX(n: any) {
  return n.position?.x ?? 0
}

function nodeY(n: any) {
  return n.position?.y ?? 0
}

const wfCanvasSize = computed(() => {
  let maxX = 640
  let maxY = 380
  for (const n of workflowForm.nodes) {
    maxX = Math.max(maxX, nodeX(n) + 260)
    maxY = Math.max(maxY, nodeY(n) + 140)
  }
  return { w: maxX, h: maxY }
})

const wfEdges = computed(() => {
  const nodes = workflowForm.nodes
  const map = new Map(nodes.map((n: any) => [n.id, n]))
  const edges: any[] = []
  // 同一对节点只画一条线；条件 true/false 指向同一节点时合并成 "true/false"
  const seen = new Map<string, any>()
  const add = (from: any, to: any, color: string, index?: number, forceLabel?: string) => {
    if (!from || !to) return
    const key = `${from.id}->${to.id}`
    const label = index != null
      ? String(workflowForm.edges[index]?.label || '')
      : (forceLabel || (color === '#67c23a' ? 'true' : color === '#f56c6c' ? 'false' : ''))
    const previous = seen.get(key)
    if (previous) {
      if (label && !previous.label) previous.label = label
      else if (label && previous.label && !previous.label.split('/').includes(label)) {
        previous.label = `${previous.label}/${label}`
        previous.color = '#e6a23c'
      }
      return
    }
    const x1 = nodeX(from) + 190
    const y1 = nodeY(from) + 34
    const x2 = nodeX(to)
    const y2 = nodeY(to) + 34
    const edge = {
      id: index != null ? `edge-${index}` : `derived-${from.id}-${to.id}`,
      index: index ?? null,
      explicit: index != null,
      color,
      label,
      labelX: (x1 + x2) / 2,
      labelY: (y1 + y2) / 2 - 6,
      d: `M ${x1} ${y1} C ${x1 + 60} ${y1}, ${x2 - 60} ${y2}, ${x2} ${y2}`
    }
    seen.set(key, edge)
    edges.push(edge)
  }
  // 手动连线优先（可选中删除）；其余按节点顺序/条件/并行分支推导，属于只读
  workflowForm.edges.forEach((e: any, i: number) => {
    const color = e.label === 'false' ? '#f56c6c' : e.label === 'true' ? '#67c23a' : '#409eff'
    add(map.get(e.from), map.get(e.to), color, i)
  })
  for (const n of nodes) {
    if (n.type === 'END') continue
    if (n.type === 'CONDITION') {
      add(n, map.get(n.trueNext), '#67c23a', undefined, 'true')
      add(n, map.get(n.falseNext), '#f56c6c', undefined, 'false')
    } else if (n.type === 'PARALLEL') {
      const branches: string[] = Array.isArray(n.branches) ? n.branches : []
      const join = map.get(n.joinNode)
      for (const b of branches) {
        const bn = map.get(b)
        add(n, bn, '#e6a23c')
        add(bn, join, '#e6a23c')
      }
      if (!branches.length) add(n, join, '#e6a23c')
    } else {
      add(n, map.get(n.next), '#909399')
    }
  }
  return edges
})

const wfLinkPath = computed(() => {
  const d = wfLinkDrag.value
  if (!d) return ''
  return `M ${d.x1} ${d.y1} C ${d.x1 + 60} ${d.y1}, ${d.x2 - 60} ${d.y2}, ${d.x2} ${d.y2}`
})

function wfRunClass(n: any) {
  const status = String(wfRunNodeStatus.value[n.id] || '')
  return {
    'run-success': status === 'SUCCESS',
    'run-failed': ['FAILED', 'ERROR', 'REJECTED'].includes(status),
    'run-running': status === 'RUNNING',
    'run-waiting': ['WAITING', 'SKIPPED'].includes(status)
  }
}

const wfRunNodeStatus = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const r of (workflowRunResult.value?.nodeRuns || [])) {
    if (r?.nodeId) map[String(r.nodeId)] = String(r.status || '')
  }
  return map
})

function toggleLinkMode() {
  wfLinkMode.value = !wfLinkMode.value
  wfLinkFrom.value = ''
}

let wfDragMoved = false
let wfDragEndedAt = 0

function onCanvasNodeClick(n: any, ev?: MouseEvent) {
  // 拖拽结束后的 click 不要重置多选/触发面板
  if (Date.now() - wfDragEndedAt < 300) {
    wfDragEndedAt = 0
    return
  }
  if (wfRewireMode.value) {
    finishRewire(n)
    return
  }
  if (ev?.shiftKey) {
    const i = wfMultiSelected.value.indexOf(n.id)
    if (i >= 0) wfMultiSelected.value.splice(i, 1)
    else wfMultiSelected.value.push(n.id)
    wfSelected.value = n.id
    return
  }
  wfMultiSelected.value = []
  wfSelected.value = n.id
  if (!wfLinkMode.value) {
    openNodePanel(n)
    return
  }
  if (!wfLinkFrom.value) {
    wfLinkFrom.value = n.id
    ElMessage.info(`${t('agentMsg.startSelected')}${n.name}${t('agentMsg.clickEnd')}`)
    return
  }
  if (wfLinkFrom.value === n.id) {
    wfLinkFrom.value = ''
    return
  }
  const from = workflowForm.nodes.find((x: any) => x.id === wfLinkFrom.value)
  const fromType = String(from?.type || '')
  const targetId = wfLinkFrom.value
  wfLinkFrom.value = ''
  connectNodes(targetId, n.id, fromType === 'CONDITION' ? 'true' : '')
}

function zoomCanvas(delta: number) {
  wfZoom.value = Math.min(1.6, Math.max(0.5, Number((wfZoom.value + delta).toFixed(2))))
}

function autoLayoutWorkflow() {
  const before = snapshotWorkflow()
  workflowForm.nodes.forEach((n: any, i: number) => {
    n.position = { x: 60 + (i % 3) * 240, y: 40 + Math.floor(i / 3) * 120 }
  })
  commitWorkflowHistory(before)
}

function editWorkflowNode(id: string) {
  wfSelected.value = id
  wfMode.value = 'list'
}

function startBoxSelect(ev: MouseEvent) {
  const canvas = wfCanvasEl.value?.querySelector('.wf-canvas') as HTMLElement | null
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  const toCanvas = (e: MouseEvent) => ({
    x: (e.clientX - rect.left) / wfZoom.value,
    y: (e.clientY - rect.top) / wfZoom.value
  })
  const start = toCanvas(ev)
  wfBoxSelect.value = { x1: start.x, y1: start.y, x2: start.x, y2: start.y }
  const move = (e: MouseEvent) => {
    if (!wfBoxSelect.value) return
    const point = toCanvas(e)
    wfBoxSelect.value.x2 = point.x
    wfBoxSelect.value.y2 = point.y
  }
  const up = () => {
    window.removeEventListener('mousemove', move)
    window.removeEventListener('mouseup', up)
    const box = wfBoxSelect.value
    wfBoxSelect.value = null
    if (!box) return
    const x1 = Math.min(box.x1, box.x2)
    const x2 = Math.max(box.x1, box.x2)
    const y1 = Math.min(box.y1, box.y2)
    const y2 = Math.max(box.y1, box.y2)
    if (x2 - x1 < 4 && y2 - y1 < 4) return
    const hits = workflowForm.nodes.filter((n: any) => {
      const nx1 = nodeX(n)
      const ny1 = nodeY(n)
      return nx1 < x2 && nx1 + 190 > x1 && ny1 < y2 && ny1 + 66 > y1
    }).map((n: any) => n.id)
    if (!hits.length) return
    wfMultiSelected.value = Array.from(new Set([...wfMultiSelected.value, ...hits]))
    wfSelected.value = ''
    wfNodePanel.value = false
  }
  window.addEventListener('mousemove', move)
  window.addEventListener('mouseup', up)
  ev.preventDefault()
}

function startCanvasPan(ev: MouseEvent) {
  const el = wfCanvasEl.value
  if (!el) return
  if (cancelRewire()) return
  if (ev.shiftKey) {
    startBoxSelect(ev)
    return
  }
  const startX = ev.clientX
  const startY = ev.clientY
  const sl = el.scrollLeft
  const st = el.scrollTop
  const move = (e: MouseEvent) => {
    el.scrollLeft = sl - (e.clientX - startX)
    el.scrollTop = st - (e.clientY - startY)
  }
  const up = () => {
    window.removeEventListener('mousemove', move)
    window.removeEventListener('mouseup', up)
  }
  window.addEventListener('mousemove', move)
  window.addEventListener('mouseup', up)
}

async function openWorkflowTemplates() {
  wfTemplates.value = await http.get('/workflow/templates') as any
  wfTemplatesVisible.value = true
}

function applyWorkflowTemplate(tpl: any) {
  const before = snapshotWorkflow()
  let def: any = tpl?.definition
  if (typeof def === 'string') {
    try { def = JSON.parse(def) } catch { def = {} }
  }
  const nodes = def?.nodes || []
  if (!nodes.length) { ElMessage.warning(t('agentMsg.templateNoNodes')); return }
  workflowForm.nodes = nodes.map((n: any) => ({
    ...n, ...(n.type === 'CONDITION' ? { conditionSource: n.conditionSource || 'last' } : {}),
    ...(n.type === 'LOOP' ? { itemsText: JSON.stringify(n.items || []) } : {})
  }))
  ensureWorkflowPositions()
  wfMode.value = 'list'
  wfSelected.value = ''
  wfTemplatesVisible.value = false
  wfSelectedEdge.value = null
  commitWorkflowHistory(before)
  ElMessage.success(t('agentMsg.templateLoaded'))
}

function copySelectedNodes() {
  const ids = wfMultiSelected.value.length
    ? [...wfMultiSelected.value]
    : (wfSelected.value ? [wfSelected.value] : [])
  const nodes = workflowForm.nodes.filter((n: any) => ids.includes(n.id) && !['START', 'END'].includes(n.type))
  if (!nodes.length) {
    ElMessage.warning(t('wf.copyNone'))
    return
  }
  const idSet = new Set(nodes.map((n: any) => n.id))
  wfClipboard = {
    nodes: JSON.parse(JSON.stringify(nodes)),
    edges: JSON.parse(JSON.stringify(workflowForm.edges.filter((e: any) => idSet.has(e.from) && idSet.has(e.to))))
  }
  wfClipboardSize.value = nodes.length
  ElMessage.success(t('wf.copiedNodes'))
}

function pasteNodes() {
  if (!wfClipboard || !wfClipboard.nodes.length) {
    ElMessage.warning(t('wf.clipboardEmpty'))
    return
  }
  const before = snapshotWorkflow()
  const used = new Set(workflowForm.nodes.map((n: any) => n.id))
  const idMap = new Map<string, string>()
  const newId = () => {
    let id = ''
    do {
      id = `n${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`
    } while (used.has(id))
    used.add(id)
    return id
  }
  for (const n of wfClipboard.nodes) idMap.set(n.id, newId())

  const clones = wfClipboard.nodes.map((n: any, i: number) => {
    const copy = JSON.parse(JSON.stringify(n))
    copy.id = idMap.get(n.id)
    copy.name = `${n.name} ${t('wf.copySuffix')}`
    copy.position = avoidWorkflowOverlap({
      x: (n.position?.x ?? 60) + 40 + i * 16,
      y: (n.position?.y ?? 40) + 40 + i * 16
    })
    // 指向副本内部的引用做重映射；指向外部的引用收敛到 end/空，避免粘贴出意外连线或环
    copy.next = idMap.get(copy.next) || 'end'
    copy.trueNext = idMap.get(copy.trueNext) || 'end'
    copy.falseNext = idMap.get(copy.falseNext) || 'end'
    copy.joinNode = idMap.get(copy.joinNode) || ''
    copy.bodyNode = idMap.get(copy.bodyNode) || ''
    copy.loopEnd = idMap.get(copy.loopEnd) || ''
    if (Array.isArray(copy.branches)) copy.branches = copy.branches.map((b: string) => idMap.get(b)).filter(Boolean)
    return copy
  })

  const endIndex = workflowForm.nodes.findIndex((n: any) => n.type === 'END')
  if (endIndex >= 0) workflowForm.nodes.splice(endIndex, 0, ...clones)
  else workflowForm.nodes.push(...clones)
  for (const edge of wfClipboard.edges) {
    workflowForm.edges.push({ ...edge, from: idMap.get(edge.from), to: idMap.get(edge.to) })
  }
  wfSelected.value = ''
  wfSelectedEdge.value = null
  wfMultiSelected.value = clones.map((c: any) => c.id)
  commitWorkflowHistory(before)
  ElMessage.success(t('wf.pastedNodes'))
}

function removeMultiSelected() {
  const ids = new Set(wfMultiSelected.value)
  const targets = workflowForm.nodes.filter((x: any) => ids.has(x.id) && !['START', 'END'].includes(x.type))
  if (!targets.length) {
    ElMessage.warning(t('wf.multiNoneRemovable'))
    return
  }
  const before = snapshotWorkflow()
  workflowForm.nodes = workflowForm.nodes.filter((x: any) => !targets.some((r: any) => r.id === x.id))
  wfMultiSelected.value = []
  wfSelected.value = ''
  wfSelectedEdge.value = null
  rewireWorkflow()
  commitWorkflowHistory(before)
}

function startNodeDrag(ev: MouseEvent, n: any) {
  ensureWorkflowPositions()
  const before = snapshotWorkflow()
  wfSelected.value = n.id
  if (!wfRewireMode.value) wfSelectedEdge.value = null
  const grouped = wfMultiSelected.value.includes(n.id) && wfMultiSelected.value.length > 1
  // Shift+点击是多选操作，不要在这里清空已选集合
  if (!grouped && !ev.shiftKey) wfMultiSelected.value = []
  const ids = grouped ? [...wfMultiSelected.value] : [n.id]
  const starts = new Map<string, { x: number; y: number }>()
  for (const id of ids) {
    const node = workflowForm.nodes.find((x: any) => x.id === id)
    if (node) starts.set(id, { x: node.position.x, y: node.position.y })
  }
  const startX = ev.clientX
  const startY = ev.clientY
  wfDragMoved = false
  const move = (e: MouseEvent) => {
    const dx = e.clientX - startX
    const dy = e.clientY - startY
    if (Math.abs(dx) > 3 || Math.abs(dy) > 3) wfDragMoved = true
    for (const [id, start] of starts) {
      const node = workflowForm.nodes.find((x: any) => x.id === id)
      if (!node) continue
      node.position.x = Math.max(0, start.x + dx)
      node.position.y = Math.max(0, start.y + dy)
    }
  }
  const up = () => {
    window.removeEventListener('mousemove', move)
    window.removeEventListener('mouseup', up)
    if (wfDragMoved) wfDragEndedAt = Date.now()
    wfDragMoved = false
    commitWorkflowHistory(before)
  }
  window.addEventListener('mousemove', move)
  window.addEventListener('mouseup', up)
  ev.preventDefault()
}

function compareVersion(row: any) {
  let oldNodes: any[] = []
  try {
    oldNodes = JSON.parse(row.definition || '{}')?.nodes || []
  } catch {
    oldNodes = []
  }
  const current = workflowForm.nodes
  const oldMap = new Map(oldNodes.map((n: any) => [n.id, n]))
  const curMap = new Map(current.map((n: any) => [n.id, n]))
  const fields = ['type', 'name', 'prompt', 'modelName', 'agentId', 'toolId', 'argsTemplate',
    'knowledgeBaseId', 'mode', 'topK', 'queryTemplate', 'conditionType', 'conditionValue',
    'conditionSource', 'conditionStateKey',
    'trueNext', 'falseNext', 'next', 'title', 'requiredRole', 'timeoutMinutes', 'branches', 'joinNode', 'strategy']
  const rows: any[] = []
  for (const n of current) {
    const old = oldMap.get(n.id)
    if (!old) {
      rows.push({ id: n.id, name: n.name, change: t('agentMsg.diffAdded'), detail: t('agentMsg.newNode') })
      continue
    }
    const changed = fields.filter(f => JSON.stringify(n[f] ?? null) !== JSON.stringify(old[f] ?? null))
    if (changed.length) rows.push({ id: n.id, name: n.name, change: t('agentMsg.diffModified'), detail: changed.join(', ') })
  }
  for (const o of oldNodes) {
    if (!curMap.has(o.id)) rows.push({ id: o.id, name: o.name, change: t('agentMsg.diffDeleted'), detail: t('agentMsg.removedDraft') })
  }
  wfDiffRows.value = rows
  wfDiffTitle.value = `${t('agentMsg.diffTitlePrefix')}${row.version}${t('agentMsg.diffTitleMid')}`
  wfDiffVisible.value = true
}

function diffTag(change: string) {
  if (change === t('agentMsg.diffAdded')) return 'success'
  if (change === t('agentMsg.diffDeleted')) return 'danger'
  return 'warning'
}

function workflowNodeType(id: string) {
  return workflowForm.nodes.find((n: any) => n.id === id)?.type || ''
}

function addGraphEdge() {
  workflowForm.edges.push({ from: 'start', to: 'end', label: '' })
}

function buildWorkflowNode(type: string): any {
  const id = `n${Date.now().toString(36)}${Math.floor(Math.random() * 100)}`
  const node: any = { id, type, name: wfTypeText(type), next: 'end' }
  if (type === 'APPROVAL') node.title = t('agentMsg.approvalTitle')
  if (type === 'LLM') node.prompt = ''
  if (type === 'HANDOFF') { node.inputTemplate = '{{input}}'; node.returnRounds = 1 }
  if (type === 'STATE') { node.stateKey = ''; node.valueTemplate = '{{last}}' }
  if (type === 'TOOL') node.argsTemplate = '{}'
  if (type === 'KNOWLEDGE') { node.mode = 'hybrid'; node.topK = 5; node.queryTemplate = '' }
  if (type === 'APPROVAL') { node.requiredRole = ''; node.timeoutMinutes = 0 }
  if (type === 'PARALLEL') { node.branches = []; node.joinNode = ''; node.next = ''; node.strategy = 'ALL_OR_NOTHING' }
  if (type === 'LOOP') { node.bodyNode = ''; node.loopEnd = ''; node.itemsText = '["A","B"]'; node.maxIterations = 10; node.requiredRole = ''; node.timeoutMinutes = 0 }
  if (['LLM', 'AGENT', 'HANDOFF', 'STATE', 'TOOL', 'KNOWLEDGE'].includes(type)) { node.retryTimes = 0; node.onError = 'FAIL' }
  if (type === 'JOIN') node.next = 'end'
  if (type === 'CONDITION') {
    node.conditionType = 'contains'
    node.conditionValue = ''
    node.conditionSource = 'last'
    node.conditionStateKey = ''
    node.trueNext = 'end'
    node.falseNext = 'end'
  }
  return node
}

/** 找一个不与现有节点重叠的空位（网格扫描） */
function freeWorkflowPosition() {
  const taken = (x: number, y: number) => workflowForm.nodes.some((n: any) =>
    Math.abs(nodeX(n) - x) < 210 && Math.abs(nodeY(n) - y) < 100)
  // 先按 3 列向下排，保证新节点落在可视区域内，避免加到画布右侧看不见
  for (let row = 0; row < 30; row++) {
    for (let col = 0; col < 3; col++) {
      const x = 60 + col * 230
      const y = 40 + row * 110
      if (!taken(x, y)) return { x, y }
    }
  }
  return { x: 60, y: 40 + workflowForm.nodes.length * 110 }
}

/** 拖入位置若与其他节点重叠，向下顺延，避免卡片互相盖住 */
function avoidWorkflowOverlap(position: { x: number; y: number }) {
  const overlaps = (x: number, y: number) => workflowForm.nodes.some((n: any) =>
    Math.abs(nodeX(n) - x) < 210 && Math.abs(nodeY(n) - y) < 100)
  let { x, y } = position
  let guard = 0
  while (overlaps(x, y) && guard++ < 30) y += 110
  return { x, y }
}

/** 新增节点：列表模式按钮与画布拖入共用；position 为空时自动找空位 */
function insertWorkflowNode(type: string, position?: { x: number; y: number }) {
  const before = snapshotWorkflow()
  const node = buildWorkflowNode(type)
  position = position ? avoidWorkflowOverlap(position) : freeWorkflowPosition()
  node.position = position
  const endIndex = workflowForm.nodes.findIndex((n: any) => n.type === 'END')
  if (endIndex >= 0) workflowForm.nodes.splice(endIndex, 0, node)
  else workflowForm.nodes.push(node)
  ensureWorkflowPositions()
  if (position) node.position = position
  rewireWorkflow()
  wfSelected.value = node.id
  wfSelectedEdge.value = null
  commitWorkflowHistory(before)
  if (wfMode.value === 'canvas') openNodePanel(node)
  return node
}

function addWorkflowNode() {
  insertWorkflowNode(wfAddType.value)
}

function addWorkflowNodeOfType(type: string) {
  insertWorkflowNode(type)
}

function removeWorkflowNode(index: number) {
  const before = snapshotWorkflow()
  workflowForm.nodes.splice(index, 1)
  rewireWorkflow()
  wfSelectedEdge.value = null
  commitWorkflowHistory(before)
}

function moveWorkflowNode(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= workflowForm.nodes.length) return
  const [node] = workflowForm.nodes.splice(index, 1)
  workflowForm.nodes.splice(target, 0, node)
  rewireWorkflow()
}

function rewireWorkflow() {
  const nodes = workflowForm.nodes
  for (let i = 0; i < nodes.length; i++) {
    const n = nodes[i]
    if (n.type === 'END') continue
    const nextId = nodes[i + 1]?.id || 'end'
    if (n.type === 'CONDITION') {
      const ids = new Set(nodes.map((x: any) => x.id))
      if (!n.trueNext || !ids.has(n.trueNext)) n.trueNext = nextId
      if (!n.falseNext || !ids.has(n.falseNext)) n.falseNext = nextId
    } else if (n.type === 'PARALLEL') {
      const ids = new Set(nodes.map((x: any) => x.id))
      if (!n.joinNode || !ids.has(n.joinNode)) {
        n.joinNode = nodes.find((x: any) => x.type === 'JOIN')?.id || ''
      }
      n.next = n.joinNode || n.next
    } else if (!n.next || !nodes.some((x: any) => x.id === n.next)) {
      n.next = nextId
    }
  }
}

async function saveWorkflow(silent = false) {
  if (!workflowForm.nodes.some((n: any) => n.type === 'START')
      || !workflowForm.nodes.some((n: any) => n.type === 'END')) {
    ElMessage.warning(t('agentMsg.workflowRequired'))
    return
  }
  workflowSaving.value = true
  try {
    await http.put(`/agent/${agentId}/workflow`, {
      name: workflowForm.name,
      status: workflowForm.status,
      definition: {
        nodes: workflowForm.nodes.map((n: any) => {
          if (n.type !== 'LOOP') return n
          let items = n.items
          try { items = JSON.parse(n.itemsText || '[]') } catch {
            items = String(n.itemsText || '').split(/[\n,，]/).map((x: string) => x.trim()).filter(Boolean)
          }
          const { itemsText, ...rest } = n
          return { ...rest, items }
        }),
        edges: workflowForm.edges.filter((e: any) => e.from && e.to)
      }
    })
    workflowConfigured.value = true
    const fresh = await http.get(`/agent/${agentId}/workflow`) as any
    workflowPublishedVersion.value = fresh?.publishedVersion || ''
    workflowHasUnpublishedChanges.value = !!fresh?.hasUnpublishedChanges
    if (!silent) ElMessage.success(t('agentMsg.workflowSaved'))
    return true
  } catch (e: any) {
    if (!silent) ElMessage.error(e?.response?.data?.message || e?.message || t('agentMsg.workflowSaveFailed'))
    return false
  } finally {
    workflowSaving.value = false
  }
}

async function publishWorkflow() {
  workflowPublishing.value = true
  try {
    const saved = await saveWorkflow()
    if (!saved) return
    const res = await http.post(`/agent/${agentId}/workflow/publish`) as any
    workflowPublishedVersion.value = res.version
    workflowHasUnpublishedChanges.value = false
    ElMessage.success(`${t('agentMsg.published')}${res.version}`)
  } finally {
    workflowPublishing.value = false
  }
}

async function openWorkflowVersions() {
  wfVersions.value = await http.get(`/agent/${agentId}/workflow/versions`) as any
  wfVersionsVisible.value = true
}

async function rollbackWorkflow(version: string) {
  await ElMessageBox.confirm(`${t('agentMsg.rollbackWorkflowConfirm')}${version}${t('agentMsg.rollbackWorkflowSuffix')}`, t('agentMsg.rollbackWorkflow'),
    { type: 'warning', confirmButtonText: t('agentMsg.rollbackWorkflow'), cancelButtonText: t('agentDlg.cancel') })
  const res = await http.post(`/agent/${agentId}/workflow/versions/${version}/rollback`) as any
  ElMessage.success(`${t('agentMsg.rolledBackPublished')}${res.version}`)
  wfVersionsVisible.value = false
  const data = await http.get(`/agent/${agentId}/workflow`) as any
  workflowForm.name = data.name
  workflowForm.status = data.status
  workflowForm.nodes = (data.definition?.nodes || []).map((n: any) => ({
    ...n, ...(n.type === 'CONDITION' ? { conditionSource: n.conditionSource || 'last' } : {})
  }))
  ensureWorkflowPositions()
  workflowPublishedVersion.value = data.publishedVersion || ''
  workflowHasUnpublishedChanges.value = !!data.hasUnpublishedChanges
}

async function resumeWorkflow() {
  if (!workflowRunResult.value?.id) return
  try {
    await ElMessageBox.confirm(t('wf.resumeConfirm'), t('wf.resumeTitle'), { type: 'warning' })
  } catch {
    return
  }
  workflowResuming.value = true
  try {
    await http.post(`/workflow/runs/${workflowRunResult.value.id}/resume?allowSideEffects=true`, {})
    workflowRunResult.value = await http.get(`/workflow/runs/${workflowRunResult.value.id}`) as any
    ElMessage.success(t('wf.resumed'))
  } finally {
    workflowResuming.value = false
  }
}

async function runWorkflow() {
  if (!workflowRunInput.value.trim()) { ElMessage.warning(t('agentMsg.testInputRequired')); return }
  // 测试必须跑“当前草稿”：先保存草稿，再以 draft=true 运行，避免实际跑旧的已发布版本。
  if (!(await saveWorkflow(true))) return
  workflowRunning.value = true
  stopReplay()
  workflowRunResult.value = null
  try {
    workflowRunResult.value = await http.post(`/agent/${agentId}/workflow/run`,
      { input: workflowRunInput.value.trim(), draft: true }, { timeout: 180000 }) as any
    if (workflowRunResult.value?.status === 'WAITING_APPROVAL') {
      ElMessage.info(t('agentMsg.pausedApproval'))
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || t('agentMsg.workflowRunFailed'))
  } finally {
    workflowRunning.value = false
  }
}

const wfReplaySteps = computed<any[]>(() => workflowRunResult.value?.nodeRuns || [])
const wfReplayCurrent = computed<any>(() => wfReplaySteps.value[wfReplayIndex.value] || null)

function replayDuration(step: any): number | null {
  if (!step?.startedAt || !step?.endedAt) return null
  const ms = new Date(step.endedAt).getTime() - new Date(step.startedAt).getTime()
  return Number.isFinite(ms) && ms >= 0 ? ms : null
}

function clearReplayTimer() {
  if (wfReplayTimer) {
    clearTimeout(wfReplayTimer)
    wfReplayTimer = null
  }
}

function focusReplayNode() {
  const step = wfReplayCurrent.value
  const wrap = wfCanvasEl.value
  if (!step || !wrap || wfMode.value !== 'canvas') return
  const node = workflowForm.nodes.find((n: any) => n.id === step.nodeId)
  if (!node) return
  const x = nodeX(node) * wfZoom.value
  const y = nodeY(node) * wfZoom.value
  if (x < wrap.scrollLeft || x > wrap.scrollLeft + wrap.clientWidth - 220) {
    wrap.scrollLeft = Math.max(0, x - 60)
  }
  if (y < wrap.scrollTop || y > wrap.scrollTop + wrap.clientHeight - 100) {
    wrap.scrollTop = Math.max(0, y - 40)
  }
}

function startReplay() {
  if (!wfReplaySteps.value.length) return
  clearReplayTimer()
  wfMode.value = 'canvas'
  wfSelectedEdge.value = null
  wfNodePanel.value = false
  if (wfReplayIndex.value < 0 || wfReplayIndex.value >= wfReplaySteps.value.length - 1) {
    wfReplayIndex.value = -1
  }
  wfReplayPlaying.value = true
  advanceReplay()
}

function advanceReplay() {
  clearReplayTimer()
  if (!wfReplayPlaying.value) return
  if (wfReplayIndex.value >= wfReplaySteps.value.length - 1) {
    wfReplayPlaying.value = false
    return
  }
  wfReplayIndex.value += 1
  focusReplayNode()
  wfReplayTimer = setTimeout(advanceReplay, 1100)
}

function toggleReplay() {
  if (wfReplayPlaying.value) {
    wfReplayPlaying.value = false
    clearReplayTimer()
    return
  }
  startReplay()
}

function stepReplay(delta: number) {
  if (!wfReplaySteps.value.length) return
  wfReplayPlaying.value = false
  clearReplayTimer()
  const next = Math.min(wfReplaySteps.value.length - 1, Math.max(0, wfReplayIndex.value + delta))
  wfReplayIndex.value = next
  focusReplayNode()
}

function stopReplay() {
  wfReplayPlaying.value = false
  clearReplayTimer()
  wfReplayIndex.value = -1
}

function goApprovals() {
  workflowVisible.value = false
  router.push('/approvals')
}

watch(wfMode, mode => { if (mode !== 'canvas') closeNodePanel() })
onMounted(() => window.addEventListener('keydown', onWorkflowKeydown))
onUnmounted(() => {
  window.removeEventListener('keydown', onWorkflowKeydown)
  clearReplayTimer()
})
  return { workflowVisible, workflowConfigured, workflowSaving, workflowRunning, workflowResuming, wfAddType, wfTypes, workflowForm, workflowRunInput, workflowRunResult, workflowPublishedVersion, workflowHasUnpublishedChanges, workflowPublishing, wfVersionsVisible, wfVersions, wfMode, wfSelected, wfLinkMode, wfLinkFrom, wfHistory, wfHistoryIndex, wfSelectedEdge, wfDraggingType, wfDropActive, wfLinkDrag, wfNodePanel, wfRewireMode, wfMultiSelected, wfClipboardSize, wfReplayIndex, wfReplayPlaying, wfReplayTimer, wfBoxSelect, wfClipboard, wfNodeEditBefore, wfSelectedNode, wfBoxStyle, wfCanvasEl, wfDiffVisible, wfDiffTitle, wfDiffRows, wfZoom, wfTemplatesVisible, wfTemplates, applyWorkflowData, openWorkflow, wfTypeText, wfTagType, wfTargets, wfBranchTargets, ensureWorkflowPositions, openNodePanel, closeNodePanel, openNodeInList, removeSelectedNode, snapshotWorkflow, resetWorkflowHistory, commitWorkflowHistory, restoreWorkflow, undoWorkflow, redoWorkflow, onWorkflowKeydown, onPaletteDragStart, onCanvasDrop, startLinkDrag, connectNodes, startRewireEdge, finishRewire, cancelRewire, selectEdge, deleteSelectedEdge, removeGraphEdge, fitCanvas, nodeX, nodeY, wfCanvasSize, wfEdges, wfLinkPath, wfRunClass, wfRunNodeStatus, toggleLinkMode, wfDragMoved, wfDragEndedAt, onCanvasNodeClick, zoomCanvas, autoLayoutWorkflow, editWorkflowNode, startBoxSelect, startCanvasPan, openWorkflowTemplates, applyWorkflowTemplate, copySelectedNodes, pasteNodes, removeMultiSelected, startNodeDrag, compareVersion, diffTag, workflowNodeType, addGraphEdge, buildWorkflowNode, freeWorkflowPosition, avoidWorkflowOverlap, insertWorkflowNode, addWorkflowNode, addWorkflowNodeOfType, removeWorkflowNode, moveWorkflowNode, rewireWorkflow, saveWorkflow, publishWorkflow, openWorkflowVersions, rollbackWorkflow, resumeWorkflow, runWorkflow, wfReplaySteps, wfReplayCurrent, replayDuration, clearReplayTimer, focusReplayNode, startReplay, advanceReplay, toggleReplay, stepReplay, stopReplay, goApprovals }
}
