<template>
  <el-divider />
  <el-form-item :label="t('agentEdit.routingLabel')">
    <el-switch v-model="form.routingEnabled" />
    <span class="hint" style="margin-left: 10px">{{ t('agentEdit.routingHint') }}</span>
  </el-form-item>
  <template v-if="form.routingEnabled">
    <el-form-item :label="t('agentEdit.classifierModel')">
      <el-select v-model="form.routingClassifierModel" clearable
                 :placeholder="t('agentEdit.classifierPh')" style="width: 320px">
        <el-option v-for="m in models" :key="m.modelName" :value="m.modelName"
                   :label="modelLabel(m)" />
      </el-select>
      <span class="hint">{{ t('agentEdit.classifierHint') }}</span>
    </el-form-item>
    <el-form-item :label="t('agentEdit.simpleModel')">
      <el-select v-model="form.routingSimpleModel" clearable
                 :placeholder="t('agentEdit.simpleHint')" style="width: 320px">
        <el-option v-for="m in models" :key="m.modelName" :value="m.modelName"
                   :label="modelLabel(m)" />
      </el-select>
      <span class="hint">{{ t('agentEdit.simpleHint') }}</span>
    </el-form-item>
    <el-form-item :label="t('agentEdit.complexModel')">
      <el-select v-model="form.routingComplexModel" clearable
                 :placeholder="t('agentEdit.complexHint')" style="width: 320px">
        <el-option v-for="m in models" :key="m.modelName" :value="m.modelName"
                   :label="modelLabel(m)" />
      </el-select>
      <span class="hint">{{ t('agentEdit.complexHint') }}</span>
    </el-form-item>
    <el-form-item :label="t('agentEdit.rulesLabel')">
      <div>
        <div v-for="(rule, idx) in form.routingRules" :key="idx" class="routing-rule">
          <el-input v-model="rule.keywords" :placeholder="t('agentEdit.ruleKeywordsPh')"
                    style="width: 240px" />
          <el-select v-model="rule.model" clearable :placeholder="t('agentEdit.ruleModelPh')"
                     style="width: 220px">
            <el-option v-for="m in models" :key="m.modelName" :value="m.modelName"
                       :label="modelLabel(m)" />
          </el-select>
          <el-button link type="danger" @click="form.routingRules.splice(idx, 1)">×</el-button>
        </div>
        <el-button size="small" @click="form.routingRules.push({ keywords: '', model: '' })">
          {{ t('agentEdit.addRule') }}
        </el-button>
      </div>
    </el-form-item>
  </template>
</template>

<script setup lang="ts">
defineProps<{
  models: any[]
  form: any
  t: (key: string, ...args: any[]) => string
  modelLabel: (model: any) => string
}>()
</script>
