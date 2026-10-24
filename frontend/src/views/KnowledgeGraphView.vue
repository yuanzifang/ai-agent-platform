<template>
  <div class="page">
    <div class="toolbar">
      <el-button text @click="$router.push(`/knowledge/${kbId}`)">
        <el-icon><ArrowLeft /></el-icon> {{ t('graphPage.back') }}
      </el-button>
      <span class="title">{{ t('graphPage.title') }}</span>
      <el-tag size="small">{{ t('graphPage.entityCount') }} {{ graph.entityCount || 0 }}</el-tag>
      <el-tag size="small" type="success">{{ t('graphPage.relationCount') }} {{ graph.relationCount || 0 }}</el-tag>
      <div class="spacer" />
      <el-button type="primary" plain @click="extractVisible = true">{{ t('graphPage.aiExtract') }}</el-button>
      <el-button @click="load">{{ t('common.refresh') }}</el-button>
    </div>

    <el-dialog v-model="extractVisible" :title="t('graphPage.extractTitle')" width="640px">
      <el-form label-width="110px">
        <el-form-item :label="t('graphPage.extractText')">
          <el-input v-model="extractText" type="textarea" :rows="5" :placeholder="t('graphPage.extractTextPh')" />
        </el-form-item>
        <el-form-item :label="t('graphPage.commit')">
          <el-checkbox v-model="extractCommit" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="extractVisible = false">{{ t('graphPage.close') }}</el-button>
        <el-button type="primary" :loading="extracting" @click="runExtract">{{ t('graphPage.extract') }}</el-button>
      </template>
    </el-dialog>

    <el-row :gutter="12">
      <el-col :span="8">
        <el-card shadow="never">
          <template #header>{{ t('graphPage.addEntity') }}</template>
          <el-form label-width="80px">
            <el-form-item :label="t('graphPage.entityName')">
              <el-input v-model="entityForm.name" :placeholder="t('graphPage.entityNamePh')" />
            </el-form-item>
            <el-form-item :label="t('graphPage.entityType')">
              <el-input v-model="entityForm.entityType" :placeholder="t('graphPage.entityTypePh')" />
            </el-form-item>
            <el-form-item :label="t('graphPage.description')">
              <el-input v-model="entityForm.description" type="textarea" :rows="2"
                        :placeholder="t('graphPage.descriptionPh')" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="savingEntity" @click="addEntity">{{ t('graphPage.add') }}</el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card shadow="never" class="mt12">
          <template #header>{{ t('graphPage.addRelation') }}</template>
          <el-form label-width="80px">
            <el-form-item :label="t('graphPage.relationFrom')">
              <el-select v-model="relationForm.fromId" filterable style="width: 100%">
                <el-option v-for="e in entities" :key="e.id" :value="e.id" :label="e.name" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('graphPage.relationTo')">
              <el-select v-model="relationForm.toId" filterable style="width: 100%">
                <el-option v-for="e in entities" :key="e.id" :value="e.id" :label="e.name" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('graphPage.relationName')">
              <el-input v-model="relationForm.relation" :placeholder="t('graphPage.relationNamePh')" />
            </el-form-item>
            <el-form-item :label="t('graphPage.weight')">
              <el-input-number v-model="relationForm.weight" :min="0.1" :max="10" :step="0.1" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="savingRelation" @click="addRelation">{{ t('graphPage.add') }}</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="16">
        <el-card shadow="never">
          <template #header>{{ t('graphPage.graph') }}</template>
          <el-empty v-if="!entities.length" :description="t('graphPage.empty')" :image-size="60" />
          <svg v-else :viewBox="`0 0 ${width} ${height}`" class="graph-canvas">
            <line v-for="r in relations" :key="'r' + r.id"
                  :x1="pos(r.fromId).x" :y1="pos(r.fromId).y"
                  :x2="pos(r.toId).x" :y2="pos(r.toId).y"
                  stroke="#a8abb2" stroke-width="1.5" />
            <text v-for="r in relations" :key="'rt' + r.id"
                  :x="(pos(r.fromId).x + pos(r.toId).x) / 2"
                  :y="(pos(r.fromId).y + pos(r.toId).y) / 2 - 4"
                  text-anchor="middle" class="rel-label">{{ r.relation }}</text>
            <g v-for="e in entities" :key="'e' + e.id" :transform="`translate(${pos(e.id).x},${pos(e.id).y})`">
              <circle r="28" :fill="e.entityType ? '#ecf5ff' : '#f4f4f5'" stroke="#409eff" />
              <text text-anchor="middle" y="5" class="node-label">{{ short(e.name) }}</text>
              <text text-anchor="middle" y="46" class="type-label">{{ e.entityType || '' }}</text>
            </g>
          </svg>
        </el-card>

        <el-row :gutter="12" class="mt12">
          <el-col :span="12">
            <el-card shadow="never">
              <template #header>{{ t('graphPage.entities') }}</template>
              <el-table :data="entities" size="small" max-height="260">
                <el-table-column prop="name" :label="t('graphPage.entityName')" min-width="110" />
                <el-table-column prop="entityType" :label="t('graphPage.entityType')" width="100" />
                <el-table-column width="70">
                  <template #default="{ row }">
                    <el-button text type="danger" size="small" @click="removeEntity(row)">{{ t('graphPage.remove') }}</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-card>
          </el-col>
          <el-col :span="12">
            <el-card shadow="never">
              <template #header>{{ t('graphPage.relations') }}</template>
              <el-table :data="relations" size="small" max-height="260">
                <el-table-column :label="t('graphPage.relations')" min-width="140">
                  <template #default="{ row }">{{ nameOf(row.fromId) }} → {{ nameOf(row.toId) }}</template>
                </el-table-column>
                <el-table-column prop="relation" :label="t('graphPage.relationName')" width="100" />
                <el-table-column width="70">
                  <template #default="{ row }">
                    <el-button text type="danger" size="small" @click="removeRelation(row)">{{ t('graphPage.remove') }}</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-card>
          </el-col>
        </el-row>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import http from '../api/http'

