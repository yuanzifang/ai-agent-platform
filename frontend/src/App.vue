<template>
  <el-config-provider :locale="elLocale">
    <router-view />
  </el-config-provider>
</template>

<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'
import { useUserStore } from './stores/user'

const userStore = useUserStore()
const { locale, t } = useI18n()
const elLocale = computed(() => locale.value === 'en-US' ? en : zhCn)

watch(() => [locale.value, t('app.name')], () => {
  document.title = t('app.name')
  document.documentElement.lang = locale.value
}, { immediate: true })

onMounted(() => {
  // 刷新角色/权限，避免升级后旧 localStorage 中缺少新增权限导致按钮被隐藏
  if (userStore.accessToken) userStore.refreshProfile()
})
</script>
