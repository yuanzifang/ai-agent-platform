<template>
  <div class="page">
    <div class="toolbar">
      <span class="title">{{ t('page.templates') }}</span>
      <span class="sub">{{ t('tpl.sub') }}</span>
      <div class="spacer" />
      <el-input v-model="keyword" :placeholder="t('tpl.searchPh')" clearable style="width: 200px"
                @keyup.enter="load" @clear="load" />
      <el-select v-model="category" :placeholder="t('tpl.category')" clearable style="width: 120px" @change="load">
        <el-option v-for="c in categories" :key="c" :value="c" :label="c" />
      </el-select>
      <el-button @click="load">{{ t('tpl.search') }}</el-button>
    </div>

    <el-empty v-if="!loading && list.length === 0" :description="t('tpl.empty')" />
    <div class="grid">
      <el-card v-for="tpl in list" :key="tpl.id" class="tpl-card" shadow="hover">
        <div class="card-head">
          <div class="icon">{{ iconOf(tpl.category) }}</div>
          <div class="title-wrap">
            <div class="name">{{ tpl.name }}</div>
            <el-tag v-if="tpl.category" size="small">{{ tpl.category }}</el-tag>
            <el-tag v-if="tpl.isBuiltin" size="small" type="info">{{ t('tpl.builtin') }}</el-tag>
          </div>
        </div>
        <div class="desc">{{ tpl.description }}</div>
        <div class="tags">
          <el-tag v-for="tool in tpl.tools || []" :key="'t' + tool.toolKey" size="small" type="success">
            {{ t('tpl.tools') }} · {{ tool.name }}
          </el-tag>
          <el-tag v-for="s in tpl.skills || []" :key="'s' + s.skillKey" size="small" type="warning">
            {{ t('tpl.skills') }} · {{ s.name }}
          </el-tag>
          <span v-if="!(tpl.tools || []).length && !(tpl.skills || []).length" class="muted">{{ t('tpl.noBindings') }}</span>
        </div>
        <div class="card-foot">
          <el-button size="small" @click="openPreview(tpl)">{{ t('tpl.preview') }}</el-button>
          <el-button v-if="canCreate" size="small" type="primary" @click="useTemplate(tpl)">{{ t('tpl.use') }}</el-button>
        </div>
      </el-card>
    </div>

    <el-dialog v-model="previewVisible" :title="`${t('tpl.previewTitle')}${current?.name || ''}`" width="680px">
      <template v-if="current">
        <div class="pv-row">
          <span class="pv-label">{{ t('tpl.category') }}</span>
          <el-tag v-if="current.category" size="small">{{ current.category }}</el-tag>
          <span v-else class="muted">-</span>
        </div>
        <div class="pv-row">
          <span class="pv-label">{{ t('tpl.desc') }}</span>
          <span>{{ current.description }}</span>
        </div>
        <div class="pv-row">
          <span class="pv-label">{{ t('tpl.capabilities') }}</span>
          <el-tag size="small" :type="current.agentCapabilities?.streamOutput !== false ? 'success' : 'info'">
            {{ t('tpl.stream') }} {{ current.agentCapabilities?.streamOutput !== false ? t('tpl.streamOn') : t('tpl.streamOff') }}
          </el-tag>
          <el-tag size="small" class="ml6" :type="current.agentCapabilities?.functionCall ? 'success' : 'info'">
            {{ t('tpl.function') }} {{ current.agentCapabilities?.functionCall ? t('tpl.streamOn') : t('tpl.streamOff') }}
          </el-tag>
        </div>
        <div class="pv-row" v-if="(current.variables || []).length">
          <span class="pv-label">{{ t('tpl.variables') }}</span>
          <el-tag v-for="v in current.variables" :key="v.key" size="small" class="ml6">{{ v.key }}</el-tag>
        </div>
        <div class="pv-row" v-if="(current.tools || []).length || (current.skills || []).length">
          <span class="pv-label">{{ t('tpl.bindings') }}</span>
          <el-tag v-for="tool in current.tools || []" :key="'pt' + tool.toolKey" size="small" class="ml6">
            {{ t('tpl.tools') }} · {{ tool.name }}
          </el-tag>
          <el-tag v-for="s in current.skills || []" :key="'ps' + s.skillKey" size="small" class="ml6">
            {{ t('tpl.skills') }} · {{ s.name }}
          </el-tag>
        </div>
        <div class="pv-label mt10">{{ t('tpl.prompt') }}</div>
        <pre class="pv-prompt">{{ current.systemPrompt }}</pre>
        <div class="pv-label mt10" v-if="current.openingStatement">{{ t('tpl.opening') }}</div>
        <div v-if="current.openingStatement" class="pv-open">{{ current.openingStatement }}</div>
      </template>
      <template #footer>
        <el-button @click="previewVisible = false">{{ t('tpl.close') }}</el-button>
        <el-button v-if="canCreate && current" type="primary" @click="useTemplate(current)">{{ t('tpl.use') }}</el-button>
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
  ElMessage.success(t('tpl.created'))
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