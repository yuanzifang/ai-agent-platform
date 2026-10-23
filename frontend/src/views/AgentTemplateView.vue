<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">{{ t('page.templates') }}</span>
      <span class="sub">预置 Agent 模板，一键创建后自动填充提示词、能力与工具/Skill 绑定</span>
      <div class="spacer" />
      <el-input v-model="keyword" placeholder="搜索模板" clearable style="width: 200px"
                @keyup.enter="load" @clear="load" />
      <el-select v-model="category" placeholder="分类" clearable style="width: 120px" @change="load">
        <el-option v-for="c in categories" :key="c" :value="c" :label="c" />
      </el-select>
      <el-button @click="load">搜索</el-button>
    </div>

    <el-empty v-if="!loading && list.length === 0" description="暂无模板" />
    <div class="grid">
      <el-card v-for="t in list" :key="t.id" class="tpl-card" shadow="hover">
        <div class="card-head">
          <div class="icon">{{ iconOf(t.category) }}</div>
          <div class="title-wrap">
            <div class="name">{{ t.name }}</div>
            <el-tag v-if="t.category" size="small">{{ t.category }}</el-tag>
            <el-tag v-if="t.isBuiltin" size="small" type="info">内置</el-tag>
          </div>
        </div>
        <div class="desc">{{ t.description }}</div>
        <div class="tags">
          <el-tag v-for="tool in t.tools || []" :key="'t' + tool.toolKey" size="small" type="success">
            工具 · {{ tool.name }}
          </el-tag>
          <el-tag v-for="s in t.skills || []" :key="'s' + s.skillKey" size="small" type="warning">
            技能 · {{ s.name }}
          </el-tag>
          <span v-if="!(t.tools || []).length && !(t.skills || []).length" class="muted">无绑定工具</span>
        </div>
        <div class="card-foot">
          <el-button size="small" @click="openPreview(t)">预览</el-button>
          <el-button v-if="canCreate" size="small" type="primary" @click="useTemplate(t)">使用模板创建</el-button>
        </div>
      </el-card>
    </div>

    <el-dialog v-model="previewVisible" :title="`模板预览：${current?.name || ''}`" width="680px">
      <template v-if="current">
        <div class="pv-row">
          <span class="pv-label">分类</span>
          <el-tag v-if="current.category" size="small">{{ current.category }}</el-tag>
          <span v-else class="muted">-</span>
        </div>
        <div class="pv-row">
          <span class="pv-label">描述</span>
          <span>{{ current.description }}</span>
        </div>
        <div class="pv-row">
          <span class="pv-label">能力</span>
          <el-tag size="small" :type="current.agentCapabilities?.streamOutput !== false ? 'success' : 'info'">
            流式输出 {{ current.agentCapabilities?.streamOutput !== false ? '开' : '关' }}
          </el-tag>
          <el-tag size="small" class="ml6" :type="current.agentCapabilities?.functionCall ? 'success' : 'info'">
            函数调用 {{ current.agentCapabilities?.functionCall ? '开' : '关' }}
          </el-tag>
        </div>
        <div class="pv-row" v-if="(current.variables || []).length">
          <span class="pv-label">变量</span>
          <el-tag v-for="v in current.variables" :key="v.key" size="small" class="ml6">{{ v.key }}</el-tag>
        </div>
        <div class="pv-row" v-if="(current.tools || []).length || (current.skills || []).length">
          <span class="pv-label">绑定</span>
          <el-tag v-for="tool in current.tools || []" :key="'pt' + tool.toolKey" size="small" class="ml6">
            工具 · {{ tool.name }}
          </el-tag>
          <el-tag v-for="s in current.skills || []" :key="'ps' + s.skillKey" size="small" class="ml6">
            技能 · {{ s.name }}
          </el-tag>
        </div>
        <div class="pv-label mt10">系统提示词</div>
        <pre class="pv-prompt">{{ current.systemPrompt }}</pre>
        <div class="pv-label mt10" v-if="current.openingStatement">欢迎语</div>
        <div v-if="current.openingStatement" class="pv-open">{{ current.openingStatement }}</div>
      </template>
      <template #footer>
        <el-button @click="previewVisible = false">关闭</el-button>
        <el-button v-if="canCreate && current" type="primary" @click="useTemplate(current)">使用模板创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api/http'
import { useUserStore } from '../stores/user'

const { t } = useI18n()

const router = useRouter()
const userStore = useUserStore()
const canCreate = computed(() => {
  const perms = userStore.userInfo?.permissions || []
  return userStore.userInfo?.isPlatformAdmin || perms.includes('agent:create')
})

const list = ref<any[]>([])
const loading = ref(false)
const keyword = ref('')
const category = ref('')
const categories = computed(() => [...new Set(list.value.map(t => t.category).filter(Boolean))])
const previewVisible = ref(false)
const current = ref<any>(null)

onMounted(load)

async function load() {
  loading.value = true
  try {
    const res = await http.get('/agent-templates', {
      params: { keyword: keyword.value || undefined, category: category.value || undefined }
    }) as any
    list.value = res?.list || []
  } finally {
    loading.value = false
  }
}

function iconOf(category?: string) {
  if (category === '客服') return '🎧'
  if (category === '知识问答') return '📚'
  if (category === '分析') return '📊'
  return '🤖'
}

function openPreview(t: any) {
  current.value = t
  previewVisible.value = true
}

async function useTemplate(t: any) {
  const res = await http.post(`/agent-templates/${t.id}/use`) as any
  ElMessage.success('已从模板创建 Agent，请按需调整知识库等配置')
  previewVisible.value = false
  router.push(`/agent/edit/${res.id}`)
}
</script>

<style scoped>
.page { background: #fff; border: 1px solid #e4e7ed; border-radius: 6px; padding: 14px; }
.toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; }
.title { font-weight: 600; font-size: 15px; }
.sub { color: #909399; font-size: 12px; margin-left: 8px; }
.spacer { flex: 1; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 14px; }
.tpl-card { display: flex; flex-direction: column; }
.card-head { display: flex; align-items: center; gap: 12px; }
.icon {
  width: 44px; height: 44px; border-radius: 50%; background: #ecf5ff;
  display: flex; align-items: center; justify-content: center; font-size: 22px;
}
.title-wrap { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.name { font-weight: 600; font-size: 15px; }
.desc { color: #606266; font-size: 13px; line-height: 1.6; margin: 10px 0; min-height: 42px; }
.tags { display: flex; flex-wrap: wrap; gap: 6px; min-height: 24px; }
.card-foot { display: flex; justify-content: flex-end; gap: 6px; margin-top: 12px; }
.muted { color: #c0c4cc; font-size: 12px; }
.pv-row { display: flex; align-items: center; gap: 6px; margin-bottom: 8px; font-size: 13px; }
.pv-label { color: #909399; min-width: 64px; font-size: 12px; }
.ml6 { margin-left: 6px; }
.mt10 { margin-top: 10px; }
.pv-prompt {
  white-space: pre-wrap; background: #f5f7fa; border-radius: 6px; padding: 10px;
  font-size: 13px; line-height: 1.6; max-height: 240px; overflow-y: auto;
}
.pv-open { color: #606266; font-size: 13px; }
</style>