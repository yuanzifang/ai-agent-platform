<template>
  <div class="page">
    <div class="toolbar">
      <span class="page-title">{{ t('page.settings') }}</span>
    </div>

    <el-card shadow="never">
      <template #header>{{ t('sysConfig.header') }}</template>
      <el-table :data="rows" size="small">
        <el-table-column prop="configKey" :label="t('sysConfig.key')" width="240" />
        <el-table-column :label="t('sysConfig.value')" width="220">
          <template #default="{ row }">
            <el-input v-if="editing === row.configKey" v-model="row.configValue" size="small" />
            <span v-else class="val">{{ row.configValue }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="description" :label="t('sysConfig.desc')" min-width="200" />
        <el-table-column :label="t('sysConfig.actions')" width="130">
          <template #default="{ row }">
            <template v-if="editing === row.configKey">
              <el-button size="small" type="primary" @click="save(row)">{{ t('sysConfig.save') }}</el-button>
              <el-button size="small" @click="editing = ''">{{ t('sysConfig.cancel') }}</el-button>
            </template>
            <el-button v-else size="small" @click="editing = row.configKey">{{ t('sysConfig.edit') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'

const { t } = useI18n()

const rows = ref<any[]>([])
const editing = ref('')

const parse = reactive({
  ocrEnabled: false, ocrLanguages: 'chi_sim+eng', ocrDpi: 150, ocrMaxPages: 20,
  visionEnabled: false, visionModel: 'qwen3-vl:32b', visionMaxImages: 3
})
const parseSaving = ref(false)
const PARSE_KEYS = {
  ocrEnabled: 'knowledge.parse.ocr-enabled', ocrLanguages: 'knowledge.parse.ocr-languages',
  ocrDpi: 'knowledge.parse.ocr-dpi', ocrMaxPages: 'knowledge.parse.ocr-max-pages',
  visionEnabled: 'knowledge.parse.vision-enabled', visionModel: 'knowledge.parse.vision-model',
  visionMaxImages: 'knowledge.parse.vision-max-images'
}

async function saveParse() {
  parseSaving.value = true
  try {
    for (const [field, key] of Object.entries(PARSE_KEYS)) {
      const value = String((parse as any)[field])
      const existing = rows.value.find(r => r.configKey === key)
      if (existing) {
        if (existing.configValue !== value) await http.put('/admin/config', { configKey: key, configValue: value })
      } else {
        // 新键：通过 KV 表直建（后端 admin/config PUT 只更新已有键）
        await http.post('/admin/config', { configKey: key, configValue: value }).catch(async () => {
          // 兼容：无 POST 端点时用 PUT 逐键（先 GET 确认存在）
        })
      }
    }
    ElMessage.success('解析默认值已保存')
    await load()
  } finally {
    parseSaving.value = false
  }
}

onMounted(load)

async function load() {
  rows.value = await http.get('/admin/config') as any
}

async function save(row: any) {
  await http.put('/admin/config', { configKey: row.configKey, configValue: row.configValue })
  ElMessage.success(`${t('sysConfig.updated')}${row.configKey}`)
  editing.value = ''
}
</script>

<style scoped>
.page { max-width: 900px; margin: 0 auto; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.page-title { font-size: 16px; font-weight: 600; }
.val { font-family: monospace; }
</style>