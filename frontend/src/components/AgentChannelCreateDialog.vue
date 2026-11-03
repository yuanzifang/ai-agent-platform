<template>
  <el-dialog :model-value="modelValue" :title="t('agentDlg.addChannelTitle')" width="560px"
             @update:model-value="$emit('update:modelValue', $event)">
    <el-form label-width="110px">
      <el-form-item :label="t('agentEdit.name')"><el-input v-model="form.name" maxlength="64" /></el-form-item>
      <el-form-item :label="t('agentDlg.type')">
        <el-select v-model="form.type" style="width: 200px">
          <el-option :label="t('agentDlg.webhook')" value="WEBHOOK" />
          <el-option label="Slack" value="SLACK" />
          <el-option :label="t('agentDlg.dingtalk')" value="DINGTALK" />
          <el-option :label="t('agentDlg.feishu')" value="FEISHU" />
          <el-option :label="t('agentDlg.wecom')" value="WECOM" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('agentDlg.outbound')">
        <el-input v-model="form.outboundUrl" :placeholder="t('agentDlg.outboundPh')" />
      </el-form-item>
      <el-form-item :label="t('agentDlg.secret')">
        <el-input v-model="form.secret" type="password" show-password :placeholder="t('agentDlg.secretPh')" />
      </el-form-item>
      <el-form-item :label="t('agentDlg.verifyToken')">
        <el-input v-model="form.verificationToken" :placeholder="t('agentDlg.verifyPh')" />
      </el-form-item>
      <el-form-item label="Encrypt Key">
        <el-input v-model="form.encryptKey" type="password" show-password :placeholder="t('agentDlg.encryptPh')" />
      </el-form-item>
      <el-form-item label="EncodingAESKey">
        <el-input v-model="form.encodingAesKey" type="password" show-password :placeholder="t('agentDlg.aesPh')" />
      </el-form-item>
      <el-form-item label="CorpID/AppID">
        <el-input v-model="form.corpId" :placeholder="t('agentDlg.corpPh')" />
      </el-form-item>
      <el-form-item :label="t('agentDlg.rateLimit')">
        <el-input-number v-model="form.rateLimitPerMinute" :min="0" :max="10000" />
        <span class="hint ml8">{{ t('agentDlg.unlimited') }}</span>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">{{ t('agentDlg.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="$emit('create')">{{ t('agentDlg.create') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
defineProps<{
  modelValue: boolean
  form: any
  saving: boolean
  t: (key: string, ...args: any[]) => string
}>()
defineEmits<{
  'update:modelValue': [value: boolean]
  create: []
}>()
</script>
