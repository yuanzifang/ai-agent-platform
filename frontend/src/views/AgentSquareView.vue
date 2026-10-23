<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">{{ t('page.square') }}</span>
      <span class="sub">{{ t('square.sub') }}</span>
      <div class="spacer" />
      <el-input v-model="keyword" :placeholder="t('square.searchPh')" clearable style="width: 190px"
                @keyup.enter="load" @clear="load" />
      <el-select v-model="category" :placeholder="t('square.category')" clearable style="width: 110px" @change="load">
        <el-option v-for="c in categories" :key="c" :value="c" :label="c" />
      </el-select>
      <el-select v-model="tag" :placeholder="t('square.tag')" clearable style="width: 110px" @change="load">
        <el-option v-for="t in tags" :key="t" :value="t" :label="t" />
      </el-select>
      <el-select v-model="sort" style="width: 120px" @change="load">
        <el-option :label="t('square.sortHot')" value="hot" />
        <el-option :label="t('square.sortRating')" value="rating" />
        <el-option :label="t('square.sortNew')" value="new" />
      </el-select>
      <el-checkbox v-model="favoritesOnly" @change="load">{{ t('square.favoritesOnly') }}</el-checkbox>
      <el-button @click="load">{{ t('square.search') }}</el-button>
      <el-button v-if="canManage" @click="openReports">{{ t('square.reports') }}</el-button>
    </div>

    <el-empty v-if="!loading && list.length === 0" :description="t('square.empty')" />
    <div class="grid">
      <el-card v-for="a in list" :key="a.id" class="agent-card" shadow="hover">
        <div class="card-head">
          <el-avatar :size="44" class="avatar">{{ initials(a.name) }}</el-avatar>
          <div class="title-wrap">
            <div class="name" @click="openDetail(a)">{{ a.name }}</div>
            <div class="tags">
              <el-tag v-if="a.category" size="small">{{ a.category }}</el-tag>
              <el-tag v-for="t in a.tags || []" :key="t" size="small" type="info">{{ t }}</el-tag>
            </div>
          </div>
          <el-button text class="fav" :class="a.favorite ? 'on' : ''" @click="toggleFavorite(a)">
            <el-icon><StarFilled v-if="a.favorite" /><Star v-else /></el-icon>
          </el-button>
        </div>
        <div class="desc">{{ a.description || t('square.noDesc') }}</div>
        <div class="metrics">
          <span>{{ t('square.calls') }} {{ a.quality?.calls ?? 0 }}</span>
          <span>{{ t('square.successRate') }} {{ a.quality?.successRate == null ? '—' : a.quality.successRate + '%' }}</span>
          <span>{{ t('square.rating') }} {{ a.quality?.ratingAvg == null ? '—' : a.quality.ratingAvg }}（{{ a.quality?.ratingCount ?? 0 }}）</span>
          <span>{{ t('square.favorites') }} {{ a.favoriteCount ?? 0 }}</span>
        </div>
        <div class="meta">
          <span>{{ a.publisher || '-' }} · {{ fmt(a.publishedAt) }}</span>
        </div>
        <div class="card-foot">
          <el-button size="small" @click="openDetail(a)">{{ t('square.detail') }}</el-button>
          <el-button size="small" :disabled="!a.favorite" @click="toggleFavorite(a)">{{ t('square.unfavorite') }}</el-button>
          <el-button v-if="canFork" size="small" @click="fork(a)">Fork</el-button>
          <el-button size="small" type="primary" @click="use(a)">{{ t('square.chat') }}</el-button>
        </div>
      </el-card>
    </div>

    <el-dialog v-model="reportsVisible" :title="t('square.reportTitle')" width="780px">
      <el-table :data="reports" size="small" :empty-text="t('square.reportEmpty')">
        <el-table-column prop="agentName" label="Agent" width="140" />
        <el-table-column prop="reporter" :label="t('square.reporter')" width="120" />
        <el-table-column prop="reason" :label="t('square.reason')" min-width="220" show-overflow-tooltip />
        <el-table-column :label="t('square.time')" width="150">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('square.status')" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'PENDING' ? 'warning'
                      : (row.status === 'RESOLVED' ? 'success' : 'info')">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('square.actions')" width="150">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button size="small" text type="primary" @click="handleReport(row, 'RESOLVED')">{{ t('square.handle') }}</el-button>
              <el-button size="small" text @click="handleReport(row, 'DISMISSED')">{{ t('square.dismiss') }}</el-button>
            </template>
            <span v-else class="hint">{{ t('square.handled') }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="detailVisible" :title="current?.name || ''" width="760px">
      <template v-if="current">
        <div class="d-head">
          <el-avatar :size="52">{{ initials(current.name) }}</el-avatar>
          <div class="d-title">
            <div class="d-name">{{ current.name }} <el-tag size="small">{{ current.category || t('square.unclassified') }}</el-tag></div>
            <div class="d-sub">
              {{ t('square.version') }} {{ current.version || '—' }} · {{ current.publisher || '-' }} · {{ fmt(current.publishedAt) }}
              <el-tag v-if="!current.listed" size="small" type="danger" class="ml6">{{ t('square.delisted') }}</el-tag>
            </div>
          </div>
          <div class="spacer" />
          <el-button text :class="current.favorite ? 'fav on' : 'fav'" @click="toggleFavorite(current)">
            <el-icon><StarFilled v-if="current.favorite" /><Star v-else /></el-icon>
          </el-button>
        </div>
        <div class="d-desc">{{ current.description }}</div>
        <div class="d-metrics">
          <span>{{ t('square.calls') }} {{ current.quality?.calls ?? 0 }}</span>
          <span>{{ t('square.successRate') }} {{ current.quality?.successRate == null ? '—' : current.quality.successRate + '%' }}</span>
          <span>{{ t('square.rating') }} {{ current.quality?.ratingAvg == null ? '—' : current.quality.ratingAvg }}（{{ current.quality?.ratingCount ?? 0 }}）</span>
          <span>{{ t('square.favorites') }} {{ current.favoriteCount ?? 0 }}</span>
          <span>{{ t('square.conversations') }} {{ current.quality?.usageCount ?? 0 }}</span>
        </div>
        <div v-if="current.openingStatement" class="d-row">
          <span class="lbl">{{ t('square.opening') }}</span><span>{{ current.openingStatement }}</span>
        </div>
        <div v-if="(current.variables || []).length" class="d-row">
          <span class="lbl">{{ t('square.variables') }}</span>
          <el-tag v-for="v in current.variables" :key="v.key" size="small" class="ml6">{{ v.key }}</el-tag>
        </div>
        <div class="d-row">
          <span class="lbl">{{ t('square.actions') }}</span>
          <el-button size="small" type="primary" :disabled="!current.canUse" @click="use(current)">{{ t('square.useChat') }}</el-button>
          <el-button v-if="canFork" size="small" @click="fork(current)">{{ t('square.copyAgent') }}</el-button>
          <el-button size="small" @click="report(current)">{{ t('square.report') }}</el-button>
        </div>

        <el-divider content-position="left">{{ t('square.reviews') }}</el-divider>
        <div v-if="current.canRate" class="rate-form">
          <span class="lbl">{{ t('square.myRating') }}</span>
          <el-rate v-model="myScore" :max="5" />
          <el-input v-model="myComment" type="textarea" :rows="2" maxlength="1000"
                    :placeholder="t('square.ratingPh')" style="margin-top: 6px" />
          <div class="mt6">
            <el-button size="small" type="primary" :loading="rating" :disabled="!myScore" @click="submitRating">{{ t('square.submitRating') }}</el-button>
            <span class="hint">{{ t('square.onlyUsed') }}</span>
          </div>
        </div>
        <div v-else class="hint mt6">{{ t('square.rateAfterUse') }}</div>

        <div v-if="(current.ratings || []).length" class="ratings">
          <div v-for="r in current.ratings" :key="r.id" class="rating-item">
            <div class="r-head">
              <span class="r-user">{{ r.userName || (t('square.anonUser') + r.userId) }}</span>
              <el-rate :model-value="r.score" disabled size="small" />
              <span class="r-time">{{ fmt(r.createdAt) }}</span>
            </div>
            <div class="r-comment">{{ r.comment || t('square.noComment') }}</div>
            <div v-if="r.reply" class="r-reply">{{ t('square.publisherReply') }}{{ r.reply }}</div>
            <div v-else-if="current.isOwner" class="r-reply-form">
              <el-input v-model="replyDraft[r.id]" size="small" :placeholder="t('square.replyPh')" style="width: 380px" />
              <el-button size="small" type="primary" class="ml6" @click="submitReply(r)">{{ t('square.reply') }}</el-button>
            </div>
          </div>
        </div>
        <el-empty v-else :description="t('square.noReviews')" :image-size="50" />
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Star, StarFilled } from '@element-plus/icons-vue'
import http from '../api/http'
import { useUserStore } from '../stores/user'

