import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElLoading, ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import http from '../api/http'
import { escapeHtml, renderGateFailures } from '../utils/gateDetails'
import { buildKbRetrievalConfig, buildModelRoutingConfig } from '../utils/agentRoutingConfig'

interface RefRow { id: number; name: string; description?: string; type?: string; isBuiltin?: boolean; enabled: boolean; missing?: boolean }
type RefType = 'skill' | 'tool' | 'sub'
interface VariableRow { key: string; type: string; required: boolean; defaultValue: string; description: string }

export function useAgentEditorActions(ctx: any) {
  const { agentId, kbRows, versions, grantedIds, versionsVisible, deployVisible, deployInfo, deployment, publishInfoVisible, publishInfoLoading, testLinkCreating, testWidgetUrl, testWidgetIframe, manualCopyVisible, manualCopyText, manualCopyInput, deploying, deployLogsVisible, deployLogs, deployHistoryVisible, deployHistory, runtimeToken, runtimeKeys, runtimeStats, deployReplicas, deployProfileId, deployProfiles, deployMaxReplicas, runtimeKeyDialogVisible, runtimeKeySaving, runtimeKeyForm, createdRuntimeKey, newRuntimeKeyVisible, saving, form, tagsStr, writeToolPatternsText, redactPatternsText, writeToolAmountLimitsText, enabledKbCount } = ctx
  const { t } = useI18n()
  const router = useRouter()

const skillRows = ref<RefRow[]>([])
const toolRows = ref<RefRow[]>([])
const subRows = ref<RefRow[]>([])
const skillCatalog = ref<any[]>([])
const toolCatalog = ref<any[]>([])
const agentCatalog = ref<any[]>([])
const picker = reactive({ visible: false, type: 'skill' as RefType, selected: [] as number[] })
const credentialVisible = ref(false)
const credentials = ref<any[]>([])
const credentialFormVisible = ref(false)
const credentialSaving = ref(false)
const credentialForm = reactive({
  id: 0, name: '', type: 'API_KEY', content: '', toolId: null as number | null, enabled: true
})
const scheduleVisible = ref(false)
const schedules = ref<any[]>([])
const scheduleFormVisible = ref(false)
const scheduleSaving = ref(false)
const scheduleForm = reactive({
  id: 0, name: '', cronExpr: '0 */5 * * * *', inputParamsText: '{}',
  timeoutMs: 60000, notifyType: 'STORE', webhookUrl: '', enabled: true
})
const resultsVisible = ref(false)
const scheduleResults = ref<any[]>([])
const resultsTitle = ref('')
const integrationVisible = ref(false)
const apiKeys = ref<any[]>([])
const mcpEndpoint = `${window.location.origin}/mcp`
const createdKey = ref('')
const channelsVisible = ref(false)
const channelMessagesVisible = ref(false)
const channelMessages = ref<any[]>([])
const messagesChannelName = ref('')
const channels = ref<any[]>([])
const channelCreateVisible = ref(false)
const channelSaving = ref(false)
const channelTestingId = ref(0)
const createdInbound = ref('')
const channelForm = reactive({ name: '', type: 'WEBHOOK', outboundUrl: '', secret: '', verificationToken: '', encryptKey: '', encodingAesKey: '', corpId: '', rateLimitPerMinute: 60 })
const widgetSnippet = ref('')
const widgetKey = ref('')
const widgetCreating = ref(false)
const keyCreateVisible = ref(false)
const keySaving = ref(false)
const keyForm = reactive({ name: '', channel: 'API', dailyLimit: 0, minuteLimit: 0 })
const optimizeVisible = ref(false)
const optimizing = ref(false)
const optimizeStyle = ref('')
const optimizeInstruction = ref('')
const optimizeResult = ref('')
const optimizeUsage = ref<any>(null)
const optimizeModel = ref('')
const promptTemplates = ref<any[]>([])
const templateVisible = ref(false)
const templateId = ref(0)
const selectedTemplate = computed(() => promptTemplates.value.find(t => t.id === templateId.value))
const enabledSkillCount = computed(() => skillRows.value.filter(r => r.enabled && !r.missing).length)
const enabledToolCount = computed(() => toolRows.value.filter(r => r.enabled && !r.missing).length)
const enabledSubCount = computed(() => subRows.value.filter(r => r.enabled && !r.missing).length)
const pickerTitle = computed(() => ({ skill: t('wf.pickerAddSkill'), tool: t('wf.pickerAddTool'), sub: t('wf.pickerAddSub') }[picker.type]))
const pickerPlaceholder = computed(() => ({ skill: t('wf.pickerSelectSkill'), tool: t('wf.pickerSelectTool'), sub: t('wf.pickerSelectSub') }[picker.type]))
const pickerOptions = computed(() => {
  const rows = picker.type === 'skill' ? skillRows.value : picker.type === 'tool' ? toolRows.value : subRows.value
  const existing = new Set(rows.map(r => r.id))
  const source = picker.type === 'skill' ? skillCatalog.value : picker.type === 'tool' ? toolCatalog.value : agentCatalog.value
  return source.filter(o => !existing.has(o.id))
})
const debugMsgs = ref<any[]>([])
const debugInput = ref('')
const debugRunning = ref(false)
const dbgBox = ref<HTMLElement>()
function buildRefRows(type: RefType, bindings: any[], catalog: any[], target: any) {
  const bound = new Map<number, any>()
  for (const b of bindings || []) {
    bound.set(type === 'skill' ? b.skillId : type === 'tool' ? b.toolId : b.subAgentId, b)
  }
  const rows: RefRow[] = []
  for (const item of catalog) {
    const b = bound.get(item.id)
    if (!b) continue
    rows.push({
      id: item.id, name: item.name, description: item.description,
      type: item.toolType || item.skillType, isBuiltin: !!item.isBuiltin,
      enabled: b.enabled !== false, missing: false
    })
  }
  const visibleIds = new Set(catalog.map((o: any) => o.id))
  for (const b of bindings || []) {
    const id = type === 'skill' ? b.skillId : type === 'tool' ? b.toolId : b.subAgentId
    if (!visibleIds.has(id)) {
      const label = type === 'skill' ? t('agentMsg.typeSkill') : type === 'tool' ? t('agentMsg.typeTool') : t('agentMsg.typeAgent')
      rows.push({ id, name: `${t('agentMsg.invalidSuffix')}${label} #${id}${t('agentMsg.invalidSuffix2')}`, enabled: false, missing: true })
    }
  }
  target.value = rows
}

async function openCredentials() {
  await loadCredentials()
  credentialVisible.value = true
}

async function loadCredentials() {
  const res = await http.get(`/agent/${agentId}/credentials`) as any
  credentials.value = res?.list || []
}

function toolName(id?: number) {
  if (!id) return '—'
  return toolCatalog.value.find((t: any) => t.id === id)?.name || (`${t('agentMsg.typeTool')} #${id}`)
}

function openCredentialCreate() {
  Object.assign(credentialForm, {
    id: 0, name: '', type: 'API_KEY', content: '', toolId: null, enabled: true
  })
  credentialFormVisible.value = true
}

function openCredentialEdit(row: any) {
  Object.assign(credentialForm, {
    id: row.id, name: row.name || '', type: row.type || 'API_KEY',
    content: '', toolId: row.toolId || null, enabled: row.status !== 'DISABLED'
  })
  credentialFormVisible.value = true
}

async function saveCredential() {
  if (!credentialForm.name.trim()) { ElMessage.warning(t('agentDlg.keyNameRequired')); return }
  if (!credentialForm.id && !credentialForm.content.trim()) { ElMessage.warning(t('agentMsg.credContentRequired')); return }
  credentialSaving.value = true
  try {
    const payload: any = {
      name: credentialForm.name.trim(),
      type: credentialForm.type,
      toolId: credentialForm.toolId,
      status: credentialForm.enabled ? 'ENABLED' : 'DISABLED'
    }
    if (credentialForm.content.trim()) payload.content = credentialForm.content
    if (credentialForm.id) await http.put(`/agent/${agentId}/credentials/${credentialForm.id}`, payload)
    else await http.post(`/agent/${agentId}/credentials`, payload)
    ElMessage.success(t('agentDlg.saved'))
    credentialFormVisible.value = false
    await loadCredentials()
  } finally {
    credentialSaving.value = false
  }
}

async function removeCredential(row: any) {
  await ElMessageBox.confirm(`${t('agentMsg.deleteCredPrefix')}${row.name}${t('agentMsg.deleteCredMid')}`, t('agentMsg.deleteCredTitle'),
    { type: 'warning', confirmButtonText: t('agentDlg.delete'), cancelButtonText: t('agentDlg.cancel') })
  await http.delete(`/agent/${agentId}/credentials/${row.id}`)
  ElMessage.success(t('agentDlg.deleted'))
  await loadCredentials()
}

async function openSchedules() {
  await loadSchedules()
  scheduleVisible.value = true
}

async function loadSchedules() {
  const res = await http.get(`/agent/${agentId}/schedules`) as any
  schedules.value = res?.list || []
}

function openScheduleCreate() {
  Object.assign(scheduleForm, {
    id: 0, name: '', cronExpr: '0 */5 * * * *', inputParamsText: '{}',
    timeoutMs: 60000, notifyType: 'STORE', webhookUrl: '', enabled: true
  })
  scheduleFormVisible.value = true
}

function openScheduleEdit(row: any) {
  Object.assign(scheduleForm, {
    id: row.id,
    name: row.name || '',
    cronExpr: row.cronExpr || '',
    inputParamsText: JSON.stringify(row.inputParams || {}, null, 2),
    timeoutMs: row.timeoutMs ?? 60000,
    notifyType: row.notifyType || 'STORE',
    webhookUrl: row.webhookUrl || '',
    enabled: row.status !== 'DISABLED'
  })
  scheduleFormVisible.value = true
}

async function saveSchedule() {
  if (!scheduleForm.name.trim() || !scheduleForm.cronExpr.trim()) {
    ElMessage.warning(t('agentDlg.nameCronRequired')); return
  }
  let params: any
  try {
    params = JSON.parse(scheduleForm.inputParamsText || '{}')
  } catch {
    ElMessage.error(t('agentDlg.invalidJson')); return
  }
  scheduleSaving.value = true
  try {
    const payload = {
      name: scheduleForm.name.trim(),
      cronExpr: scheduleForm.cronExpr.trim(),
      inputParams: params,
      timeoutMs: scheduleForm.timeoutMs,
      notifyType: scheduleForm.notifyType,
      webhookUrl: scheduleForm.webhookUrl || null,
      status: scheduleForm.enabled ? 'ENABLED' : 'DISABLED'
    }
    if (scheduleForm.id) await http.put(`/agent/${agentId}/schedules/${scheduleForm.id}`, payload)
    else await http.post(`/agent/${agentId}/schedules`, payload)
    ElMessage.success(t('agentDlg.saved'))
    scheduleFormVisible.value = false
    await loadSchedules()
  } finally {
    scheduleSaving.value = false
  }
}

async function toggleSchedule(row: any) {
  await http.put(`/agent/${agentId}/schedules/${row.id}`, {
    status: row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  })
  await loadSchedules()
}

async function runSchedule(row: any) {
  const res = await http.post(`/agent/${agentId}/schedules/${row.id}/run`) as any
  ElMessage[res.status === 'SUCCESS' ? 'success' : 'error'](
    res.status === 'SUCCESS' ? t('agentDlg.execSuccess') : (t('agentDlg.execFailed') + res.message))
  await loadSchedules()
}

async function openResults(row: any) {
  resultsTitle.value = `${t('agentDlg.resultTitle')}${row.name}`
  scheduleResults.value = await http.get(`/agent/${agentId}/schedules/${row.id}/results`) as any
  resultsVisible.value = true
}

async function removeSchedule(row: any) {
  await ElMessageBox.confirm(`${t('agentDlg.deleteSchedulePrefix')}${row.name}${t('agentDlg.deleteScheduleMid')}`, t('agentDlg.deleteScheduleTitle'),
    { type: 'warning', confirmButtonText: t('agentDlg.delete'), cancelButtonText: t('agentDlg.cancel') })
  await http.delete(`/agent/${agentId}/schedules/${row.id}`)
  ElMessage.success(t('agentDlg.deleted'))
  await loadSchedules()
}

async function openIntegration() {
  integratedAgentId.value = agentId
  createdKey.value = ''
  widgetSnippet.value = ''
  widgetKey.value = ''
  await loadApiKeys()
  integrationVisible.value = true
}

async function openChannels() {
  channels.value = await http.get(`/agent/${agentId}/channels`) as any
  createdInbound.value = ''
  channelsVisible.value = true
}

async function createChannel() {
  channelSaving.value = true
  try {
    const res = await http.post(`/agent/${agentId}/channels`, {
      name: channelForm.name,
      type: channelForm.type,
      outboundUrl: channelForm.outboundUrl,
      secret: channelForm.secret,
      config: {
        verificationToken: channelForm.verificationToken,
        encryptKey: channelForm.encryptKey,
        encodingAesKey: channelForm.encodingAesKey,
        corpId: channelForm.corpId,
        rateLimitPerMinute: channelForm.rateLimitPerMinute
      }
    }) as any
    ElMessage.success(t('agentDlg.channelCreated'))
    channelCreateVisible.value = false
    await openChannels()
    createdInbound.value = location.origin + res.inboundPath
    Object.assign(channelForm, { name: '', type: 'WEBHOOK', outboundUrl: '', secret: '',
      verificationToken: '', encryptKey: '', encodingAesKey: '', corpId: '', rateLimitPerMinute: 60 })
  } finally {
    channelSaving.value = false
  }
}

function inboundUrl(row: any) {
  return location.origin + row.inboundPath
}

async function openChannelMessages(row: any) {
  messagesChannelName.value = row.name
  const data = await http.get(`/agent/${agentId}/channels/${row.id}/messages`, { params: { pageSize: 50 } }) as any
  channelMessages.value = data?.list || []
  channelMessagesVisible.value = true
}

async function copyInbound(row: any) {
  await copyText(inboundUrl(row))
}

async function testChannel(row: any) {
  channelTestingId.value = row.id
  try {
    const res = await http.post(`/agent/${agentId}/channels/${row.id}/test`) as any
    if (res.success) ElMessage.success(`${t('agentDlg.outboundOk')}${res.latencyMs}ms)`)
    else ElMessage.error(`${t('agentDlg.outboundFail')}${res.message}`)
  } finally {
    channelTestingId.value = 0
  }
}

async function toggleChannel(row: any) {
  await http.put(`/agent/${agentId}/channels/${row.id}`, {
    status: row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  })
  await openChannels()
}

async function removeChannel(row: any) {
  await ElMessageBox.confirm(`${t('agentDlg.deleteChannelPrefix')}${row.name}${t('agentDlg.deleteChannelMid')}`, t('agentDlg.deleteChannelTitle'),
    { type: 'warning', confirmButtonText: t('agentDlg.delete'), cancelButtonText: t('agentDlg.cancel') })
  await http.delete(`/agent/${agentId}/channels/${row.id}`)
  ElMessage.success(t('agentDlg.deleted'))
  await openChannels()
}

async function loadApiKeys() {
  const res = await http.get(`/agent/${agentId}/api-keys`) as any
  apiKeys.value = res?.list || []
}

function openKeyCreate() {
  Object.assign(keyForm, { name: '', channel: 'API', dailyLimit: 0, minuteLimit: 0 })
  keyCreateVisible.value = true
}

async function createKey() {
  if (!keyForm.name.trim()) { ElMessage.warning(t('agentDlg.keyNameRequired')); return }
  keySaving.value = true
  try {
    const res = await http.post(`/agent/${agentId}/api-keys`, {
      name: keyForm.name.trim(), channel: keyForm.channel,
      dailyLimit: keyForm.dailyLimit, minuteLimit: keyForm.minuteLimit
    }) as any
    createdKey.value = res.apiKey
    keyCreateVisible.value = false
    await loadApiKeys()
  } finally {
    keySaving.value = false
  }
}

async function createWidgetKey() {
  widgetCreating.value = true
  try {
    const res = await http.post(`/agent/${agentId}/api-keys`, {
      name: t('agentMsg.widgetName'), channel: 'WIDGET', dailyLimit: 0, minuteLimit: 60
    }) as any
    widgetKey.value = res.apiKey
    const src = `${secureWidgetOrigin()}/widget?key=${encodeURIComponent(res.apiKey)}`
    widgetSnippet.value = `<iframe src="${src}" :title="t('widget.defaultName')" style="width:100%;height:640px;border:0;border-radius:12px"></iframe>`
    ElMessage.success(t('agentDlg.widgetGenerated'))
    await loadApiKeys()
  } finally {
    widgetCreating.value = false
  }
}

/**
 * 内网 HTTP 页面不是 secure context，麦克风会被浏览器拒绝。
 * 本地 UTM 部署使用 app.<ip-with-dashes>.sslip.io 的 HTTPS 别名；
 * 生产域名/已 HTTPS 页面保持当前 origin。
 */
function secureWidgetOrigin(): string {
  if (window.isSecureContext) return window.location.origin
  const host = window.location.hostname
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host)) {
    return `https://aap.${host.replace(/\./g, '-')}.sslip.io`
  }
  return window.location.origin
}

