<template>
  <div class="page">
    <div class="toolbar">
      <span class="page-title">模型接入</span>
      <el-button type="primary" @click="createVisible = true">接入新 Provider</el-button>
    </div>

    <el-card shadow="never" class="mb">
      <template #header>Provider（平台预置 + 租户自建）</template>
      <el-table :data="providers" size="small">
        <el-table-column prop="name" label="名称" width="140" />
        <el-table-column prop="providerType" label="类型" width="110" />
        <el-table-column prop="baseUrl" label="Base URL" min-width="200" />
        <el-table-column label="归属" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.preset ? 'info' : 'success'">{{ row.preset ? '平台预置' : '租户自建' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90" />
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button size="small" @click="test(row)">测试</el-button>
            <el-button size="small" type="primary" plain @click="sync(row)">同步模型</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>模型列表（默认定价为预填，计价权威为计费规则）</template>
      <el-table :data="models" size="small">
        <el-table-column prop="displayName" label="名称" width="180" />
        <el-table-column prop="modelName" label="模型 ID" width="150" />
        <el-table-column prop="providerName" label="Provider" width="120" />
        <el-table-column prop="purpose" label="用途" width="90" />
        <el-table-column label="启停" width="90">
          <template #default="{ row }">
            <el-switch :model-value="modelStatus(row)" @change="(v: any) => toggleModel(row, v)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="editPrice(row)">定价</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="createVisible" title="接入新 Provider" width="520px">
      <el-form label-width="90px">
        <el-form-item label="名称" required><el-input v-model="form.name" placeholder="如：智谱" /></el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="form.providerType" style="width: 100%">
            <el-option v-for="t in ['OPENAI_COMPATIBLE','DEEPSEEK','DASHSCOPE','OLLAMA','VOLC']" :key="t" :value="t" :label="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="Base URL" required>
          <el-input v-model="form.baseUrl" placeholder="https://api.example.com（OpenAI 兼容）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="create">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="priceVisible" title="模型默认定价" width="420px">
      <el-form label-width="120px">
        <el-form-item label="输入 元/千Token">
          <el-input-number v-model="price.input" :min="0" :step="0.001" :precision="4" />
        </el-form-item>
        <el-form-item label="输出 元/千Token">
          <el-input-number v-model="price.output" :min="0" :step="0.001" :precision="4" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="priceVisible = false">取消</el-button>
        <el-button type="primary" @click="savePrice">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'

const providers = ref<any[]>([])
const models = ref<any[]>([])
const disabled = ref<Set<number>>(new Set())
const createVisible = ref(false)
const creating = ref(false)
const priceVisible = ref(false)
const price = reactive({ id: 0, input: 0, output: 0 })
const form = reactive({ name: '', providerType: 'OPENAI_COMPATIBLE', baseUrl: '' })

onMounted(load)

async function load() {
  providers.value = await http.get('/provider') as any
  models.value = await http.get('/provider/models') as any
  disabled.value = new Set(models.value.filter((m: any) => m.status === 'DISABLED').map((m: any) => m.modelId))
}

async function create() {
  if (!form.name.trim() || !form.baseUrl.trim()) { ElMessage.warning('名称和 Base URL 必填'); return }
  creating.value = true
  try {
    await http.post('/provider', { ...form })
    ElMessage.success('已创建，凭证请在测试连通后由管理员配置')
    createVisible.value = false
    await load()
  } finally {
    creating.value = false
  }
}

async function test(row: any) {
  const res = await http.post(`/provider/${row.id}/test`) as any
  ElMessage.success(`连通正常，可用模型 ${res.modelCount ?? '?'} 个，耗时 ${res.latencyMs ?? '?'}ms`)
}

async function sync(row: any) {
  const res = await http.post(`/provider/${row.id}/models/sync`) as any
  ElMessage.success(`同步完成：新增 ${res.inserted ?? 0}，更新 ${res.updated ?? 0}`)
  await load()
}

function modelStatus(row: any) {
  return !disabled.value.has(row.modelId)
}

async function toggleModel(row: any, enabled: boolean) {
  await http.put(`/models/${row.modelId}`, { status: enabled ? 'ENABLED' : 'DISABLED' })
  if (enabled) disabled.value.delete(row.modelId)
  else disabled.value.add(row.modelId)
  disabled.value = new Set(disabled.value)
  ElMessage.success(enabled ? '已启用' : '已停用')
}

function editPrice(row: any) {
  price.id = row.modelId
  price.input = Number(row.inputPrice ?? 0)
  price.output = Number(row.outputPrice ?? 0)
  priceVisible.value = true
}

async function savePrice() {
  await http.put(`/models/${price.id}`, { inputPrice: price.input, outputPrice: price.output })
  ElMessage.success('已保存默认定价')
  priceVisible.value = false
}
</script>

<style scoped>
.page { max-width: 1100px; margin: 0 auto; }
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.page-title { font-size: 16px; font-weight: 600; }
.mb { margin-bottom: 14px; }
</style>