const { t } = useI18n()

const router = useRouter()
const userStore = useUserStore()
const canManage = computed(() => {
  const perms = userStore.userInfo?.permissions || []
  return userStore.userInfo?.isPlatformAdmin || perms.includes('tenant:manage')
})
const reportsVisible = ref(false)
const reports = ref<any[]>([])

const canFork = computed(() => {
  const perms = userStore.userInfo?.permissions || []
  return userStore.userInfo?.isPlatformAdmin || perms.includes('agent:create')
})

const list = ref<any[]>([])
const loading = ref(false)
const keyword = ref('')
const category = ref('')
const tag = ref('')
const sort = ref('hot')
const favoritesOnly = ref(false)
const categories = computed(() => [...new Set(list.value.map(a => a.category).filter(Boolean))])
const tags = computed(() => [...new Set(list.value.flatMap(a => a.tags || []))])

const detailVisible = ref(false)
const current = ref<any>(null)
const myScore = ref(0)
const myComment = ref('')
const rating = ref(false)
const replyDraft = reactive<Record<number, string>>({})

onMounted(load)

async function load() {
  loading.value = true
  try {
    const res = await http.get('/square/agents', {
      params: {
        keyword: keyword.value || undefined,
        category: category.value || undefined,
        tag: tag.value || undefined,
        sort: sort.value,
        favoritesOnly: favoritesOnly.value,
        pageSize: 100
      }
    }) as any
    list.value = res?.list || []
  } finally {
    loading.value = false
  }
}