const route = useRoute()
const { t } = useI18n()
const kbId = Number(route.params.id)
const width = 860
const height = 480
const graph = reactive<any>({ entities: [], relations: [], entityCount: 0, relationCount: 0 })
const entities = computed<any[]>(() => graph.entities || [])
const relations = computed<any[]>(() => graph.relations || [])
const savingEntity = ref(false)
const savingRelation = ref(false)
const entityForm = reactive({ name: '', entityType: '', description: '' })
const relationForm = reactive<any>({ fromId: null, toId: null, relation: '', weight: 1 })
const extractVisible = ref(false)
const extracting = ref(false)
const extractText = ref('')
const extractCommit = ref(true)

onMounted(load)

async function load() {
  try {
    const data = await http.get(`/knowledge-base/${kbId}/graph`) as any
    Object.assign(graph, data || {})
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('graphPage.loadFail'))
  }
}

async function addEntity() {
  if (!entityForm.name.trim()) { ElMessage.warning(t('graphPage.nameRequired')); return }
  savingEntity.value = true
  try {
    await http.post(`/knowledge-base/${kbId}/graph/entities`, entityForm)
    entityForm.name = ''
    entityForm.entityType = ''
    entityForm.description = ''
    ElMessage.success(t('graphPage.added'))
    await load()
  } finally { savingEntity.value = false }
}

async function addRelation() {
  if (!relationForm.fromId || !relationForm.toId || !relationForm.relation.trim()) {
    ElMessage.warning(t('graphPage.relationRequired')); return
  }
  savingRelation.value = true
  try {
    await http.post(`/knowledge-base/${kbId}/graph/relations`, relationForm)
    relationForm.relation = ''
    ElMessage.success(t('graphPage.added'))
    await load()
  } finally { savingRelation.value = false }
}

async function runExtract() {
  extracting.value = true
  try {
    const res = await http.post(`/knowledge-base/${kbId}/graph/extract`, {
      text: extractText.value || null, commit: extractCommit.value
    }) as any
    if (!res || (!res.entityCount && !res.relationCount)) {
      ElMessage.warning(t('graphPage.noExtract')); return
    }
    ElMessage.success(`${t('graphPage.extracted')}${res.entityCount}${t('graphPage.extractedMid')}${res.relationCount}${t('graphPage.extractedSuffix')}`)
    if (extractCommit.value) {
      extractVisible.value = false
      await load()
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('graphPage.saveFail'))
  } finally { extracting.value = false }
}

async function removeEntity(row: any) {
  await ElMessageBox.confirm(`${t('graphPage.remove')} ${row.name}?`, t('graphPage.remove'), { type: 'warning' })
  await http.delete(`/knowledge-base/${kbId}/graph/entities/${row.id}`)
  ElMessage.success(t('graphPage.deleted'))
  await load()
}

async function removeRelation(row: any) {
  await ElMessageBox.confirm(`${t('graphPage.remove')} ${row.relation}?`, t('graphPage.remove'), { type: 'warning' })
  await http.delete(`/knowledge-base/${kbId}/graph/relations/${row.id}`)
  ElMessage.success(t('graphPage.deleted'))
  await load()
}

function pos(id: number) {
  const idx = Math.max(0, entities.value.findIndex(e => e.id === id))
  const total = Math.max(1, entities.value.length)
  const angle = (Math.PI * 2 * idx) / total - Math.PI / 2
  return { x: width / 2 + Math.cos(angle) * 300, y: height / 2 + Math.sin(angle) * 170 }
}

function nameOf(id: number) {
  return entities.value.find(e => e.id === id)?.name || `#${id}`
}

function short(name: string) {
  return name && name.length > 8 ? name.slice(0, 8) + '…' : name
}
</script>

<style scoped>
.page { background: #fff; border: 1px solid #e4e7ed; border-radius: 6px; padding: 14px; }
.toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.title { font-weight: 600; font-size: 15px; }
.spacer { flex: 1; }
.mt12 { margin-top: 12px; }
.graph-canvas { width: 100%; height: 480px; background: #fafafa; border-radius: 6px; }
.node-label { font-size: 11px; fill: #303133; }
.type-label { font-size: 10px; fill: #909399; }
.rel-label { font-size: 10px; fill: #606266; }
</style>
