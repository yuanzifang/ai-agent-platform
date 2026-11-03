<template>
  <span :title="t('agentEdit.rerankHint')">
    <el-switch v-model="kb.rerank" size="small" />
    {{ t('agentEdit.rerank') }}
  </span>
  <template v-if="kb.rerank">
    {{ t('agentEdit.rerankTopN') }}
    <el-input-number v-model="kb.rerankTopN" :min="1" :max="kb.maxTopK || 20" size="small" />
  </template>
</template>

<script setup lang="ts">
defineProps<{
  kb: any
  t: (key: string, ...args: any[]) => string
}>()
</script>