function openManualCopy(text: string) {
  manualCopyText.value = text
  manualCopyVisible.value = true
  nextTick(() => {
    const el = (manualCopyInput.value?.textarea
      || document.querySelector('.manual-copy-dialog textarea')) as HTMLTextAreaElement | null
    el?.focus()
    el?.select()
  })
}

function legacyCopy(text: string): boolean {
  const area = document.createElement('textarea')
  area.value = text
  area.setAttribute('readonly', '')
  area.style.position = 'fixed'
  area.style.top = '-1000px'
  area.style.opacity = '0'
  document.body.appendChild(area)
  try {
    area.focus()
    area.select()
    area.setSelectionRange(0, text.length)
    return document.execCommand('copy')
  } finally {
    document.body.removeChild(area)
  }
}

function selectAllText(event: FocusEvent) {
  const el = event.target as HTMLInputElement | null
  if (el?.select) el.select()
}

async function copyText(text: string) {
  if (!text) return
  try {
    // HTTP 内网地址不是 secure context，navigator.clipboard 可能不存在或直接抛错。
    if (window.isSecureContext && navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else if (!legacyCopy(text)) {
      throw new Error('legacy copy rejected')
    }
    ElMessage.success(t('agentDlg.copied'))
  } catch {
    try {
      if (legacyCopy(text)) {
        ElMessage.success(t('agentDlg.copied'))
        return
      }
    } catch {
      // fall through
    }
    ElMessage.info(t('agentDlg.copyFallbackOpened'))
    openManualCopy(text)
  }
}

// ---- 容器部署 ----
const deployTagType = computed(() => ({
  RUNNING: 'success', STOPPED: 'info', FAILED: 'danger', PENDING: 'warning'
} as Record<string, string>)[deployment.value?.status] || 'info')

const deployStatusText = computed(() =>
  t('deploy.statuses.' + (deployment.value?.status || 'PENDING')))

const deployCurl = computed(() => {
  const endpoint = deployment.value?.endpoint || 'http://localhost:19000/v1'
  const token = runtimeToken.value || '<访问令牌>'
  return `curl -s ${endpoint}/chat/completions \\\n  -H 'Authorization: Bearer ${token}' \\\n  -H 'Content-Type: application/json' \\\n  -d '{"model":"agent","messages":[{"role":"user","content":"你好"}]}'`
})

async function loadPublishInfo() {
  publishInfoLoading.value = true
  try {
    const data = await http.get(`/agent/${agentId}/deployment`) as any
    deployInfo.enabled = data?.enabled !== false
    deployInfo.dockerAvailable = !!data?.dockerAvailable
    deployInfo.dockerVersion = data?.dockerVersion || ''
    deployInfo.image = data?.image || ''
    deployment.value = data?.deployment || null
  } catch {
    deployment.value = null
  } finally {
    publishInfoLoading.value = false
  }
}

async function openPublishInfo() {
  publishInfoVisible.value = true
  await loadPublishInfo()
}

async function createTestLink() {
  testLinkCreating.value = true
  try {
    const res = await http.post(`/agent/${agentId}/api-keys`, {
      name: t('deploy.testKeyName'),
      channel: 'WIDGET',
      dailyLimit: 0,
      minuteLimit: 60
    }) as any
    const url = `${secureWidgetOrigin()}/widget?key=${encodeURIComponent(res.apiKey)}`
    testWidgetUrl.value = url
    testWidgetIframe.value = `<iframe src="${url}" style="width:100%;height:640px;border:0;border-radius:12px" title="Agent test"></iframe>`
    await loadApiKeys()
    ElMessage.success(t('deploy.testLinkCreated'))
  } finally {
    testLinkCreating.value = false
  }
}

function openTestPage() {
  if (testWidgetUrl.value) window.open(testWidgetUrl.value, '_blank')
}

async function openDeploy() {
  deployVisible.value = true
  await refreshDeploy()
}

async function loadDeployProfiles() {
  try {
    deployProfiles.value = await http.get('/deploy-resource-profiles') as any || []
  } catch {
    deployProfiles.value = []
  }
}

async function refreshDeploy() {
  try {
    if (!deployProfiles.value.length) await loadDeployProfiles()
    const data = await http.get(`/agent/${agentId}/deployment`) as any
    deployInfo.enabled = data.enabled !== false
    deployInfo.dockerAvailable = !!data.dockerAvailable
    deployInfo.dockerVersion = data.dockerVersion || ''
    deployInfo.image = data.image || ''
    deployment.value = data.deployment || null
    deployReplicas.value = deployment.value?.replicaCount || 1
    deployProfileId.value = deployment.value?.deployProfileId || deployProfiles.value[0]?.id || null
    if (deployReplicas.value > deployMaxReplicas.value) deployReplicas.value = deployMaxReplicas.value
    runtimeToken.value = ''
    if (deployment.value?.hasToken) {
      try {
        runtimeToken.value = ((await http.get(`/agent/${agentId}/deployment/token`) as any).token) || ''
      } catch { /* 旧部署可能没有令牌 */ }
    }
    await Promise.all([loadRuntimeKeys(), loadDeployStats()])
  } catch {
    deployment.value = null
    runtimeToken.value = ''
    runtimeKeys.value = []
    runtimeStats.value = { callers: [] }
  }
}

async function doDeploy() {
  if (deployInfo.enabled === false) {
    ElMessage.warning(t('deploy.frozen'))
    return
  }
  if (!deployInfo.dockerAvailable) {
    ElMessage.warning(t('deploy.dockerMissing'))
    return
  }
  deploying.value = true
  try {
    deployment.value = await http.post(`/agent/${agentId}/deployment`, {
      replicas: deployReplicas.value, deployProfileId: deployProfileId.value
    }) as any
    await refreshDeploy()
    ElMessage.success(t('deploy.ok'))
  } finally {
    deploying.value = false
  }
}

async function deployAction(action: 'start' | 'stop') {
  const data = await http.post(`/agent/${agentId}/deployment/${action}`, {}) as any
  deployment.value = data
  ElMessage.success(t(action === 'start' ? 'deploy.started' : 'deploy.stopped'))
}

async function openDeployLogs() {
  const data = await http.get(`/agent/${agentId}/deployment/logs`, { params: { tail: 200, all: true } }) as any
  deployLogs.value = data?.logs || ''
  deployLogsVisible.value = true
}

async function openDeployHistory() {
  deployHistory.value = await http.get(`/agent/${agentId}/deployment/history`,
    { params: { limit: 50 } }) as any || []
  deployHistoryVisible.value = true
}

function deployActionText(action: string) {
  const key = `deploy.actions.${action}`
  return t(key) === key ? action : t(key)
}

function deployActionTag(action: string) {
  return ({ DEPLOY: 'primary', START: 'success', STOP: 'warning', REMOVE: 'danger' } as any)[action] || 'info'
}

async function removeDeploy() {
  await ElMessageBox.confirm(t('deploy.confirmRemove'), t('deploy.remove'), { type: 'warning' })
  await http.delete(`/agent/${agentId}/deployment`)
  deployment.value = null
  ElMessage.success(t('deploy.removed'))
}

async function rollbackDeployment(version: string) {
  await ElMessageBox.confirm(`${t('deploy.rollbackConfirm')}${version}${t('deploy.rollbackConfirmSuffix')}`,
    t('deploy.rollbackDeploy'), { type: 'warning' })
  await http.post(`/agent/${agentId}/versions/${version}/rollback`)
  await http.post(`/agent/${agentId}/deployment`, {})
  ElMessage.success(t('deploy.rollbackDeployed'))
  deployHistoryVisible.value = false
  setTimeout(() => router.go(0), 800)
}

const mainTokenUsage = computed(() => {
  const row = (runtimeStats.value?.callers || []).find((c: any) => c.caller === 'default')
  return row?.requests || 0
})

function keyUsage(row: any) {
  const stat = (runtimeStats.value?.callers || []).find((c: any) => c.caller === row.callerId)
  return stat?.requests || 0
}

async function loadRuntimeKeys() {
  try {
    runtimeKeys.value = await http.get(`/agent/${agentId}/runtime-keys`) as any || []
  } catch {
    runtimeKeys.value = []
  }
}

async function loadDeployStats() {
  if (!deployment.value) {
    runtimeStats.value = { callers: [] }
    return
  }
  try {
    runtimeStats.value = await http.get(`/agent/${agentId}/deployment/stats`) as any || { callers: [] }
  } catch {
    runtimeStats.value = { callers: [] }
  }
}

function openKeyDialog(row?: any) {
  runtimeKeyForm.id = row?.id || 0
  runtimeKeyForm.name = row?.name || ''
  runtimeKeyForm.dailyQuota = row?.dailyQuota || 0
  runtimeKeyDialogVisible.value = true
}

async function saveRuntimeKey() {
  if (!runtimeKeyForm.name.trim()) {
    ElMessage.warning(t('deploy.keyNamePh'))
    return
  }
  runtimeKeySaving.value = true
  try {
    if (runtimeKeyForm.id) {
      await http.put(`/agent/${agentId}/runtime-keys/${runtimeKeyForm.id}`,
        { name: runtimeKeyForm.name.trim(), dailyQuota: runtimeKeyForm.dailyQuota })
    } else {
      const created = await http.post(`/agent/${agentId}/runtime-keys`,
        { name: runtimeKeyForm.name.trim(), dailyQuota: runtimeKeyForm.dailyQuota }) as any
      createdRuntimeKey.value = created?.apiKey || ''
      newRuntimeKeyVisible.value = true
    }
    runtimeKeyDialogVisible.value = false
    await applyRuntimeKeys()
  } finally {
    runtimeKeySaving.value = false
  }
}

async function toggleRuntimeKey(row: any) {
  await http.put(`/agent/${agentId}/runtime-keys/${row.id}`,
    { status: row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED' })
  await applyRuntimeKeys()
}

async function removeRuntimeKey(row: any) {
  await ElMessageBox.confirm(`${t('deploy.confirmDeleteKey')}${row.name}?`,
    t('deploy.delete'), { type: 'warning' })
  await http.delete(`/agent/${agentId}/runtime-keys/${row.id}`)
  await applyRuntimeKeys()
  ElMessage.success(t('deploy.keyDeleted'))
}

/** Key 变更后重建容器使其生效（未部署则等下次部署） */
async function applyRuntimeKeys() {
  if (deployment.value) {
    deploying.value = true
    try {
      deployment.value = await http.post(`/agent/${agentId}/deployment`, {
        replicas: deployReplicas.value, deployProfileId: deployProfileId.value
      }) as any
      await refreshDeploy()
      ElMessage.success(t('deploy.keySynced'))
    } finally {
      deploying.value = false
    }
  } else {
    await loadRuntimeKeys()
    ElMessage.info(t('deploy.keySavedPending'))
  }
}

async function rotateDeployToken() {
  await ElMessageBox.confirm(t('deploy.rotateConfirm'), t('deploy.rotateToken'), { type: 'warning' })
  deployment.value = await http.post(`/agent/${agentId}/deployment/token/rotate`, {
    replicas: deployReplicas.value, deployProfileId: deployProfileId.value
  }) as any
  await refreshDeploy()
  ElMessage.success(t('deploy.rotated'))
}

async function copyWidget() {
  await copyText(widgetSnippet.value)
}

function previewWidget() {
  if (widgetKey.value) {
    window.open(`${secureWidgetOrigin()}/widget?key=${encodeURIComponent(widgetKey.value)}`, '_blank')
  }
}

async function copyKey() {
  await copyText(createdKey.value)
}

async function toggleKey(row: any) {
  await http.put(`/agent/${agentId}/api-keys/${row.id}`, {
    status: row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  })
  await loadApiKeys()
}

async function removeKey(row: any) {
  await ElMessageBox.confirm(`${t('agentDlg.deleteKeyPrefix')}${row.name}${t('agentDlg.deleteKeyMid')}`, t('agentDlg.deleteKeyTitle'),
    { type: 'warning', confirmButtonText: t('agentDlg.delete'), cancelButtonText: t('agentDlg.cancel') })
  await http.delete(`/agent/${agentId}/api-keys/${row.id}`)
  ElMessage.success(t('agentDlg.deleted'))
  await loadApiKeys()
}

const integratedAgentId = ref(0)

function modelLabel(m: any) {
  const name = m.displayName || m.modelName
  const c = Number(m.contextWindow || 0)
  return c > 0 ? `${name}（${c >= 1048576 ? (c / 1048576) + 'M' : (c / 1024) + 'K'}${t('agentMsg.contextSuffix')}` : name
}

function openOptimize() {
  if (!form.systemPrompt || !form.systemPrompt.trim()) {
    ElMessage.warning(t('agentDlg.promptRequired')); return
  }
  optimizeResult.value = ''
  optimizeUsage.value = null
  optimizeInstruction.value = ''
  optimizeVisible.value = true
}

async function runOptimize() {
  if (!form.systemPrompt.trim()) { ElMessage.warning(t('agentDlg.promptRequired')); return }
  optimizing.value = true
  try {
    const res = await http.post('/agent/prompt/optimize', {
      prompt: form.systemPrompt,
      name: form.name,
      description: form.description,
      modelName: form.modelName,
      instruction: optimizeInstruction.value,
      style: optimizeStyle.value
    }, { timeout: 120000 }) as any
    optimizeResult.value = res.optimizedPrompt || ''
    optimizeUsage.value = res.usage || null
    optimizeModel.value = res.modelName || ''
    ElMessage.success(t('agentDlg.optimizeDone'))
  } finally {
    optimizing.value = false
  }
}

function applyOptimized(mode: 'replace' | 'append') {
  if (!optimizeResult.value) return
  form.systemPrompt = mode === 'replace'
    ? optimizeResult.value
    : (form.systemPrompt ? form.systemPrompt + '\n\n' + optimizeResult.value : optimizeResult.value)
  optimizeVisible.value = false
  ElMessage.success(mode === 'replace' ? t('agentDlg.replaced') : t('agentDlg.appended'))
}

function openTemplatePicker() {
  templateId.value = 0
  templateVisible.value = true
}

async function applyTemplate() {
  if (!selectedTemplate.value) return
  if (form.systemPrompt.trim()) {
    try {
      await ElMessageBox.confirm(t('agentDlg.overwrite'), t('agentDlg.overwrite'), { type: 'warning' })
    } catch { return }
  }
  form.systemPrompt = selectedTemplate.value.content || ''
  templateVisible.value = false
  ElMessage.success(t('agentDlg.templateInserted'))
}

function openPicker(type: RefType) {
  picker.type = type
  picker.selected = []
  picker.visible = true
}

function goCreate(kind: 'skill' | 'tool' | 'mcp') {
  picker.visible = false
  const path = kind === 'skill' ? '/skills?create=1'
    : kind === 'tool' ? '/tools?create=1' : '/mcp-servers?create=1'
  router.push(path)
}

/** 后端 GET /workflow 恒返回 200（configured=是否有工作流行），此处只负责把响应铺到页面状态。 */
function confirmPicker() {
  const options = pickerOptions.value
  for (const id of picker.selected) {
    const o = options.find((x: any) => x.id === id)
    if (!o) continue
    const row: RefRow = {
      id, name: o.name, description: o.description,
      type: o.toolType || o.skillType, isBuiltin: !!o.isBuiltin, enabled: true
    }
    if (picker.type === 'skill') skillRows.value.push(row)
    else if (picker.type === 'tool') toolRows.value.push(row)
    else subRows.value.push(row)
  }
  picker.visible = false
}

function removeRow(rows: any, row: RefRow) {
  const i = rows.value.indexOf(row)
  if (i >= 0) rows.value.splice(i, 1)
}

function parsePolicyJson(text: string) {
  if (!text || !text.trim()) return {}
  let value: any
  try {
    value = JSON.parse(text)
  } catch {
    throw new Error('write policy JSON invalid')
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('write policy JSON must be an object')
  }
  return value
}

function parseRedactPatterns(text: string): string[] {
  if (!text || !text.trim()) return []
  try {
    const value = JSON.parse(text)
    if (!Array.isArray(value)) return []
    return value.filter(v => typeof v === 'string').slice(0, 20)
  } catch {
    return []
  }
}

function buildPayload() {
  return {
    name: form.name,
    description: form.description,
    category: form.category,
    tags: tagsStr.value.split(/[,，]/).map((t: string) => t.trim()).filter(Boolean),
    systemPrompt: form.systemPrompt,
    openingStatement: form.openingStatement,
    agentCapabilities: {
      ...form.capabilities,
      redactPatterns: parseRedactPatterns(redactPatternsText.value),
      writeToolArgPatterns: parsePolicyJson(writeToolPatternsText.value),
      writeToolAmountLimits: parsePolicyJson(writeToolAmountLimitsText.value)
    },
    memoryConfig: { ...form.memory },
    modelConfig: { modelName: form.modelName, fallbackModelName: form.fallbackModelName || null,
      grayModel: form.grayModel || null, grayTag: form.grayTag || null,
      temperature: form.temperature,
      thinking: form.thinking, reasoningEffort: form.thinking ? form.reasoningEffort : null,
      routing: buildModelRoutingConfig(form) },
    resourceProfileId: form.resourceProfileId,
    variables: form.variables.filter((v: any) => v.key),
    visibility: form.visibility,
    knowledgeBases: kbRows.value.filter((k: any) => k.enabled).map((k: any) => ({
      knowledgeBaseId: k.knowledgeBaseId, enabled: true,
      retrievalConfig: buildKbRetrievalConfig(k)
    })),
    skills: skillRows.value.filter(r => !r.missing).map(r => ({ skillId: r.id, enabled: r.enabled })),
    tools: toolRows.value.filter(r => !r.missing).map(r => ({ toolId: r.id, enabled: r.enabled })),
    subAgents: subRows.value.filter(r => !r.missing).map(r => ({ subAgentId: r.id, enabled: r.enabled }))
  }
}

async function save(): Promise<boolean> {
  if (!form.name.trim() || !form.modelName || !form.systemPrompt.trim()) {
    ElMessage.warning(t('agentMsg.fieldsRequired'))
    return false
  }
  saving.value = true
  try {
    let payload: any
    try {
      payload = buildPayload()
    } catch {
      ElMessage.error(t('agentEdit.writeToolPolicyJsonInvalid'))
      return false
    }
    const res = await http.put(`/agent/${agentId}`, payload, {
      headers: form.updatedAt ? { 'If-Match': form.updatedAt } : {}
    }) as any
    form.updatedAt = res.updatedAt
    if (form.visibility === 'PRIVATE') {
      await http.put(`/agent/${agentId}/grants`, { userIds: grantedIds.value }).catch(() => {})
    }
    ElMessage.success(t('agentDlg.saved'))
    return true
  } finally {
    saving.value = false
  }
}

/** 发布前配置检查摘要（需求 3.2：知情确认，不阻断；门禁仍由后端执行）。 */
async function publishCheckSummary(): Promise<boolean> {
  const items = [
    { label: t('agentMsg.chkModel'), ok: !!form.modelName },
    { label: t('agentMsg.chkPrompt'), ok: !!form.systemPrompt?.trim() },
    { label: t('agentMsg.chkKb'), ok: enabledKbCount.value > 0, count: enabledKbCount.value },
    { label: t('agentMsg.chkTools'), ok: toolRows.value.length > 0, count: toolRows.value.length },
    { label: t('agentMsg.chkSkills'), ok: skillRows.value.length > 0, count: skillRows.value.length },
    { label: t('agentMsg.chkVars'), ok: form.variables.length > 0, count: form.variables.length }
  ]
  const warns = items.filter(i => !i.ok)
  if (warns.length === 0) return true
  const lines = items.map(i => `${i.ok ? '✓' : '⚠'} ${i.label}` + (i.ok && i.count ? `（${i.count}）` : ''))
  try {
    await ElMessageBox.confirm(
      `<div style="line-height:2">${lines.join('<br/>')}</div>`,
      t('agentMsg.chkTitle'),
      { type: 'warning', dangerouslyUseHTMLString: true,
        confirmButtonText: t('agentMsg.chkPublishAnyway'), cancelButtonText: t('agentDlg.cancel') })
    return true
  } catch {
    return false
  }
}

/** 已部署过的 Agent 发布后自动用新版本重新部署，保证容器与线上版本一致 */
async function autoRedeployAfterPublish() {
  try {
    const data = await http.get(`/agent/${agentId}/deployment`) as any
    if (!data?.deployment || !data.dockerAvailable) return
    deployment.value = await http.post(`/agent/${agentId}/deployment`, {}) as any
    deployInfo.dockerAvailable = true
    ElMessage.success(t('deploy.autoRedeployed'))
  } catch {
    // 部署失败已由请求拦截器提示，不阻断发布结果
  }
}

async function publish(force = false) {
  if (!force) {
    const go = await publishCheckSummary()
    if (!go) return
  }
  if (!await save()) return
  // 发布可能先执行预发布门禁评测（每样本约 5-30s），超时放宽至 10 分钟
  const loading = ElLoading.service({
    text: force ? t('agentMsg.forcePublishing') : t('agentMsg.publishing'),
    background: 'rgba(0, 0, 0, 0.4)'
  })
  try {
    const res = await http.post(`/agent/${agentId}/publish`, { force }, { timeout: 600000 }) as any
    form.status = 'PUBLISHED'
    ElMessage.success(`${t('agentMsg.published')}${res.version}`)
    await autoRedeployAfterPublish()
  } catch (err: any) {
    const msg = String(err?.response?.data?.message || err?.message || err)
    if (msg.includes('writePolicyGate:')) {
      loading.close()
      try {
        await ElMessageBox.confirm(
          `${escapeHtml(msg)}<br/><br/>${escapeHtml(t('agentMsg.writePolicyGatePrompt'))}`,
          t('agentMsg.writePolicyGateTitle'),
          { type: 'warning', dangerouslyUseHTMLString: true,
            confirmButtonText: t('agentMsg.forcePublish'), cancelButtonText: t('agentDlg.cancel') })
        await publish(true)
      } catch { /* 取消 */ }
      return
    }
    if (msg.includes(t('agentMsg.gateFailed'))) {
      loading.close()
      let gateHtml = ''
      try {
        const gate = await http.post(`/agent/${agentId}/gate-preview`, {},
          { timeout: 600000 }) as any
        gateHtml = renderGateFailures(gate, {
          failedSteps: t('agentMsg.gateFailedSteps'), score: t('agentMsg.gateScore')
        })
      } catch {
        // 明细获取失败不阻断“强制发布”确认
      }
      try {
        await ElMessageBox.confirm(
          `${escapeHtml(msg)}${gateHtml ? '<br/><br/>' + gateHtml : ''}<br/><br/>${escapeHtml(t('agentMsg.forcePublishPrompt'))}`,
          t('agentMsg.gateTitle'),
          { type: 'warning', dangerouslyUseHTMLString: true,
            confirmButtonText: t('agentMsg.forcePublish'), cancelButtonText: t('agentDlg.cancel') })
        await publish(true)
      } catch { /* 取消 */ }
      return
    }
    throw err
  } finally {
    loading.close()
  }
}

async function toggleListing(value: boolean) {
  if (!value) {
    try {
      await ElMessageBox.confirm(t('agentMsg.delistConfirm'),
        t('agentMsg.delistTitle'), { type: 'warning', confirmButtonText: t('agentMsg.delist'), cancelButtonText: t('agentDlg.cancel') })
    } catch {
      form.listed = true
      return
    }
  }
  try {
    await http.put(`/square/agents/${agentId}/listing`, { listed: value })
    ElMessage.success(value ? t('agentMsg.listedMsg') : t('agentMsg.delistedMsg'))
  } catch {
    form.listed = !value
  }
}

async function openVersions() {
  versions.value = await http.get(`/agent/${agentId}/versions`) as any
  versionsVisible.value = true
}

async function rollback(version: string) {
  await ElMessageBox.confirm(`${t('agentMsg.rollbackConfirm')}${version}${t('agentMsg.rollbackSuffix')}`, t('agentMsg.rollbackTitle'), { type: 'warning' })
  await http.post(`/agent/${agentId}/versions/${version}/rollback`)
  ElMessage.success(t('agentMsg.rolledBackRefresh'))
  versionsVisible.value = false
  setTimeout(() => router.go(0), 600)
}

function addVariable() {
  form.variables.push({ key: '', type: 'STRING', required: false, defaultValue: '', description: '' })
}

async function debugSend() {
  const text = debugInput.value.trim()
  if (!text || debugRunning.value) return
  debugInput.value = ''
  debugMsgs.value.push({ role: 'USER', content: text })
  const reply = { role: 'ASSISTANT', content: '', citations: [] as any[], toolCalls: [] as any[],
    usage: null as any, latencyMs: 0 }
  debugMsgs.value.push(reply)
  debugRunning.value = true
  try {
    const res = await http.post('/agent/debug/chat', { config: buildPayload(), message: text }) as any
    reply.content = res.answer
    reply.citations = res.citations || []
    reply.toolCalls = res.toolCalls || []
    reply.usage = res.usage
    reply.latencyMs = res.latencyMs
  } catch (e: any) {
    reply.content = t('agentMsg.debugFailed') + (e.message || e)
  } finally {
    debugRunning.value = false
    scrollDbg()
  }
}

function scrollDbg() {
  nextTick(() => { if (dbgBox.value) dbgBox.value.scrollTop = dbgBox.value.scrollHeight })
}

function evalEntry() {
  router.push(`/eval/${agentId}`)
}

function fmtTime(v?: string) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '—'
}

function clip(text: string, n: number) {
  text = text || ''
  return text.length > n ? text.slice(0, n) + '…' : text
}
  return { skillRows, toolRows, subRows, skillCatalog, toolCatalog, agentCatalog, picker, credentialVisible, credentials, credentialFormVisible, credentialSaving, credentialForm, scheduleVisible, schedules, scheduleFormVisible, scheduleSaving, scheduleForm, resultsVisible, scheduleResults, resultsTitle, integrationVisible, apiKeys, mcpEndpoint, createdKey, channelsVisible, channelMessagesVisible, channelMessages, messagesChannelName, channels, channelCreateVisible, channelSaving, channelTestingId, createdInbound, channelForm, widgetSnippet, widgetKey, widgetCreating, keyCreateVisible, keySaving, keyForm, optimizeVisible, optimizing, optimizeStyle, optimizeInstruction, optimizeResult, optimizeUsage, optimizeModel, promptTemplates, templateVisible, templateId, selectedTemplate, enabledSkillCount, enabledToolCount, enabledSubCount, pickerTitle, pickerPlaceholder, pickerOptions, debugMsgs, debugInput, debugRunning, dbgBox, buildRefRows, openCredentials, loadCredentials, toolName, openCredentialCreate, openCredentialEdit, saveCredential, removeCredential, openSchedules, loadSchedules, openScheduleCreate, openScheduleEdit, saveSchedule, toggleSchedule, runSchedule, openResults, removeSchedule, openIntegration, openChannels, createChannel, inboundUrl, openChannelMessages, copyInbound, testChannel, toggleChannel, removeChannel, loadApiKeys, openKeyCreate, createKey, createWidgetKey, secureWidgetOrigin, openManualCopy, legacyCopy, selectAllText, copyText, deployTagType, deployStatusText, deployCurl, loadPublishInfo, openPublishInfo, createTestLink, openTestPage, openDeploy, loadDeployProfiles, refreshDeploy, doDeploy, deployAction, openDeployLogs, openDeployHistory, deployActionText, deployActionTag, removeDeploy, rollbackDeployment, mainTokenUsage, keyUsage, loadRuntimeKeys, loadDeployStats, openKeyDialog, saveRuntimeKey, toggleRuntimeKey, removeRuntimeKey, applyRuntimeKeys, rotateDeployToken, copyWidget, previewWidget, copyKey, toggleKey, removeKey, integratedAgentId, modelLabel, openOptimize, runOptimize, applyOptimized, openTemplatePicker, applyTemplate, openPicker, goCreate, confirmPicker, removeRow, parsePolicyJson, parseRedactPatterns, buildPayload, save, publishCheckSummary, autoRedeployAfterPublish, publish, toggleListing, openVersions, rollback, addVariable, debugSend, scrollDbg, evalEntry, fmtTime, clip }
}
