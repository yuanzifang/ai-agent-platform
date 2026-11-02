<template>
  <div class="policy-page">
    <el-card class="policy-card" shadow="never">
      <div class="policy-head">
        <el-button text @click="$router.back()">{{ zh ? '返回' : 'Back' }}</el-button>
        <el-tag size="small" type="info">{{ zh ? '租户可查看' : 'Tenant visible' }}</el-tag>
      </div>
      <h1>{{ zh ? '模型数据留存与训练政策' : 'Model Data Retention & Training Policy' }}</h1>
      <p class="updated">{{ zh ? '更新日期：2026-11-02' : 'Updated: 2026-11-02' }}</p>

      <template v-if="zh">
        <h2>1. 平台自身</h2>
        <p>平台不会使用租户的对话、知识库文档或附件训练平台自有模型。平台数据按租户隔离存储，备份按部署配置加密保存。</p>
        <h2>2. 模型调用与数据出境</h2>
        <p>使用对话、知识库检索、图片/语音等能力时，必要的提示词、检索片段、附件内容会发送给租户选择的模型服务。若选择本地 Ollama，数据留在本部署环境；若选择外部 Provider（如 DeepSeek 或其他 OpenAI 兼容服务），数据会发送给该供应商。</p>
        <p>外部供应商的留存期限、是否用于训练、数据出境和删除机制由其服务条款和隐私政策决定，平台无法替代供应商作出承诺。对数据训练或出境有严格要求的租户应选择本地模型，或由管理员先完成供应商条款评估。</p>
        <h2>3. 留存与删除</h2>
        <p>消息默认进入 90 天归档策略，审计记录默认保留 180 天，系统日志默认 7 天；Trace、附件、知识库和备份的实际保留时间以部署配置及租户配置为准。租户可通过删除会话、知识库文档、附件或联系管理员申请清理；备份中的历史副本需按备份保留周期自然过期。</p>
        <h2>4. 租户控制</h2>
        <p>租户可在模型/Provider 配置中选择本地模型或外部模型，在知识库和 Agent 配置中控制数据范围，并通过成员权限限制访问。上线前应将本说明与供应商实际条款、隐私政策和用户协议一并提供给租户确认。</p>
        <h2>5. 说明</h2>
        <p>本页是平台透明说明，不替代与租户签署的合同、隐私政策、用户协议或模型供应商条款。具体供应商名称、留存周期和训练状态以管理员配置及供应商最新条款为准。</p>
      </template>

      <template v-else>
        <h2>1. The platform</h2>
        <p>The platform does not use tenant conversations, knowledge documents, or attachments to train its own models. Tenant data is isolated per tenant, and backups are encrypted according to deployment configuration.</p>
        <h2>2. Model calls and cross-border data</h2>
        <p>When using chat, retrieval, image, or speech features, the necessary prompts, retrieved snippets, and attachment content are sent to the model service selected by the tenant. With local Ollama, data remains in the deployment environment. With an external Provider (for example DeepSeek or another OpenAI-compatible service), data is sent to that provider.</p>
        <p>The external provider's retention period, training use, cross-border transfer, and deletion practices are governed by its own terms and privacy policy. The platform cannot make commitments on the provider's behalf. Tenants with strict training or transfer requirements should use a local model or have an administrator review the provider's terms first.</p>
        <h2>3. Retention and deletion</h2>
        <p>Messages default to a 90-day archive policy, audit records default to 180 days, and system logs default to 7 days. Actual retention for traces, attachments, knowledge bases, and backups follows deployment and tenant configuration. Tenants may delete conversations, documents, and attachments or contact an administrator; historical copies in backups expire with the backup retention period.</p>
        <h2>4. Tenant controls</h2>
        <p>Tenants may choose local or external models in Provider settings, limit data scope through knowledge base and Agent configuration, and restrict access through member permissions. Before launch, this notice should be provided together with the actual provider terms, privacy policy, and user agreement.</p>
        <h2>5. Notice</h2>
        <p>This page is a transparency notice. It does not replace the contract, privacy policy, user agreement, or model provider terms. Provider names, retention periods, and training status are subject to administrator configuration and the provider's latest terms.</p>
      </template>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const { locale } = useI18n()
const zh = computed(() => String(locale.value).toLowerCase().startsWith('zh'))
</script>

<style scoped>
.policy-page { min-height: 100vh; background: #f5f7fa; padding: 32px 16px; box-sizing: border-box; }
.policy-card { max-width: 900px; margin: 0 auto; line-height: 1.75; }
.policy-head { display: flex; justify-content: space-between; align-items: center; }
h1 { margin: 16px 0 4px; font-size: 24px; }
h2 { margin: 24px 0 8px; font-size: 16px; }
p { margin: 8px 0; color: #303133; }
.updated { color: #909399; font-size: 12px; }
</style>
