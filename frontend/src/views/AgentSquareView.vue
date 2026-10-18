<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">Agent 广场</span>
      <span class="sub">本租户已发布的 Agent，按热度/评分排序</span>
      <div class="spacer" />
      <el-input v-model="keyword" placeholder="搜索名称/描述" clearable style="width: 190px"
                @keyup.enter="load" @clear="load" />
      <el-select v-model="category" placeholder="分类" clearable style="width: 110px" @change="load">
        <el-option v-for="c in categories" :key="c" :value="c" :label="c" />
      </el-select>
      <el-select v-model="tag" placeholder="标签" clearable style="width: 110px" @change="load">
        <el-option v-for="t in tags" :key="t" :value="t" :label="t" />
      </el-select>
      <el-select v-model="sort" style="width: 120px" @change="load">
        <el-option label="按热度" value="hot" />
        <el-option label="按评分" value="rating" />
        <el-option label="最新发布" value="new" />
      </el-select>
      <el-checkbox v-model="favoritesOnly" @change="load">我的收藏</el-checkbox>
      <el-button @click="load">搜索</el-button>
      <el-button v-if="canManage" @click="openReports">举报处理</el-button>
    </div>

    <el-empty v-if="!loading && list.length === 0" description="暂无已上架的 Agent" />
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
        <div class="desc">{{ a.description || '暂无描述' }}</div>
        <div class="metrics">
          <span>调用 {{ a.quality?.calls ?? 0 }}</span>
          <span>成功率 {{ a.quality?.successRate == null ? '—' : a.quality.successRate + '%' }}</span>
          <span>评分 {{ a.quality?.ratingAvg == null ? '—' : a.quality.ratingAvg }}（{{ a.quality?.ratingCount ?? 0 }}）</span>
          <span>收藏 {{ a.favoriteCount ?? 0 }}</span>
        </div>
        <div class="meta">
          <span>{{ a.publisher || '-' }} · {{ fmt(a.publishedAt) }}</span>
        </div>
        <div class="card-foot">
          <el-button size="small" @click="openDetail(a)">详情</el-button>
          <el-button size="small" :disabled="!a.favorite" @click="toggleFavorite(a)">取消收藏</el-button>
          <el-button v-if="canFork" size="small" @click="fork(a)">Fork</el-button>
          <el-button size="small" type="primary" @click="use(a)">对话</el-button>
        </div>
      </el-card>
    </div>

    <el-dialog v-model="reportsVisible" title="举报处理" width="780px">
      <el-table :data="reports" size="small" empty-text="暂无举报">
        <el-table-column prop="agentName" label="Agent" width="140" />
        <el-table-column prop="reporter" label="举报人" width="120" />
        <el-table-column prop="reason" label="理由" min-width="220" show-overflow-tooltip />
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'PENDING' ? 'warning'
                      : (row.status === 'RESOLVED' ? 'success' : 'info')">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button size="small" text type="primary" @click="handleReport(row, 'RESOLVED')">处理</el-button>
              <el-button size="small" text @click="handleReport(row, 'DISMISSED')">驳回</el-button>
            </template>
            <span v-else class="hint">已处理</span>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="detailVisible" :title="current?.name || ''" width="760px">
      <template v-if="current">
        <div class="d-head">
          <el-avatar :size="52">{{ initials(current.name) }}</el-avatar>
          <div class="d-title">
            <div class="d-name">{{ current.name }} <el-tag size="small">{{ current.category || '未分类' }}</el-tag></div>
            <div class="d-sub">
              版本 {{ current.version || '—' }} · {{ current.publisher || '-' }} · {{ fmt(current.publishedAt) }}
              <el-tag v-if="!current.listed" size="small" type="danger" class="ml6">已下架</el-tag>
            </div>
          </div>
          <div class="spacer" />
          <el-button text :class="current.favorite ? 'fav on' : 'fav'" @click="toggleFavorite(current)">
            <el-icon><StarFilled v-if="current.favorite" /><Star v-else /></el-icon>
          </el-button>
        </div>
        <div class="d-desc">{{ current.description }}</div>
        <div class="d-metrics">
          <span>调用 {{ current.quality?.calls ?? 0 }}</span>
          <span>成功率 {{ current.quality?.successRate == null ? '—' : current.quality.successRate + '%' }}</span>
          <span>评分 {{ current.quality?.ratingAvg == null ? '—' : current.quality.ratingAvg }}（{{ current.quality?.ratingCount ?? 0 }}）</span>
          <span>收藏 {{ current.favoriteCount ?? 0 }}</span>
          <span>会话 {{ current.quality?.usageCount ?? 0 }}</span>
        </div>
        <div v-if="current.openingStatement" class="d-row">
          <span class="lbl">欢迎语</span><span>{{ current.openingStatement }}</span>
        </div>
        <div v-if="(current.variables || []).length" class="d-row">
          <span class="lbl">输入参数</span>
          <el-tag v-for="v in current.variables" :key="v.key" size="small" class="ml6">{{ v.key }}</el-tag>
        </div>
        <div class="d-row">
          <span class="lbl">操作</span>
          <el-button size="small" type="primary" :disabled="!current.canUse" @click="use(current)">使用（对话）</el-button>
          <el-button v-if="canFork" size="small" @click="fork(current)">复制为我的 Agent</el-button>
          <el-button size="small" @click="report(current)">举报</el-button>
        </div>

        <el-divider content-position="left">用户评价</el-divider>
        <div v-if="current.canRate" class="rate-form">
          <span class="lbl">我的评价</span>
          <el-rate v-model="myScore" :max="5" />
          <el-input v-model="myComment" type="textarea" :rows="2" maxlength="1000"
                    placeholder="说说使用体验（可选）" style="margin-top: 6px" />
          <div class="mt6">
            <el-button size="small" type="primary" :loading="rating" :disabled="!myScore" @click="submitRating">提交评价</el-button>
            <span class="hint">仅使用过该 Agent 的用户可评价</span>
          </div>
        </div>
        <div v-else class="hint mt6">使用该 Agent 后可进行评价</div>

        <div v-if="(current.ratings || []).length" class="ratings">
          <div v-for="r in current.ratings" :key="r.id" class="rating-item">
            <div class="r-head">
              <span class="r-user">{{ r.userName || ('用户' + r.userId) }}</span>
              <el-rate :model-value="r.score" disabled size="small" />
              <span class="r-time">{{ fmt(r.createdAt) }}</span>
            </div>
            <div class="r-comment">{{ r.comment || '（无评论）' }}</div>
            <div v-if="r.reply" class="r-reply">发布者回复：{{ r.reply }}</div>
            <div v-else-if="current.isOwner" class="r-reply-form">
              <el-input v-model="replyDraft[r.id]" size="small" placeholder="回复该评价" style="width: 380px" />
              <el-button size="small" type="primary" class="ml6" @click="submitReply(r)">回复</el-button>
            </div>
          </div>
        </div>
        <el-empty v-else description="暂无评价" :image-size="50" />
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Star, StarFilled } from '@element-plus/icons-vue'
import http from '../api/http'
import { useUserStore } from '../stores/user'

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
  ElMessage.success(status === 'RESOLVED' ? '已处理' : '已驳回')
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
    ElMessage.success('评价已提交')
    await openDetail(current.value)
    await load()
  } finally {
    rating.value = false
  }
}

