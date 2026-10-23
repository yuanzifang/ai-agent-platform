<template>
  <div class="nf">
    <h1>404</h1>
    <p>{{ t('notFound.desc') }}</p>
    <el-button type="primary" @click="$router.push('/agents')">{{ t('notFound.home') }}</el-button>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
</script>

<style scoped>
.nf {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
}
h1 { font-size: 64px; color: #c0c4cc; margin: 0; }
p { color: #909399; }
</style>
