<template>
  <div class="share-page">
    <div class="share-card">
      <div class="head">
        <div class="title">{{ detail.title }}</div>
        <div class="sub">{{ t('sharePage.agent') }}{{ detail.agentName }}{{ t('sharePage.readonly') }}{{ fmtTime(detail.expiresAt) }}</div>
      </div>
      <div class="msgs">
        <div v-for="(m, i) in detail.messages" :key="i" class="msg" :class="m.role.toLowerCase()">
          <div class="bubble">
            <div class="content">{{ m.content }}</div>
            <div v-if="m.isAiGenerated" class="ai-tag">{{ t('sharePage.aiGenerated') }}</div>
          </div>
        </div>
        <el-empty v-if="detail.messages.length === 0" :description="t('sharePage.noMessages')" />
      </div>
      <div class="foot">{{ t('sharePage.footer') }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { onMounted, reactive } from 'vue'
import { useRoute } from 'vue-router'
import axios from 'axios'

const { t } = useI18n()

const route = useRoute()
const detail = reactive<any>({ title: '', agentName: '', expiresAt: '', messages: [] })

onMounted(async () => {
  // 免登录接口，不走带鉴权拦截的 http 实例
  const resp = await axios.get(`/api/v1/share/${route.params.token}`)
  Object.assign(detail, resp.data.data)
})

function fmtTime(t?: string) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : '-'
}
</script>

<style scoped>
.share-page {
  min-height: 100vh;
  background: #f5f7fa;
  padding: 32px 16px;
  display: flex;
  justify-content: center;
}
.share-card {
  width: 720px;
  max-width: 100%;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 64px);
}
.head {
  padding: 14px 18px;
  border-bottom: 1px solid #e4e7ed;
}
.title { font-weight: 700; font-size: 16px; }
.sub { font-size: 12px; color: #909399; margin-top: 4px; }
.msgs { flex: 1; overflow-y: auto; padding: 16px; display: flex; flex-direction: column; gap: 12px; }
.msg { display: flex; }
.msg.user { justify-content: flex-end; }
.bubble {
  max-width: 76%;
  padding: 10px 14px;
  border-radius: 10px;
  background: #f4f4f5;
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.msg.user .bubble { background: #409eff; color: #fff; }
.ai-tag { margin-top: 6px; font-size: 11px; color: #c0c4cc; }
.foot { padding: 10px 18px; border-top: 1px solid #e4e7ed; font-size: 12px; color: #c0c4cc; text-align: center; }
</style>