async function submitReply(r: any) {
  if (!replyDraft[r.id]?.trim()) { ElMessage.warning('请输入回复内容'); return }
  await http.post(`/square/agents/${current.value.id}/ratings/${r.id}/reply`, { reply: replyDraft[r.id] })
  ElMessage.success('已回复')
  replyDraft[r.id] = ''
  await openDetail(current.value)
}

async function report(a: any) {
  try {
    const { value } = await ElMessageBox.prompt('请填写举报理由', '举报 Agent', {
      inputType: 'textarea', inputPlaceholder: '如：内容违规、信息不实等',
      confirmButtonText: '提交', cancelButtonText: '取消'
    })
    await http.post(`/square/agents/${a.id}/report`, { reason: value })
    ElMessage.success('举报已提交，平台管理员将处理')
  } catch { /* 取消 */ }
}

async function fork(a: any) {
  await ElMessageBox.confirm(`将复制「${a.name}」的配置与绑定，创建归属于你的副本。继续？`, 'Fork Agent',
    { type: 'warning', confirmButtonText: 'Fork', cancelButtonText: '取消' })
  const res = await http.post(`/square/agents/${a.id}/fork`) as any
  ElMessage.success('已创建副本')
  detailVisible.value = false
  router.push(`/agent/edit/${res.id}`)
}

function use(a: any) {
  if (!a.canUse && a.canUse !== undefined) { ElMessage.warning('无使用权限'); return }
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