async function openDetail(a: any) {
  const d = await http.get(`/square/agents/${a.id}`) as any
  current.value = d
  const mine = (d.ratings || []).find((r: any) => r.mine)
  myScore.value = mine?.score || 0
  myComment.value = mine?.comment || ''
  detailVisible.value = true
}

async function openReports() {
  reports.value = await http.get('/square/reports') as any
  reportsVisible.value = true
}

async function handleReport(row: any, status: string) {
  await http.put(`/square/reports/${row.id}`, { status })
  ElMessage.success(status === 'RESOLVED' ? t('square.resolved') : t('square.dismissed'))
  reports.value = await http.get('/square/reports') as any
}

async function toggleFavorite(a: any) {
  const res = await http.post(`/square/agents/${a.id}/favorite`) as any
  a.favorite = res.favorited
  a.favoriteCount = res.favoriteCount
  if (current.value?.id === a.id) {
    current.value.favorite = res.favorited
    current.value.favoriteCount = res.favoriteCount
  }
  if (favoritesOnly.value) await load()
}

async function submitRating() {
  if (!current.value) return
  rating.value = true
  try {
    await http.post(`/square/agents/${current.value.id}/rating`, {
      score: myScore.value, comment: myComment.value
    })
    ElMessage.success(t('square.ratingSubmitted'))
    await openDetail(current.value)
    await load()
  } finally {
    rating.value = false
  }
}

async function submitReply(r: any) {
  if (!replyDraft[r.id]?.trim()) { ElMessage.warning(t('square.replyRequired')); return }
  await http.post(`/square/agents/${current.value.id}/ratings/${r.id}/reply`, { reply: replyDraft[r.id] })
  ElMessage.success(t('square.replied'))
  replyDraft[r.id] = ''
  await openDetail(current.value)
}

async function report(a: any) {
  try {
    const { value } = await ElMessageBox.prompt(t('square.reason'), t('square.reportReasonTitle'), {
      inputType: 'textarea', inputPlaceholder: t('square.reportReasonPh'),
      confirmButtonText: t('square.submit'), cancelButtonText: t('square.cancel')
    })
    await http.post(`/square/agents/${a.id}/report`, { reason: value })
    ElMessage.success(t('square.reportSuccess'))
  } catch { /* 取消 */ }
}

async function fork(a: any) {
  await ElMessageBox.confirm(`${t('square.copyConfirmPrefix')}${a.name}${t('square.copyConfirmSuffix')}`, t('square.copyTitle'),
    { type: 'warning', confirmButtonText: 'Fork', cancelButtonText: t('square.cancel') })
  const res = await http.post(`/square/agents/${a.id}/fork`) as any
  ElMessage.success(t('square.copied'))
  detailVisible.value = false
  router.push(`/agent/edit/${res.id}`)
}

function use(a: any) {
  if (!a.canUse && a.canUse !== undefined) { ElMessage.warning(t('square.noPermission')); return }
  detailVisible.value = false
  router.push(`/chat/${a.id}`)
}

function initials(name: string) {
  return (name || 'A').slice(0, 1)
}

function fmt(v?: string) {
  return v ? String(v).replace('T', ' ').slice(0, 16) : '—'
}
</script>

<style scoped>
.page { background: #fff; border: 1px solid #e4e7ed; border-radius: 6px; padding: 14px; }
.toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; flex-wrap: wrap; }
.title { font-weight: 600; font-size: 15px; }
.sub { color: #909399; font-size: 12px; margin-left: 8px; }
.spacer { flex: 1; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 14px; }
.agent-card { display: flex; flex-direction: column; }
.card-head { display: flex; align-items: flex-start; gap: 10px; }
.avatar { background: #409eff; color: #fff; font-size: 18px; }
.title-wrap { flex: 1; min-width: 0; }
.name { font-weight: 600; font-size: 15px; cursor: pointer; }
.tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 4px; }
.fav { color: #c0c4cc; font-size: 18px; padding: 0; }
.fav.on { color: #e6a23c; }
.desc { color: #606266; font-size: 13px; line-height: 1.6; margin: 10px 0; min-height: 40px; }
.metrics { display: flex; flex-wrap: wrap; gap: 12px; color: #909399; font-size: 12px; }
.meta { color: #c0c4cc; font-size: 12px; margin-top: 6px; }
.card-foot { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; margin-top: 12px; }
.card-foot .el-button { margin-left: 0; }
.d-head { display: flex; align-items: center; gap: 12px; }
.d-title { flex: 1; }
.d-name { font-size: 17px; font-weight: 600; display: flex; align-items: center; gap: 8px; }
.d-sub { color: #909399; font-size: 12px; margin-top: 4px; }
.d-desc { color: #606266; font-size: 13px; line-height: 1.7; margin: 12px 0; }
.d-metrics { display: flex; flex-wrap: wrap; gap: 14px; color: #606266; font-size: 13px; margin-bottom: 12px; }
.d-row { display: flex; align-items: center; gap: 6px; font-size: 13px; margin-bottom: 8px; }
.lbl { color: #909399; font-size: 12px; min-width: 60px; }
.ml6 { margin-left: 6px; }
.mt6 { margin-top: 6px; }
.rate-form { margin-bottom: 10px; }
.ratings { margin-top: 8px; }
.rating-item { border-bottom: 1px dashed #ebeef5; padding: 8px 0; }
.r-head { display: flex; align-items: center; gap: 10px; }
.r-user { font-size: 13px; font-weight: 600; }
.r-time { color: #c0c4cc; font-size: 12px; }
.r-comment { color: #606266; font-size: 13px; margin-top: 4px; }
.r-reply { color: #409eff; font-size: 12px; margin-top: 4px; }
.r-reply-form { margin-top: 6px; }
.hint { color: #909399; font-size: 12px; margin-left: 8px; }
</style>