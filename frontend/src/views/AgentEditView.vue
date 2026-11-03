<template>
  <div class="edit-page">
    <!-- 顶栏 -->
    <div class="topbar">
      <el-button @click="$router.push('/agents')" text>
        <el-icon><ArrowLeft /></el-icon> {{ t('agentEdit.back') }}
      </el-button>
      <div class="title">
        {{ t('page.agentEdit') }}
        <el-tag size="small" :type="form.status === 'PUBLISHED' ? 'success' : 'info'" class="ml8">
          {{ form.status === 'PUBLISHED' ? t('agentEdit.published') : t('agentEdit.draft') }}
        </el-tag>
      </div>
      <div class="actions">
        <el-button @click="openVersions">{{ t('agentEdit.versions') }}</el-button>
        <el-button @click="openIntegration">{{ t('agentEdit.integration') }}</el-button>
        <el-button @click="openChannels">{{ t('agentEdit.channels') }}</el-button>
        <el-button @click="openSchedules">{{ t('agentEdit.schedules') }}</el-button>
        <el-button @click="openCredentials">{{ t('agentEdit.credentials') }}</el-button>
        <el-button @click="evalEntry">{{ t('agentEdit.eval') }}</el-button>
        <el-button @click="openPublishInfo">{{ t('deploy.publishInfo') }}</el-button>
        <el-button @click="openDeploy">{{ t('deploy.button') }}</el-button>
        <el-button type="success" plain @click="publish()">{{ t('agentEdit.publish') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('agentEdit.save') }}</el-button>
      </div>
    </div>
    <div class="edit-body">
      <!-- 左侧：配置面板 -->
      <div class="panels">
        <el-collapse v-model="activePanels">
          <el-collapse-item name="base">
            <template #title><span class="p-title">{{ t('agentEdit.basic') }}</span></template>
            <el-form label-width="70px">
              <el-form-item :label="t('agentEdit.name')"><el-input v-model="form.name" maxlength="64" /></el-form-item>
              <el-form-item :label="t('agentEdit.desc')"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
              <el-form-item :label="t('agentEdit.category')"><el-input v-model="form.category" :placeholder="t('agentEdit.categoryPh')" style="width: 200px" /></el-form-item>
              <el-form-item :label="t('agentEdit.tags')"><el-input v-model="tagsStr" :placeholder="t('agentEdit.tagsPh')" /></el-form-item>
            </el-form>
          </el-collapse-item>
          <el-collapse-item name="model">
            <template #title><span class="p-title">{{ t('agentEdit.modelSection') }}</span></template>
            <el-form label-width="70px">
              <el-form-item :label="t('agentEdit.model')" required>
                <el-select v-model="form.modelName" style="width: 320px">
                  <el-option v-for="m in models" :key="m.modelName" :value="m.modelName"
                             :label="modelLabel(m)" />
                </el-select>
                <div class="model-policy-link">
                  <router-link to="/data-policy">{{ t('agentEdit.modelDataPolicy') }}</router-link>
                </div>
              </el-form-item>
              <el-form-item :label="t('agentEdit.fallback')">
                <el-select v-model="form.fallbackModelName" clearable :placeholder="t('agentEdit.fallbackPh')"
                           style="width: 320px">
                  <el-option v-for="m in models.filter(x => x.modelName !== form.modelName)"
                             :key="m.modelName" :value="m.modelName" :label="modelLabel(m)" />
                </el-select>
                <span class="hint">{{ t('agentEdit.fallbackHint') }}</span>
              </el-form-item>
              <el-form-item :label="t('agentEdit.grayModel')">
                <el-select v-model="form.grayModel" clearable :placeholder="t('agentEdit.grayModelPh')"
                           style="width: 320px">
                  <el-option v-for="m in models" :key="m.modelName" :value="m.modelName" :label="modelLabel(m)" />
                </el-select>
                <span class="hint">{{ t('agentEdit.grayHint') }}</span>
              </el-form-item>
              <el-form-item :label="t('agentEdit.grayTagLabel')">
                <el-input v-model="form.grayTag" :placeholder="t('agentEdit.grayTagPh')" style="width: 320px" />
              </el-form-item>
              <el-form-item :label="t('agentEdit.temperature')">
                <el-slider v-model="form.temperature" :min="0" :max="2" :step="0.1" style="width: 320px"
                           :disabled="thinkingActive" />
                <span v-if="thinkingActive" class="hint">{{ t('agentEdit.temperatureOffHint') }}</span>
              </el-form-item>
              <el-form-item :label="t('agentEdit.thinking')">
                <el-switch v-model="form.thinking" :disabled="!supportsThinking" />
                <span class="hint" style="margin-left: 10px">
                  {{ supportsThinking ? t('agentEdit.thinkingHint') : t('agentEdit.thinkingUnsupported') }}
                </span>
              </el-form-item>
              <el-form-item v-if="thinkingActive" :label="t('agentEdit.thinkingLevel')">
                <el-select v-model="form.reasoningEffort" style="width: 200px">
                  <el-option v-for="lv in reasoningLevels" :key="lv" :value="lv"
                             :label="t('agentEdit.thinkingLevels.' + lv)" />
                </el-select>
                <span class="hint">{{ t('agentEdit.thinkingLevelHint') }}</span>
              </el-form-item>
              <AgentRoutingPanel :models="models" :form="form" :t="t" :model-label="modelLabel" />
            </el-form>
          </el-collapse-item>
          <el-collapse-item name="prompt">
            <template #title><span class="p-title">{{ t('agentEdit.prompts') }}</span><span class="req">*</span></template>
            <div class="prompt-actions">
              <el-button size="small" @click="openTemplatePicker">{{ t('agentEdit.insertTemplate') }}</el-button>
              <el-button size="small" type="primary" plain @click="openOptimize">{{ t('agentEdit.optimize') }}</el-button>
              <span class="hint">{{ t('agentEdit.promptHint') }}</span>
            </div>
            <el-input v-model="form.systemPrompt" type="textarea" :rows="8"
                      :placeholder="t('agentEdit.systemPromptPh')" />
          </el-collapse-item>
          <el-collapse-item name="welcome">
            <template #title><span class="p-title">{{ t('agentEdit.welcome') }}</span></template>
            <el-input v-model="form.openingStatement" type="textarea" :rows="3"
                      :placeholder="t('agentEdit.welcomePh')" />
          </el-collapse-item>
          <el-collapse-item name="capability">
            <template #title><span class="p-title">{{ t('agentEdit.capabilities') }}</span></template>
            <el-form label-width="90px">
              <el-form-item :label="t('agentEdit.stream')"><el-switch v-model="form.capabilities.streamOutput" /></el-form-item>
              <el-form-item :label="t('agentEdit.functionCall')"><el-switch v-model="form.capabilities.functionCall" /></el-form-item>
              <el-form-item :label="t('agentEdit.maxToolCalls')">
                <el-input-number v-model="form.capabilities.maxToolCalls" :min="0" :max="100" />
                <span class="hint">{{ t('agentEdit.maxToolCallsHint') }}</span>
              </el-form-item>
              <el-form-item :label="t('agentEdit.maxInputValidationAttempts')">
                <el-input-number v-model="form.capabilities.maxInputValidationAttempts" :min="1" :max="20" />
                <span class="hint">{{ t('agentEdit.maxInputValidationHint') }}</span>
              </el-form-item>
              <el-form-item :label="t('agentEdit.maxToolOutputChars')">
                <el-input-number v-model="form.capabilities.maxToolOutputChars" :min="0" :max="100000" :step="1000" />
                <span class="hint">{{ t('agentEdit.maxToolOutputHint') }}</span>
              </el-form-item>
              <el-form-item :label="t('agentEdit.redactSecrets')">
                <el-switch v-model="form.capabilities.redactSecrets" />
                <span class="hint">{{ t('agentEdit.redactSecretsHint') }}</span>
              </el-form-item>
              <el-form-item :label="t('agentEdit.redactPatterns')">
                <el-input v-model="redactPatternsText" type="textarea" :rows="2"
                          placeholder='["secret-\\d+", "internal-token-[A-Za-z0-9]+"]' />
                <span class="hint">{{ t('agentEdit.redactPatternsHint') }}</span>
              </el-form-item>
              <el-form-item :label="t('agentEdit.selfCorrection')">
                <el-switch v-model="form.capabilities.selfCorrection" />
                <span class="hint">{{ t('agentEdit.correctionHint') }}</span>
              </el-form-item>
              <el-form-item :label="t('agentEdit.reviewRounds')">
                <el-input-number v-model="form.capabilities.selfCorrectionRounds" :min="1" :max="3"
                                 :disabled="!form.capabilities.selfCorrection" />
                <span class="hint">{{ t('agentEdit.reviewHint') }}</span>
              </el-form-item>
              <el-form-item :label="t('agentEdit.multimodal')"><el-switch v-model="form.capabilities.multimodal" /></el-form-item>
              <el-form-item :label="t('agentEdit.writePolicy')">
                <el-select v-model="form.capabilities.writeToolPolicy" style="width: 220px">
                  <el-option :label="t('agentEdit.writePolicyAllowAll')" value="ALLOW_ALL" />
                  <el-option :label="t('agentEdit.writePolicyBlock')" value="BLOCK" />
                  <el-option :label="t('agentEdit.writePolicyApproval')" value="APPROVAL" />
                  <el-option :label="t('agentEdit.writePolicyAllowlist')" value="ALLOWLIST" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="form.capabilities.writeToolPolicy === 'ALLOWLIST'" :label="t('agentEdit.allowedWriteTools')">
                <el-select v-model="form.capabilities.allowedWriteToolIds" multiple filterable style="width: 100%">
                  <el-option v-for="row in toolRows" :key="row.id" :value="row.id" :label="row.name" />
                </el-select>
              </el-form-item>
              <el-form-item :label="t('agentEdit.writeToolMaxArgs')">
                <el-input-number v-model="form.capabilities.writeToolMaxArgs" :min="1024" :max="100000" :step="1024" />
              </el-form-item>
              <el-form-item :label="t('agentEdit.writeToolPatterns')">
                <el-input v-model="writeToolPatternsText" type="textarea" :rows="2"
                          placeholder='{"12":{"orderId":"^[A-Z0-9-]{6,32}$"}}' />
              </el-form-item>
              <el-form-item :label="t('agentEdit.writeToolAmountLimits')">
                <el-input v-model="writeToolAmountLimitsText" type="textarea" :rows="2"
                          placeholder='{"12":{"amount":1000}}' />
              </el-form-item>
            </el-form>
          </el-collapse-item>
          <el-collapse-item name="memory">
            <template #title><span class="p-title">{{ t('agentEdit.memory') }}</span></template>
            <el-form label-width="90px">
              <el-form-item :label="t('agentEdit.memoryEnabled')"><el-switch v-model="form.memory.enabled" /></el-form-item>
              <el-form-item :label="t('agentEdit.windowSize')">
                <el-input-number v-model="form.memory.windowSize" :min="2" :max="50" :disabled="!form.memory.enabled" />
              </el-form-item>
              <el-form-item :label="t('agentEdit.autoExtract')">
                <el-switch v-model="form.memory.autoExtract" :disabled="!form.memory.enabled" />
                <span class="hint">{{ t('agentEdit.autoExtractHint') }}</span>
              </el-form-item>
            </el-form>
          </el-collapse-item>
          <el-collapse-item name="knowledge">
            <template #title><span class="p-title">{{ t('agentEdit.kb') }}</span>
              <el-tag size="small" class="ml8">{{ enabledKbCount ? `${t('agentEdit.boundCount')}${enabledKbCount}${t('agentEdit.countSuffix')}` : t('agentEdit.unbound') }}</el-tag>
            </template>
            <el-form-item :label="t('agentEdit.resourceProfile')">
              <el-select v-model="form.resourceProfileId" clearable style="width: 300px"
                         :placeholder="t('agentEdit.resourceProfilePh')">
                <el-option v-for="p in resourceProfiles" :key="p.id" :value="p.id"
                           :label="`${p.name} · ${p.searchBackend}/${p.vectorBackend}`" />
              </el-select>
              <span class="hint">{{ t('agentEdit.resourceProfileHint') }}</span>
            </el-form-item>
            <div v-if="kbRows.length === 0" class="hint">{{ t('agentEdit.noKb') }}</div>
            <div v-for="kb in kbRows" :key="kb.knowledgeBaseId" class="kb-row">
              <el-switch v-model="kb.enabled" :disabled="kb.missing || !kbCompatible(kb)" />
              <div class="kb-name">{{ kb.name }}</div>
              <el-tag v-if="kb.missing" size="small" type="danger">{{ t('agentEdit.invalid') }}</el-tag>
              <el-tag v-else-if="!kbCompatible(kb)" size="small" type="warning">{{ t('agentEdit.resourceMismatch') }}</el-tag>
              <div class="kb-params" v-if="kb.enabled">
                {{ t('agentEdit.mode') }}
                <el-select v-model="kb.mode" size="small" style="width: 100px">
                  <el-option :label="t('agentEdit.hybrid')" value="hybrid" />
                  <el-option :label="t('agentEdit.vector')" value="vector" />
                  <el-option :label="t('agentEdit.keyword')" value="keyword" />
                </el-select>
                topK <el-input-number v-model="kb.topK" :min="1" :max="kb.maxTopK || 20" size="small" />
                {{ t('agentEdit.threshold') }} <el-input-number v-model="kb.scoreThreshold" :min="0" :max="1" :step="0.05" size="small" />
                <KbRerankControl :kb="kb" :t="t" />
              </div>
              <div class="kb-params" v-if="kb.enabled">
                <el-select v-model="kb.documentType" size="small" clearable
                           :placeholder="t('kbDetail.docType')" style="width: 120px">
                  <el-option :label="t('kbDetail.docTypeResearch')" value="research_paper" />
                  <el-option :label="t('kbDetail.docTypeRegulatory')" value="regulatory" />
                  <el-option :label="t('kbDetail.docTypeClinical')" value="clinical_trial" />
                  <el-option :label="t('kbDetail.docTypeGeneral')" value="general" />
                </el-select>
                <el-select v-model="kb.sectionType" size="small" clearable
                           :placeholder="t('kbDetail.sectionType')" style="width: 115px">
                  <el-option v-for="s in sectionOptions" :key="s" :label="s" :value="s" />
                </el-select>
                <el-select v-model="kb.regulatoryCategory" size="small" clearable
                           :placeholder="t('kbDetail.regulatoryCategory')" style="width: 110px">
                  <el-option label="FDA" value="FDA" />
                  <el-option label="EMA" value="EMA" />
                  <el-option label="ICH" value="ICH" />
                </el-select>
                <el-select v-model="kb.chunkLevel" size="small" clearable
                           :placeholder="t('kbDetail.chunkLevel')" style="width: 95px">
                  <el-option v-for="n in [1, 2, 3, 4]" :key="n" :label="String(n)" :value="n" />
                </el-select>
                {{ t('kbDetail.minRelevance') }}
                <el-input-number v-model="kb.minRelevance" :min="0" :max="1" :step="0.05"
                                 size="small" style="width: 105px" />
              </div>
            </div>
          </el-collapse-item>

          <el-collapse-item name="variable">
            <template #title><span class="p-title">{{ t('agentEdit.vars') }}</span>
              <el-tag size="small" class="ml8">{{ form.variables.length }}{{ t('agentEdit.varsSuffix') }}</el-tag>
            </template>
            <el-button size="small" @click="addVariable">{{ t('agentEdit.addVar') }}</el-button>
            <el-table :data="form.variables" size="small" v-if="form.variables.length" class="mt8">
              <el-table-column label="key" width="140">
                <template #default="{ row }"><el-input v-model="row.key" placeholder="user_name" size="small" /></template>
              </el-table-column>
              <el-table-column :label="t('agentEdit.type')" width="110">
                <template #default="{ row }">
                  <el-select v-model="row.type" size="small">
                    <el-option v-for="t in ['STRING','NUMBER','SELECT','FILE']" :key="t" :value="t" :label="t" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column :label="t('agentEdit.required')" width="60">
                <template #default="{ row }"><el-checkbox v-model="row.required" /></template>
              </el-table-column>
              <el-table-column :label="t('agentEdit.defaultValue')" width="130">
                <template #default="{ row }"><el-input v-model="row.defaultValue" size="small" /></template>
              </el-table-column>
              <el-table-column :label="t('agentEdit.varDesc')">
                <template #default="{ row }"><el-input v-model="row.description" size="small" /></template>
              </el-table-column>
              <el-table-column width="60">
                <template #default="{ $index }">
                  <el-button text type="danger" size="small" @click="form.variables.splice($index, 1)">{{ t('agentEdit.remove') }}</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-collapse-item>

          <el-collapse-item name="access">
            <template #title><span class="p-title">{{ t('agentEdit.visibility') }}</span>
              <el-tag size="small" class="ml8">{{ form.visibility === 'PRIVATE' ? t('agentEdit.private') : t('agentEdit.tenantVisible') }}</el-tag>
            </template>
            <el-form label-width="90px">
              <el-form-item :label="t('agentEdit.visibilityLabel')">
                <el-radio-group v-model="form.visibility">
                  <el-radio value="TENANT">{{ t('agentEdit.tenantAll') }}</el-radio>
                  <el-radio value="PRIVATE">{{ t('agentEdit.privateAuth') }}</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item v-if="form.status === 'PUBLISHED'" :label="t('agentEdit.listed')">
                <el-switch v-model="form.listed" @change="toggleListing" />
                <span class="hint ml8">{{ t('agentEdit.delistedHint') }}</span>
              </el-form-item>
              <el-form-item v-if="form.visibility === 'PRIVATE'" :label="t('agentEdit.grantedUsers')">
                <el-select v-model="grantedIds" multiple :placeholder="t('agentEdit.selectUsers')" style="width: 100%">
                  <el-option v-for="m in members.filter(m => m.id !== ownerId)"
                             :key="m.id" :value="m.id"
                             :label="`${m.displayName}(${m.username})`" />
                </el-select>
              </el-form-item>
            </el-form>
          </el-collapse-item>

          <el-collapse-item name="skill">
            <template #title><span class="p-title">{{ t('agentEdit.skills') }}</span>
              <el-tag size="small" class="ml8">{{ enabledSkillCount ? `${t('agentEdit.enabled')}${enabledSkillCount}${t('agentEdit.countSuffix')}` : t('agentEdit.notConfigured') }}</el-tag>
            </template>
            <div class="panel-links">
              <el-button size="small" @click="openPicker('skill')">{{ t('agentEdit.addSkill') }}</el-button>
              <el-button size="small" plain @click="$router.push('/skills?create=1')">{{ t('agentEdit.newSkill') }}</el-button>
              <el-link type="primary" :underline="false" @click="$router.push('/skills')">{{ t('agentEdit.skillManage') }}</el-link>
            </div>
            <div v-if="skillRows.length === 0" class="hint mt8">{{ t('agentEdit.noSkills') }}</div>
            <div v-for="row in skillRows" :key="'s' + row.id" class="kb-row">
              <el-switch v-model="row.enabled" :disabled="row.missing" />
              <div class="kb-name">{{ row.name }}</div>
              <el-tag v-if="row.isBuiltin" size="small" type="info">{{ t('agentEdit.builtin') }}</el-tag>
              <el-tag v-if="row.missing" size="small" type="danger">{{ t('agentEdit.invalid') }}</el-tag>
              <div class="row-desc">{{ row.description }}</div>
              <el-button size="small" text type="danger" @click="removeRow(skillRows, row)">{{ t('agentEdit.remove') }}</el-button>
            </div>
          </el-collapse-item>

          <el-collapse-item name="tool">
            <template #title><span class="p-title">{{ t('agentEdit.tools') }}</span>
              <el-tag size="small" class="ml8">{{ enabledToolCount ? `${t('agentEdit.enabled')}${enabledToolCount}${t('agentEdit.countSuffix')}` : t('agentEdit.notConfigured') }}</el-tag>
            </template>
            <div class="panel-links">
              <el-button size="small" @click="openPicker('tool')">{{ t('agentEdit.addTool') }}</el-button>
              <el-button size="small" plain @click="$router.push('/tools?create=1')">{{ t('agentEdit.newApiTool') }}</el-button>
              <el-button size="small" plain @click="$router.push('/mcp-servers?create=1')">{{ t('agentEdit.addMcp') }}</el-button>
              <el-link type="primary" :underline="false" @click="$router.push('/tools')">{{ t('agentEdit.toolManage') }}</el-link>
            </div>
            <div v-if="toolRows.length === 0" class="hint mt8">{{ t('agentEdit.noTools') }}</div>
            <div v-for="row in toolRows" :key="'t' + row.id" class="kb-row">
              <el-switch v-model="row.enabled" :disabled="row.missing" />
              <div class="kb-name">{{ row.name }}</div>
              <el-tag size="small"
                      :type="row.type === 'BUILTIN' ? 'info' : (row.type === 'MCP' ? 'warning' : 'success')">
                {{ row.type === 'BUILTIN' ? t('agentEdit.builtin') : (row.type === 'MCP' ? 'MCP' : 'API') }}
              </el-tag>
              <el-tag v-if="row.missing" size="small" type="danger">{{ t('agentEdit.invalid') }}</el-tag>
              <div class="row-desc">{{ row.description }}</div>
              <el-button size="small" text type="danger" @click="removeRow(toolRows, row)">{{ t('agentEdit.remove') }}</el-button>
            </div>
          </el-collapse-item>

          <el-collapse-item name="subagent">
            <template #title><span class="p-title">{{ t('agentEdit.subAgents') }}</span>
              <el-tag size="small" class="ml8">{{ enabledSubCount ? `${t('agentEdit.enabled')}${enabledSubCount}${t('agentEdit.countSuffix')}` : t('agentEdit.notConfigured') }}</el-tag>
            </template>
            <div class="panel-links">
              <el-button size="small" @click="openPicker('sub')">{{ t('agentEdit.addSub') }}</el-button>
              <el-link type="primary" :underline="false" @click="$router.push('/agents')">{{ t('agentEdit.agentList') }}</el-link>
            </div>
            <div v-if="subRows.length === 0" class="hint mt8">{{ t('agentEdit.noSubs') }}</div>
            <div v-for="row in subRows" :key="'a' + row.id" class="kb-row">
              <el-switch v-model="row.enabled" :disabled="row.missing" />
              <div class="kb-name">{{ row.name }}</div>
              <el-tag v-if="row.missing" size="small" type="danger">{{ t('agentEdit.invalid') }}</el-tag>
              <div class="row-desc">{{ row.description }}</div>
              <el-button size="small" text type="danger" @click="removeRow(subRows, row)">{{ t('agentEdit.remove') }}</el-button>
            </div>
          </el-collapse-item>

          <el-collapse-item name="workflow">
            <template #title><span class="p-title">{{ t('agentEdit.workflow') }}</span>
              <el-tag size="small" class="ml8">
                {{ workflowConfigured ? `${t('agentEdit.configuredNodes')}${workflowForm.nodes.length}${t('agentEdit.nodesSuffix')}` : t('agentEdit.notConfigured') }}
              </el-tag>
            </template>
            <div class="panel-links">
              <el-button size="small" @click="openWorkflow">{{ t('agentEdit.editWorkflow') }}</el-button>
              <el-link type="primary" :underline="false" @click="$router.push('/approvals')">{{ t('agentEdit.approvals') }}</el-link>
            </div>
            <div class="hint mt8">
              {{ t('agentEdit.workflowHint') }}
            </div>
          </el-collapse-item>
        </el-collapse>
      </div>

      <!-- 右侧：调试 -->
      <div class="debug">
        <div class="debug-head">{{ t('agentEdit.debugHead') }}</div>
        <div class="debug-msgs" ref="dbgBox">
          <div v-for="(m, i) in debugMsgs" :key="i" class="d-msg" :class="m.role.toLowerCase()">
            <div class="d-bubble">
              <div class="d-content">{{ m.content }}</div>
              <div v-if="m.toolCalls?.length" class="d-tools">
                <div v-for="(tc, j) in m.toolCalls" :key="j" class="d-tool"
                     :class="tc.status === 'ERROR' ? 'err' : ''">
                  ⚙ {{ tc.displayName || tc.toolName }}
                  <span v-if="tc.toolType === 'SUB_AGENT'">{{ t('agentEdit.subAgentSuffix') }}</span>
                  · {{ tc.status === 'ERROR' ? t('agentEdit.failed') : t('agentEdit.success') }} · {{ tc.durationMs }}ms
                </div>
              </div>
              <div v-if="m.citations?.length" class="d-cites">
                {{ t('agentEdit.citations') }}{{ m.citations.length }}{{ t('agentEdit.citationsSuffix') }}
                <span v-for="(c, j) in m.citations" :key="j" class="d-cite">
                  [{{ j + 1 }}] {{ (c.score * 100).toFixed(0) }}% {{ clip(c.content, 30) }}
                </span>
              </div>
              <div v-if="m.usage" class="d-usage">
                {{ m.latencyMs }}ms · {{ t('agentEdit.latencyInput') }}{{ m.usage.inputTokens }}{{ t('agentEdit.outputTokens') }}{{ m.usage.outputTokens }} tokens
              </div>
            </div>
          </div>
          <el-empty v-if="debugMsgs.length === 0" :description="t('agentEdit.emptyDebug')" :image-size="60" />
        </div>
        <div class="debug-input">
          <el-input v-model="debugInput" :placeholder="t('agentEdit.debugPh')" @keydown.enter.prevent="debugSend"
                    :disabled="debugRunning" />
          <el-button type="primary" :loading="debugRunning" @click="debugSend">{{ t('agentEdit.send') }}</el-button>
        </div>
      </div>
    </div>

    <!-- Agent 凭证 -->
    <el-dialog v-model="credentialVisible" :title="`${t('agentDlg.credTitle')}${form.name || ''}`" width="760px">
      <div class="tab-actions">
        <el-button type="primary" size="small" @click="openCredentialCreate">{{ t('agentDlg.newCredential') }}</el-button>
        <span class="hint">{{ t('agentDlg.credHint') }}</span>
      </div>
      <el-table :data="credentials" size="small" :empty-text="t('agentDlg.noCredentials')">
        <el-table-column prop="name" :label="t('wf.name')" width="150" />
        <el-table-column prop="type" :label="t('agentDlg.type')" width="100" />
        <el-table-column :label="t('agentDlg.boundTools')" width="170">
          <template #default="{ row }">{{ toolName(row.toolId) }}</template>
        </el-table-column>
        <el-table-column :label="t('agentDlg.status')" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">
              {{ row.status === 'ENABLED' ? t('agentDlg.enabled') : t('agentDlg.disabled') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('agentDlg.content')" width="90">
          <template #default="{ row }"><span class="hint">{{ t('agentDlg.encrypted') }}</span></template>
        </el-table-column>
        <el-table-column :label="t('agentDlg.actions')" width="150">
          <template #default="{ row }">
            <el-button size="small" text @click="openCredentialEdit(row)">{{ t('agentDlg.edit') }}</el-button>
            <el-button size="small" text type="danger" @click="removeCredential(row)">{{ t('wf.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="credentialFormVisible" :title="credentialForm.id ? t('agentDlg.credEdit') : t('agentDlg.credNew')" width="520px">
      <el-form label-width="90px">
        <el-form-item :label="t('wf.name')" required><el-input v-model="credentialForm.name" maxlength="64" /></el-form-item>
        <el-form-item :label="t('agentDlg.type')">
          <el-select v-model="credentialForm.type" style="width: 160px">
            <el-option label="API Key" value="API_KEY" />
            <el-option label="OAuth" value="OAUTH" />
            <el-option label="Basic Auth" value="BASIC" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('agentDlg.contentLabel')" required>
          <el-input v-model="credentialForm.content" type="password" show-password
                    :placeholder="credentialForm.id ? t('agentDlg.contentKeep') : t('agentDlg.contentPh')" />
        </el-form-item>
        <el-form-item :label="t('agentDlg.bindTool')">
          <el-select v-model="credentialForm.toolId" filterable clearable style="width: 100%"
                     :placeholder="t('agentDlg.bindToolPh')">
            <el-option v-for="tool in toolCatalog" :key="tool.id" :value="tool.id"
                       :label="tool.name + (tool.toolType === 'MCP' ? t('agentDlg.mcpSuffix') : '')" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('agentDlg.enableLabel')">
          <el-switch v-model="credentialForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="credentialFormVisible = false">{{ t('agentDlg.cancel') }}</el-button>
        <el-button type="primary" :loading="credentialSaving" @click="saveCredential">{{ t('agentEdit.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- 定时调度 -->
    <el-dialog v-model="scheduleVisible" :title="`${t('agentDlg.scheduleTitle')}${form.name || ''}`" width="820px">
      <div class="tab-actions">
        <el-button type="primary" size="small" @click="openScheduleCreate">{{ t('agentDlg.newTask') }}</el-button>
        <span class="hint">{{ t('agentDlg.cronHint') }}</span>
      </div>
      <el-table :data="schedules" size="small">
        <el-table-column prop="name" :label="t('wf.name')" width="150" />
        <el-table-column prop="cronExpr" label="Cron" width="150" />
        <el-table-column :label="t('agentDlg.nextRun')" width="160">
          <template #default="{ row }">{{ fmtTime(row.nextRunAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('agentDlg.status')" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">
              {{ row.status === 'ENABLED' ? t('agentDlg.enabled') : t('agentDlg.disableAction') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('agentDlg.recentRun')" width="150">
          <template #default="{ row }">
            <el-tag v-if="row.lastStatus" size="small" :type="row.lastStatus === 'SUCCESS' ? 'success' : 'danger'">
              {{ row.lastStatus }}
            </el-tag>
            <span v-else class="hint">{{ t('agentDlg.notRun') }}</span>
            <div class="hint">{{ fmtTime(row.lastRunAt) }}</div>
          </template>
        </el-table-column>
        <el-table-column :label="t('agentDlg.actions')" width="290">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="runSchedule(row)">{{ t('agentDlg.runNow') }}</el-button>
            <el-button size="small" text @click="openResults(row)">{{ t('agentDlg.results') }}</el-button>
            <el-button size="small" text @click="openScheduleEdit(row)">{{ t('agentDlg.edit') }}</el-button>
            <el-button size="small" text @click="toggleSchedule(row)">{{ row.status === 'ENABLED' ? t('agentDlg.disableAction') : t('agentDlg.enableAction') }}</el-button>
            <el-button size="small" text type="danger" @click="removeSchedule(row)">{{ t('wf.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="schedules.length === 0" :description="t('agentDlg.noSchedules')" :image-size="50" />
    </el-dialog>

    <el-dialog v-model="scheduleFormVisible" :title="scheduleForm.id ? t('agentDlg.scheduleEdit') : t('agentDlg.scheduleNew')" width="560px">
      <el-form label-width="100px">
        <el-form-item :label="t('wf.name')" required><el-input v-model="scheduleForm.name" maxlength="64" /></el-form-item>
        <el-form-item label="Cron" required>
          <el-input v-model="scheduleForm.cronExpr" :placeholder="t('agentDlg.cronPh')" />
        </el-form-item>
        <el-form-item :label="t('agentDlg.inputArgs')">
          <el-input v-model="scheduleForm.inputParamsText" type="textarea" :rows="4"
                    :placeholder="t('agentDlg.inputPh')" />
        </el-form-item>
        <el-form-item :label="t('agentDlg.timeoutMs')">
          <el-input-number v-model="scheduleForm.timeoutMs" :min="1000" :max="600000" :step="1000" />
        </el-form-item>
        <el-form-item :label="t('agentDlg.resultTo')">
          <el-select v-model="scheduleForm.notifyType" style="width: 160px">
            <el-option :label="t('agentDlg.store')" value="STORE" />
            <el-option label="Webhook" value="WEBHOOK" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="scheduleForm.notifyType === 'WEBHOOK'" :label="t('agentDlg.callback')">
          <el-input v-model="scheduleForm.webhookUrl" placeholder="https://example.com/webhook" />
        </el-form-item>
        <el-form-item :label="t('agentDlg.enableLabel')">
          <el-switch v-model="scheduleForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scheduleFormVisible = false">{{ t('agentDlg.cancel') }}</el-button>
        <el-button type="primary" :loading="scheduleSaving" @click="saveSchedule">{{ t('agentEdit.save') }}</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="resultsVisible" :title="resultsTitle" size="620px">
      <el-table :data="scheduleResults" size="small">
        <el-table-column :label="t('wf.time')" width="160">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('agentDlg.status')" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'SUCCESS' ? 'success' : 'danger'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('agentDlg.result')">
          <template #default="{ row }">
            <div class="result-text">{{ row.status === 'SUCCESS' ? row.answer : row.error }}</div>
            <div class="hint" v-if="row.traceId">trace: {{ row.traceId }}</div>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="scheduleResults.length === 0" :description="t('agentDlg.noResults')" :image-size="50" />
    </el-drawer>

    <!-- 渠道 -->
    <el-dialog v-model="channelsVisible" :title="`${t('agentDlg.channelsTitle')}${form.name || ''}`" width="820px">
      <div class="hint mb8">
        {{ t('agentDlg.channelHint') }}
      </div>
      <div class="tab-actions">
        <el-button type="primary" size="small" @click="channelCreateVisible = true">{{ t('agentDlg.addChannel') }}</el-button>
        <el-button size="small" @click="openChannels">{{ t('agentDlg.refresh') }}</el-button>
      </div>
      <el-table :data="channels" size="small">
        <el-table-column prop="name" :label="t('wf.name')" width="140" />
        <el-table-column prop="type" :label="t('agentDlg.type')" width="100" />
        <el-table-column :label="t('agentDlg.inbound')" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">{{ inboundUrl(row) }}</template>
        </el-table-column>
        <el-table-column :label="t('agentDlg.status')" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">
              {{ row.status === 'ENABLED' ? t('agentDlg.keyEnabled') : t('agentDlg.disableAction') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('agentDlg.actions')" width="280">
          <template #default="{ row }">
            <el-button size="small" text @click="copyInbound(row)">{{ t('agentDlg.copyAddr') }}</el-button>
            <el-button size="small" text :loading="channelTestingId === row.id" @click="testChannel(row)">{{ t('agentDlg.test') }}</el-button>
            <el-button size="small" text @click="openChannelMessages(row)">{{ t('agentDlg.messages') }}</el-button>
            <el-button size="small" text @click="toggleChannel(row)">
              {{ row.status === 'ENABLED' ? t('agentDlg.disableAction') : t('agentDlg.enableAction') }}
            </el-button>
            <el-button size="small" text type="danger" @click="removeChannel(row)">{{ t('wf.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="channels.length === 0" :description="t('agentDlg.noChannels')" :image-size="50" />
      <div v-if="createdInbound" class="created-key">
        <div class="ck-title">{{ t('agentDlg.inboundGenerated') }}</div>
        <div class="ck-row">
          <code class="ck-value">{{ createdInbound }}</code>
          <el-button size="small" type="primary" @click="copyText(createdInbound)">{{ t('agentDlg.copy') }}</el-button>
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="channelCreateVisible" :title="t('agentDlg.addChannelTitle')" width="560px">
      <el-form label-width="110px">
        <el-form-item :label="t('agentEdit.name')"><el-input v-model="channelForm.name" maxlength="64" /></el-form-item>
        <el-form-item :label="t('agentDlg.type')">
          <el-select v-model="channelForm.type" style="width: 200px">
            <el-option :label="t('agentDlg.webhook')" value="WEBHOOK" />
            <el-option label="Slack" value="SLACK" />
            <el-option :label="t('agentDlg.dingtalk')" value="DINGTALK" />
            <el-option :label="t('agentDlg.feishu')" value="FEISHU" />
            <el-option :label="t('agentDlg.wecom')" value="WECOM" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('agentDlg.outbound')">
          <el-input v-model="channelForm.outboundUrl" :placeholder="t('agentDlg.outboundPh')" />
        </el-form-item>
        <el-form-item :label="t('agentDlg.secret')">
          <el-input v-model="channelForm.secret" type="password" show-password :placeholder="t('agentDlg.secretPh')" />
        </el-form-item>
        <el-form-item :label="t('agentDlg.verifyToken')">
          <el-input v-model="channelForm.verificationToken" :placeholder="t('agentDlg.verifyPh')" />
        </el-form-item>
        <el-form-item label="Encrypt Key">
          <el-input v-model="channelForm.encryptKey" type="password" show-password :placeholder="t('agentDlg.encryptPh')" />
        </el-form-item>
        <el-form-item label="EncodingAESKey">
          <el-input v-model="channelForm.encodingAesKey" type="password" show-password :placeholder="t('agentDlg.aesPh')" />
        </el-form-item>
        <el-form-item label="CorpID/AppID">
          <el-input v-model="channelForm.corpId" :placeholder="t('agentDlg.corpPh')" />
        </el-form-item>
        <el-form-item :label="t('agentDlg.rateLimit')">
          <el-input-number v-model="channelForm.rateLimitPerMinute" :min="0" :max="10000" />
          <span class="hint ml8">{{ t('agentDlg.unlimited') }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="channelCreateVisible = false">{{ t('agentDlg.cancel') }}</el-button>
        <el-button type="primary" :loading="channelSaving" @click="createChannel">{{ t('agentDlg.create') }}</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="channelMessagesVisible" :title="`${t('agentDlg.channelMessages')}${messagesChannelName}`" size="640px">
      <el-table :data="channelMessages" size="small" :empty-text="t('agentDlg.noMessages')">
        <el-table-column :label="t('wf.time')" width="160">
          <template #default="{ row }">{{ String(row.createdAt || '').replace('T',' ').slice(0,19) }}</template>
        </el-table-column>
        <el-table-column prop="direction" :label="t('agentDlg.direction')" width="70" />
        <el-table-column prop="status" :label="t('agentDlg.keyStatus')" width="80" />
        <el-table-column prop="externalUserId" :label="t('agentDlg.externalUser')" width="120" show-overflow-tooltip />
        <el-table-column prop="content" :label="t('agentDlg.contentCol')" min-width="240" show-overflow-tooltip />
      </el-table>
    </el-drawer>

    <!-- 集成 API Key -->
    <el-dialog v-model="integrationVisible" :title="`${t('agentDlg.integrationTitle')}${form.name || ''}`" width="780px">
      <div class="hint mb8">
        {{ t('agentDlg.integrationLine1') }}
        {{ t('agentDlg.integrationLine2') }}
      </div>
      <div class="hint mb8">
        {{ t('agentDlg.mcpLine') }}
        {{ t('agentDlg.toolsLine') }}
      </div>
      <div class="tab-actions">
        <el-button type="primary" size="small" @click="openKeyCreate">{{ t('agentDlg.createKey') }}</el-button>
        <el-button size="small" :loading="widgetCreating" @click="createWidgetKey">{{ t('agentDlg.createWidget') }}</el-button>
        <span class="hint">{{ t('agentDlg.keyHint') }}</span>
      </div>
      <el-table :data="apiKeys" size="small">
        <el-table-column prop="name" :label="t('wf.name')" width="150" />
        <el-table-column prop="keyPrefix" label="Key" width="180" />
        <el-table-column prop="channel" :label="t('agentDlg.keyChannel')" width="100" />
        <el-table-column :label="t('agentDlg.quota')" width="140">
          <template #default="{ row }">{{ row.dailyLimit || t('agentDlg.unlimitedShort') }} / {{ row.minuteLimit || t('agentDlg.unlimitedShort') }}</template>
        </el-table-column>
        <el-table-column :label="t('agentDlg.status')" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">
              {{ row.status === 'ENABLED' ? t('agentDlg.enabled') : t('agentDlg.disabled') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('agentDlg.lastUsed')" width="160">
          <template #default="{ row }">{{ row.lastUsedAt ? String(row.lastUsedAt).replace('T',' ').slice(0,19) : t('agentDlg.unused') }}</template>
        </el-table-column>
        <el-table-column :label="t('agentDlg.actions')" width="150">
          <template #default="{ row }">
            <el-button size="small" text @click="toggleKey(row)">
              {{ row.status === 'ENABLED' ? t('agentDlg.disable') : t('agentDlg.enable') }}
            </el-button>
            <el-button size="small" text type="danger" @click="removeKey(row)">{{ t('wf.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="apiKeys.length === 0" :description="t('agentDlg.noKeys')" :image-size="50" />

      <div v-if="widgetSnippet" class="created-key">
        <div class="ck-title">{{ t('agentDlg.widgetSnippetTitle') }}</div>
        <el-input :model-value="widgetSnippet" type="textarea" :rows="3" readonly />
        <div class="mt8">
          <el-button size="small" type="primary" @click="copyWidget">{{ t('agentDlg.copySnippet') }}</el-button>
          <el-button size="small" @click="previewWidget">{{ t('agentDlg.previewWidget') }}</el-button>
        </div>
      </div>

      <div v-if="createdKey" class="created-key">
        <div class="ck-title">{{ t('agentDlg.keyCreatedTitle') }}</div>
        <div class="ck-row">
          <code class="ck-value">{{ createdKey }}</code>
          <el-button size="small" type="primary" @click="copyKey">{{ t('agentDlg.copy') }}</el-button>
        </div>
        <div class="hint">{{ t('agentDlg.keyOnceHint') }}</div>
      </div>
    </el-dialog>

    <!-- 创建 API Key -->
    <el-dialog v-model="keyCreateVisible" :title="t('agentDlg.keyCreateTitle')" width="520px">
      <el-form label-width="110px">
        <el-form-item :label="t('wf.name')" required><el-input v-model="keyForm.name" maxlength="64" /></el-form-item>
        <el-form-item :label="t('agentDlg.keyChannelLabel')">
          <el-select v-model="keyForm.channel" style="width: 180px">
            <el-option label="API" value="API" />
            <el-option label="Web" value="WEB" />
            <el-option :label="t('agentDlg.widgetChannel')" value="WIDGET" />
            <el-option :label="t('agentDlg.thirdParty')" value="THIRD_PARTY" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('agentDlg.dailyLimit')">
          <el-input-number v-model="keyForm.dailyLimit" :min="0" />
          <span class="hint ml8">{{ t('agentDlg.unlimited') }}</span>
        </el-form-item>
        <el-form-item :label="t('agentDlg.minuteLimit')">
          <el-input-number v-model="keyForm.minuteLimit" :min="0" />
          <span class="hint ml8">{{ t('agentDlg.unlimited') }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="keyCreateVisible = false">{{ t('agentDlg.cancel') }}</el-button>
        <el-button type="primary" :loading="keySaving" @click="createKey">{{ t('agentDlg.create') }}</el-button>
      </template>
    </el-dialog>

    <!-- AI 提示词优化 -->
    <el-dialog v-model="optimizeVisible" :title="t('agentDlg.optimizeTitle')" width="760px">
      <div class="hint mb8">{{ t('agentDlg.optimizeHint') }}</div>
      <el-form label-width="90px">
        <el-form-item :label="t('agentDlg.optDirection')">
          <el-select v-model="optimizeStyle" clearable :placeholder="t('agentDlg.optional')" style="width: 200px">
            <el-option :label="t('agentDlg.structured')" value="结构化、分点清晰" />
            <el-option :label="t('agentDlg.concise')" value="精简冗余，保持信息完整" />
            <el-option :label="t('agentDlg.professional')" value="更专业严谨，适合企业场景" />
            <el-option :label="t('agentDlg.supportTone')" value="礼貌、先结论后步骤的客服语气" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('agentDlg.extraReq')">
          <el-input v-model="optimizeInstruction" type="textarea" :rows="2"
                    :placeholder="t('agentDlg.extraPh')" />
        </el-form-item>
      </el-form>
      <div class="opt-origin">
        <div class="opt-label">{{ t('agentDlg.currentPrompt') }}{{ (form.systemPrompt || '').length }}{{ t('agentDlg.chars') }}</div>
        <div class="opt-origin-text">{{ form.systemPrompt || t('agentDlg.empty') }}</div>
      </div>
      <div class="mt8">
        <el-button type="primary" :loading="optimizing" @click="runOptimize">{{ t('agentDlg.startOptimize') }}</el-button>
        <span v-if="optimizeUsage" class="hint">
          {{ optimizeModel }} · {{ t('agentDlg.inputTokens') }} {{ optimizeUsage.inputTokens }} / {{ t('agentDlg.outputTokens') }} {{ optimizeUsage.outputTokens }} tokens
        </span>
      </div>
      <div v-if="optimizeResult" class="opt-result">
        <div class="opt-label">{{ t('agentDlg.optResult') }}{{ optimizeResult.length }}{{ t('agentDlg.chars') }}</div>
        <el-input v-model="optimizeResult" type="textarea" :rows="10" />
        <div class="mt8">
          <el-button type="primary" @click="applyOptimized('replace')">{{ t('agentDlg.replacePrompt') }}</el-button>
          <el-button @click="applyOptimized('append')">{{ t('agentDlg.append') }}</el-button>
          <el-button @click="optimizeResult = ''">{{ t('agentDlg.clear') }}</el-button>
        </div>
      </div>
    </el-dialog>

    <!-- Prompt 模板插入 -->
    <el-dialog v-model="templateVisible" :title="t('agentDlg.insertTitle')" width="640px">
      <el-select v-model="templateId" filterable style="width: 100%" :placeholder="t('agentDlg.selectTemplate')">
        <el-option v-for="t in promptTemplates" :key="t.id" :value="t.id"
                   :label="t.name + (t.category ? '（' + t.category + '）' : '')" />
      </el-select>
      <div v-if="selectedTemplate" class="tpl-preview">
        <div class="tpl-vars">
          {{ t('agentDlg.variables') }}
          <el-tag v-for="v in selectedTemplate.variables || []" :key="v.key" size="small" class="ml8">{{ v.key }}</el-tag>
          <span v-if="!(selectedTemplate.variables || []).length" class="hint">{{ t('agentDlg.none') }}</span>
        </div>
        <pre class="tpl-content">{{ selectedTemplate.content }}</pre>
      </div>
      <template #footer>
        <el-button @click="templateVisible = false">{{ t('wf.cancel') }}</el-button>
        <el-button type="primary" :disabled="!selectedTemplate" @click="applyTemplate">{{ t('wf.add') }}</el-button>
      </template>
    </el-dialog>

    <!-- 能力选择弹窗 -->
    <el-dialog v-model="picker.visible" :title="pickerTitle" width="520px">
      <el-select v-model="picker.selected" multiple filterable style="width: 100%"
                 :placeholder="pickerPlaceholder">
        <el-option v-for="o in pickerOptions" :key="o.id" :value="o.id"
                   :label="o.name + (o.isBuiltin ? t('wf.builtinSuffix') : '')" />
      </el-select>
      <div v-if="picker.type !== 'sub'" class="picker-create">
        <span class="hint">{{ t('wf.noSuitable') }}</span>
        <el-link v-if="picker.type === 'skill'" type="primary" :underline="false"
                 @click="goCreate('skill')">{{ t('wf.goSkill') }}</el-link>
        <template v-else>
          <el-link type="primary" :underline="false" @click="goCreate('tool')">{{ t('wf.goTool') }}</el-link>
          <el-link type="primary" :underline="false" @click="goCreate('mcp')">{{ t('wf.goMcp') }}</el-link>
        </template>
      </div>
      <el-alert v-if="pickerOptions.length === 0" type="info" :closable="false" show-icon class="mt8"
                :title="picker.type === 'sub' ? t('wf.noSubOptions') : t('wf.noOptions')" />
      <div class="hint mt8">{{ t('wf.onlyNew') }}</div>
      <template #footer>
        <el-button @click="picker.visible = false">{{ t('wf.cancel') }}</el-button>
        <el-button type="primary" @click="confirmPicker">{{ t('wf.add') }}</el-button>
      </template>
    </el-dialog>

    <!-- 工作流编排 -->
    <el-dialog v-model="workflowVisible" :title="t('wf.title')" width="900px" top="4vh" class="wf-dialog">
      <div class="wf-head">
        <el-input v-model="workflowForm.name" :placeholder="t('wf.namePh')" style="width: 220px" />
        <el-select v-model="workflowForm.status" style="width: 110px">
          <el-option :label="t('wf.enabled')" value="ENABLED" />
          <el-option :label="t('wf.disabled')" value="DISABLED" />
        </el-select>
        <el-tag v-if="workflowPublishedVersion" type="success" size="small">{{ t('wf.published') }}{{ workflowPublishedVersion }}</el-tag>
        <el-tag v-else type="info" size="small">{{ t('wf.unpublished') }}</el-tag>
        <el-tag v-if="workflowHasUnpublishedChanges" type="warning" size="small">{{ t('wf.dirty') }}</el-tag>
        <el-select v-model="wfAddType" style="width: 130px">
          <el-option v-for="t in wfTypes" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
        <el-button @click="addWorkflowNode">{{ t('wf.addNode') }}</el-button>
        <el-button @click="rewireWorkflow">{{ t('wf.rewire') }}</el-button>
        <el-button @click="openWorkflowTemplates">{{ t('wf.templates') }}</el-button>
        <el-button @click="openWorkflowVersions">{{ t('agentEdit.versions') }}</el-button>
        <el-button type="success" :loading="workflowPublishing" @click="publishWorkflow">{{ t('agentEdit.publish') }}</el-button>
        <el-button type="primary" :loading="workflowSaving" @click="saveWorkflow()">{{ t('wf.saveDraft') }}</el-button>
      </div>

      <el-radio-group v-model="wfMode" size="small" class="wf-mode">
        <el-radio-button value="list">{{ t('wf.listMode') }}</el-radio-button>
        <el-radio-button value="canvas">{{ t('wf.canvasMode') }}</el-radio-button>
      </el-radio-group>

      <div v-show="wfMode === 'list'" class="wf-flow">
        <div v-for="(n, idx) in workflowForm.nodes" :key="n.id" class="wf-node"
             :class="{ selected: wfSelected === n.id }">
          <div class="wf-node-head">
            <el-tag size="small" :type="wfTagType(n.type)">{{ wfTypeText(n.type) }}</el-tag>
            <el-input v-model="n.name" size="small" style="width: 200px" />
            <span class="hint">#{{ n.id }}</span>
            <el-button text :disabled="idx === 0" @click="moveWorkflowNode(idx, -1)">↑</el-button>
            <el-button text :disabled="idx === workflowForm.nodes.length - 1"
                       @click="moveWorkflowNode(idx, 1)">↓</el-button>
            <el-button v-if="!['START', 'END'].includes(n.type)" text type="danger"
                       @click="removeWorkflowNode(idx)">{{ t('wf.delete') }}</el-button>
          </div>
          <WorkflowNodeForm :node="n" :nodes="workflowForm.nodes" :agents="agentCatalog"
                              :tools="toolCatalog" :kbs="kbRows" />
          <div v-if="n.type !== 'END' && n.type !== 'PARALLEL'" class="wf-node-next">
            <template v-if="n.type === 'CONDITION'">
              {{ t('wf.trueBranch') }}
              <el-select v-model="n.trueNext" size="small" style="width: 150px">
                <el-option v-for="o in wfTargets(n.id)" :key="o.id" :value="o.id"
                           :label="wfTypeText(o.type) + ' · ' + o.name" />
              </el-select>
              {{ t('wf.falseBranch') }}
              <el-select v-model="n.falseNext" size="small" style="width: 150px">
                <el-option v-for="o in wfTargets(n.id)" :key="o.id" :value="o.id"
                           :label="wfTypeText(o.type) + ' · ' + o.name" />
              </el-select>
            </template>
            <template v-else>
              {{ t('wf.nextStep') }}
              <el-select v-model="n.next" size="small" style="width: 180px">
                <el-option v-for="o in wfTargets(n.id)" :key="o.id" :value="o.id"
                           :label="wfTypeText(o.type) + ' · ' + o.name" />
              </el-select>
            </template>
          </div>
        </div>
      </div>

      <div v-show="wfMode === 'canvas'">
        <div class="wf-canvas-tools">
          <el-button size="small" :disabled="wfHistoryIndex <= 0" @click="undoWorkflow">↶ {{ t('wf.undo') }}</el-button>
          <el-button size="small" :disabled="wfHistoryIndex >= wfHistory.length - 1" @click="redoWorkflow">↷ {{ t('wf.redo') }}</el-button>
          <span class="wf-tool-sep" />
          <el-button size="small" @click="zoomCanvas(-0.1)">－</el-button>
          <span class="hint">{{ Math.round(wfZoom * 100) }}%</span>
          <el-button size="small" @click="zoomCanvas(0.1)">＋</el-button>
          <el-button size="small" @click="wfZoom = 1">100%</el-button>
          <el-button size="small" @click="fitCanvas">{{ t('wf.fit') }}</el-button>
          <el-button size="small" @click="autoLayoutWorkflow">{{ t('wf.autoLayout') }}</el-button>
          <el-button size="small" :type="wfLinkMode ? 'warning' : 'default'" @click="toggleLinkMode">
            {{ wfLinkMode ? t('wf.exitLink') : t('wf.linkMode') }}
          </el-button>
          <el-button v-if="wfSelectedEdge !== null" size="small"
                     @click="startRewireEdge">{{ t('wf.rewireEdge') }}</el-button>
          <el-button v-if="wfSelectedEdge !== null" size="small" type="danger" plain
                     @click="deleteSelectedEdge">{{ t('wf.deleteEdgeSelected') }}</el-button>
          <span v-if="wfRewireMode" class="hint">{{ t('wf.rewireHint') }}</span>
          <el-button v-if="wfMultiSelected.length > 1" size="small" type="danger" plain
                     @click="removeMultiSelected">{{ t('wf.deleteSelected') }} ({{ wfMultiSelected.length }})</el-button>
          <el-button size="small" @click="copySelectedNodes">{{ t('wf.copyNodes') }}</el-button>
          <el-button size="small" :disabled="wfClipboardSize === 0" @click="pasteNodes">
            {{ t('wf.pasteNodes') }}<template v-if="wfClipboardSize"> ({{ wfClipboardSize }})</template>
          </el-button>
        </div>
        <div class="wf-canvas-layout">
          <div class="wf-palette">
            <div class="wf-palette-title">{{ t('wf.palette') }}</div>
            <div v-for="tp in wfTypes" :key="tp.value" class="wf-palette-item" draggable="true"
                 :title="t('wf.paletteHint')"
                 @dragstart="onPaletteDragStart($event, tp.value)"
                 @click="addWorkflowNodeOfType(tp.value)">
              <el-tag size="small" :type="wfTagType(tp.value)" effect="plain">{{ tp.label }}</el-tag>
            </div>
            <div class="hint">{{ t('wf.paletteHint') }}</div>
          </div>
          <div ref="wfCanvasEl" class="wf-canvas-wrap" :class="{ 'drop-active': wfDropActive }"
               data-testid="wf-canvas"
               @mousedown="startCanvasPan"
               @dragover.prevent="wfDropActive = true"
               @dragleave="wfDropActive = false"
               @drop.prevent="onCanvasDrop">
          <div class="wf-canvas"
               :style="{ width: wfCanvasSize.w + 'px', height: wfCanvasSize.h + 'px', transform: `scale(${wfZoom})`, transformOrigin: '0 0' }">
            <svg class="wf-edges" :width="wfCanvasSize.w" :height="wfCanvasSize.h">
              <defs>
                <marker id="wf-arrow" markerWidth="8" markerHeight="8" refX="7" refY="3"
                        orient="auto" markerUnits="strokeWidth">
                  <path d="M0,0 L7,3 L0,6 z" fill="#909399" />
                </marker>
              </defs>
              <path v-for="e in wfEdges" :key="e.id" :d="e.d" fill="none"
                    :class="{ 'wf-edge-selected': e.explicit && wfSelectedEdge === e.index }"
                    :stroke="e.color" stroke-width="1.6" marker-end="url(#wf-arrow)"
                    @click.stop="selectEdge(e)" />
              <text v-for="e in wfEdges" v-show="e.label" :key="'l' + e.id"
                    :x="e.labelX" :y="e.labelY" class="wf-edge-label">{{ e.label }}</text>
              <path v-if="wfLinkDrag" :d="wfLinkPath" class="wf-edge-temp" />
            </svg>
            <div v-for="n in workflowForm.nodes" :key="'c' + n.id"
                 class="wf-canvas-node"
                 :class="[{ selected: wfSelected === n.id, multi: wfMultiSelected.includes(n.id),
                            'replay-current': wfReplayCurrent?.nodeId === n.id }, wfRunClass(n)]"
                 :data-node-id="n.id"
                 :style="{ left: nodeX(n) + 'px', top: nodeY(n) + 'px' }"
                 @mousedown.stop="startNodeDrag($event, n)"
                 @click="onCanvasNodeClick(n, $event)">
              <el-tag size="small" :type="wfTagType(n.type)">{{ wfTypeText(n.type) }}</el-tag>
              <div class="wf-cn-name">{{ n.name }}</div>
              <div class="wf-cn-id">#{{ n.id }}</div>
              <span v-if="n.type !== 'END' && n.type !== 'CONDITION'" class="wf-port" :title="t('wf.dragPortHint')"
                    @mousedown.stop="startLinkDrag($event, n)" @click.stop />
              <template v-if="n.type === 'CONDITION'">
                <span class="wf-port wf-port-true" :title="t('wf.trueBranch')"
                      @mousedown.stop="startLinkDrag($event, n, 'true')">T</span>
                <span class="wf-port wf-port-false" :title="t('wf.falseBranch')"
                      @mousedown.stop="startLinkDrag($event, n, 'false')">F</span>
              </template>
              <span v-if="n.type !== 'START'" class="wf-port wf-port-in" />
            </div>
            <div v-if="wfBoxSelect" class="wf-select-box" :style="wfBoxStyle" />
          </div>
          <div class="hint mt8">{{ t('wf.canvasHint') }}</div>
          </div>
          <div v-if="wfNodePanel && wfSelectedNode" class="wf-node-panel" data-testid="wf-node-panel">
            <div class="wf-node-panel-head">
              <el-tag size="small" :type="wfTagType(wfSelectedNode.type)">{{ wfTypeText(wfSelectedNode.type) }}</el-tag>
              <span class="wf-cn-id">#{{ wfSelectedNode.id }}</span>
              <el-button text size="small" @click="openNodeInList">{{ t('wf.listEdit') }}</el-button>
              <el-button text size="small" type="danger" @click="removeSelectedNode">{{ t('wf.delete') }}</el-button>
              <el-button text size="small" @click="closeNodePanel">×</el-button>
            </div>
            <WorkflowNodeForm :node="wfSelectedNode" :nodes="workflowForm.nodes" :agents="agentCatalog"
                              :tools="toolCatalog" :kbs="kbRows" />
          </div>
        </div>
      </div>

      <el-collapse class="mt8">
        <el-collapse-item :title="t('wf.graphEdges')" name="graph-edges">
          <div v-for="(e, i) in workflowForm.edges" :key="'edge' + i" class="wf-line">
            <el-select v-model="e.from" filterable :placeholder="t('wf.from')" style="width: 190px">
              <el-option v-for="n in workflowForm.nodes.filter((x: any) => x.type !== 'END')"
                         :key="n.id" :value="n.id" :label="wfTypeText(n.type) + ' · ' + n.name" />
            </el-select>
            <span>→</span>
            <el-select v-model="e.to" filterable :placeholder="t('wf.to')" style="width: 190px">
              <el-option v-for="n in workflowForm.nodes" :key="n.id" :value="n.id"
                         :label="wfTypeText(n.type) + ' · ' + n.name" />
            </el-select>
            <el-select v-if="workflowNodeType(e.from) === 'CONDITION'" v-model="e.label" style="width: 100px">
              <el-option label="true" value="true" />
              <el-option label="false" value="false" />
            </el-select>
            <el-button text type="danger" @click="removeGraphEdge(i)">{{ t('wf.delete') }}</el-button>
          </div>
          <el-button size="small" @click="addGraphEdge">{{ t('wf.addEdge') }}</el-button>
          <div class="hint">{{ t('wf.graphHint') }}</div>
        </el-collapse-item>
      </el-collapse>

      <el-divider content-position="left">{{ t('wf.runTest') }}</el-divider>
      <el-input v-model="workflowRunInput" type="textarea" :rows="2" :placeholder="t('wf.runInputPh')"
                data-testid="wf-run-input" />
      <div class="hint mt8">{{ t('wf.runHint') }}</div>
      <div class="mt8">
        <el-button type="primary" :loading="workflowRunning" @click="runWorkflow"
                   data-testid="wf-run-button">{{ t('wf.run') }}</el-button>
        <el-link v-if="workflowRunResult?.status === 'WAITING_APPROVAL'" type="warning"
                 @click="goApprovals">{{ t('wf.waitingApproval') }}</el-link>
      </div>
      <div v-if="workflowRunResult" class="wf-run" data-testid="wf-run-result">
        <div>
          {{ t('wf.status') }}{{ workflowRunResult.status }}{{ t('wf.currentNode') }}{{ workflowRunResult.currentNode || '-' }}
          · {{ t('wf.tokenLabel') }}{{ workflowRunResult.inputTokens || 0 }}/{{ workflowRunResult.outputTokens || 0 }}
        </div>
        <pre>{{ workflowRunResult.output || workflowRunResult.error || '' }}</pre>
        <div v-if="workflowRunResult.nodeRuns?.length" class="hint mt8">
          {{ t('wf.nodes') }}
          {{ workflowRunResult.nodeRuns.map((x: any) => `${x.name || x.nodeId}(${x.status})`).join(' → ') }}
        </div>
        <div v-if="workflowRunResult.checkpoints?.length" class="hint mt8">
          {{ t('wf.checkpoints') }}
          <div v-for="cp in workflowRunResult.checkpoints.slice(0, 8)" :key="cp.id">
            {{ cp.nodeId || '-' }} → {{ cp.nextNodeId || '-' }} · {{ cp.status }} · {{ cp.createdAt }}
          </div>
        </div>
        <div v-if="workflowRunResult.status === 'FAILED'" class="mt8">
          <el-button size="small" type="warning" :loading="workflowResuming" @click="resumeWorkflow">
            {{ t('wf.resume') }}
          </el-button>
        </div>
        <div v-if="workflowRunResult.nodeRuns?.length" class="wf-replay">
          <div class="wf-replay-bar">
            <el-button size="small" type="primary" plain @click="toggleReplay">
              {{ wfReplayPlaying ? t('wf.replayPause') : t('wf.replayPlay') }}
            </el-button>
            <el-button size="small" :disabled="wfReplayIndex <= 0" @click="stepReplay(-1)">{{ t('wf.replayPrev') }}</el-button>
            <el-button size="small" :disabled="wfReplayIndex >= wfReplaySteps.length - 1" @click="stepReplay(1)">{{ t('wf.replayNext') }}</el-button>
            <el-button v-if="wfReplayIndex >= 0" size="small" @click="stopReplay">{{ t('wf.replayStop') }}</el-button>
            <span class="hint">{{ t('wf.replayStep') }} {{ wfReplayIndex + 1 }} / {{ wfReplaySteps.length }}</span>
          </div>
          <div v-if="wfReplayCurrent" class="wf-replay-info">
            <el-tag size="small" :type="wfReplayCurrent.status === 'SUCCESS' ? 'success' : wfReplayCurrent.status === 'FAILED' ? 'danger' : 'info'">
              {{ wfReplayCurrent.status }}
            </el-tag>
            <b>{{ wfReplayCurrent.name || wfReplayCurrent.nodeId }}</b>
            <span v-if="replayDuration(wfReplayCurrent)" class="hint">{{ replayDuration(wfReplayCurrent) }}ms</span>
            <span v-if="wfReplayCurrent.errorMessage" class="hint">{{ wfReplayCurrent.errorMessage }}</span>
            <div v-if="wfReplayCurrent.outputText" class="wf-replay-output">{{ wfReplayCurrent.outputText }}</div>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="workflowVisible = false">{{ t('wf.close') }}</el-button>
        <el-button type="primary" :loading="workflowSaving" @click="saveWorkflow()">{{ t('wf.saveWorkflow') }}</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="wfVersionsVisible" :title="t('wf.versionTitle')" size="480px">
      <el-table :data="wfVersions" size="small" :empty-text="t('wf.noVersions')">
        <el-table-column prop="version" :label="t('wf.versionCol')" width="80" />
        <el-table-column prop="name" :label="t('wf.name')" min-width="140" />
        <el-table-column :label="t('wf.publishedAt')" width="170">
          <template #default="{ row }">{{ String(row.createdAt || '').replace('T',' ').slice(0,19) }}</template>
        </el-table-column>
        <el-table-column :label="t('wf.actions')" width="140">
          <template #default="{ row }">
            <el-button size="small" text @click="compareVersion(row)">{{ t('wf.compare') }}</el-button>
            <el-button size="small" text type="primary" @click="rollbackWorkflow(row.version)">{{ t('wf.rollback') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-dialog v-model="wfTemplatesVisible" :title="t('wf.templateTitle')" width="680px">
      <el-table :data="wfTemplates" size="small" @row-click="applyWorkflowTemplate">
        <el-table-column prop="name" :label="t('wf.templateCol')" width="140" />
        <el-table-column prop="description" :label="t('wf.desc')" min-width="320" show-overflow-tooltip />
        <el-table-column :label="t('wf.actions')" width="90">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click.stop="applyWorkflowTemplate(row)">{{ t('wf.use') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="wfDiffVisible" :title="wfDiffTitle" width="680px">
      <el-table :data="wfDiffRows" size="small" :empty-text="t('wf.noDiff')">
        <el-table-column prop="id" :label="t('wf.nodeCol')" width="120" />
        <el-table-column prop="name" :label="t('wf.name')" width="180" show-overflow-tooltip />
        <el-table-column :label="t('wf.change')" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="diffTag(row.change)">{{ row.change }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="detail" :label="t('wf.diffField')" min-width="220" show-overflow-tooltip />
      </el-table>
      <template #footer>
        <el-button @click="wfDiffVisible = false">{{ t('wf.close') }}</el-button>
      </template>
    </el-dialog>

    <!-- 版本抽屉 -->
    <el-drawer v-model="versionsVisible" :title="t('wf.versionHistory')" size="420px">
      <el-table :data="versions" size="small">
        <el-table-column prop="version" :label="t('wf.versionCol')" width="80" />
        <el-table-column prop="createdAt" :label="t('wf.time')" />
        <el-table-column :label="t('wf.actions')" width="90">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="rollback(row.version)">{{ t('wf.rollback') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- 发布信息 / 外部验证 -->
    <el-dialog v-model="publishInfoVisible" :title="t('deploy.publishInfoTitle')" width="760px">
      <div v-if="publishInfoLoading" class="hint">{{ t('deploy.publishInfoLoading') }}</div>
      <template v-else>
        <el-alert v-if="!deployment" type="info" :closable="false" :title="t('deploy.notDeployed')" class="mb8" />
        <el-descriptions v-if="deployment" :column="1" border size="small">
          <el-descriptions-item :label="t('deploy.status')">
            <el-tag :type="deployTagType" size="small">{{ deployStatusText }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('deploy.endpoint')">
            <code class="deploy-code">{{ deployment.endpoint }}</code>
            <el-button size="small" text type="primary" @click="copyText(deployment.endpoint!)">{{ t('deploy.copy') }}</el-button>
            <div v-if="isClusterEndpoint" class="hint">{{ t('deploy.endpointClusterOnly') }}</div>
          </el-descriptions-item>
          <el-descriptions-item v-if="deployment.localEndpoint" :label="t('deploy.localEndpoint')">
            <code class="deploy-code">{{ deployment.localEndpoint }}</code>
          </el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">{{ t('deploy.testPageTitle') }}</el-divider>
        <div class="hint mb8">{{ t('deploy.testPageHint') }}</div>
        <el-alert v-if="testWidgetUrl" type="warning" :closable="false" :title="t('deploy.testKeyWarning')" class="mb8" />
        <template v-if="testWidgetUrl">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item :label="t('deploy.testLink')">
              <el-input :model-value="testWidgetUrl" readonly @focus="selectAllText">
                <template #append>
                  <el-button @click="copyText(testWidgetUrl)">{{ t('deploy.copy') }}</el-button>
                </template>
              </el-input>
              <el-button size="small" class="mt8" @click="openManualCopy(testWidgetUrl)">
                {{ t('deploy.manualSelect') }}
              </el-button>
              <div class="hint mt4">{{ t('deploy.copyFallbackHint') }}</div>
            </el-descriptions-item>
            <el-descriptions-item :label="t('deploy.testIframe')">
              <el-input :model-value="testWidgetIframe" type="textarea" :rows="2" readonly />
              <el-button size="small" class="mt8" @click="copyText(testWidgetIframe)">{{ t('deploy.copyIframe') }}</el-button>
            </el-descriptions-item>
          </el-descriptions>
          <div class="deploy-actions">
            <el-button type="primary" @click="openTestPage">{{ t('deploy.openTestPage') }}</el-button>
            <el-button :loading="testLinkCreating" @click="createTestLink">{{ t('deploy.regenerateTestLink') }}</el-button>
          </div>
        </template>
        <el-button v-else type="primary" :loading="testLinkCreating" @click="createTestLink">
          {{ t('deploy.createTestLink') }}
        </el-button>
      </template>
      <template #footer>
        <el-button @click="publishInfoVisible = false">{{ t('deploy.close') }}</el-button>
      </template>
    </el-dialog>

    <!-- 复制失败兜底：自动选中文本，用户可 Ctrl/Cmd+C，避免只能看到“复制失败”提示 -->
    <el-dialog v-model="manualCopyVisible" :title="t('agentDlg.manualCopyTitle')" width="560px"
               class="manual-copy-dialog" append-to-body>
      <div class="hint mb8">{{ t('agentDlg.manualCopyHint') }}</div>
      <el-input ref="manualCopyInput" v-model="manualCopyText" type="textarea" :rows="4" readonly />
      <template #footer>
        <el-button type="primary" @click="manualCopyVisible = false">{{ t('deploy.close') }}</el-button>
      </template>
    </el-dialog>

    <!-- 容器部署 -->
    <el-dialog v-model="deployVisible" :title="t('deploy.title')" width="760px">
      <el-alert v-if="form.status !== 'PUBLISHED'" type="warning" :closable="false"
                :title="t('deploy.publishedOnly')" class="mb8" />
      <el-alert v-if="deployInfo.enabled === false" type="info" :closable="false"
                :title="t('deploy.frozen')" class="mb8" />
      <el-alert v-if="!deployInfo.dockerAvailable" type="error" :closable="false"
                :title="t('deploy.dockerMissing')" class="mb8" />
      <div class="deploy-replicas">
        <span>{{ t('deploy.resourceProfile') }}</span>
        <el-select v-model="deployProfileId" size="small" style="width: 250px"
                   :placeholder="t('deploy.resourceProfilePh')">
          <el-option v-for="p in deployProfiles" :key="p.id" :value="p.id"
                     :label="`${p.name} · ${p.cpuLimit}C / ${p.memoryLimit} · ${p.defaultReplicas}-${p.maxReplicas} 副本`" />
        </el-select>
        <span class="hint">{{ t('deploy.resourceProfileHint') }}</span>
      </div>
      <template v-if="deployment">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item :label="t('deploy.status')">
            <el-tag :type="deployTagType" size="small">{{ deployStatusText }}</el-tag>
            <span class="hint">{{ t('deploy.versionPrefix') }}{{ deployment.version || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="deployment.lbEndpoint ? t('deploy.lbEndpoint') : t('deploy.endpoint')">
            <code class="deploy-code">{{ deployment.endpoint }}</code>
            <el-button size="small" text type="primary" @click="copyText(deployment.endpoint!)">
              {{ t('deploy.copy') }}
            </el-button>
            <div v-if="deployment.lbEndpoint" class="hint">{{ t('deploy.lbHint', { count: deployment.replicaCount }) }}</div>
          </el-descriptions-item>
          <el-descriptions-item :label="t('deploy.localEndpoint')">
            <code class="deploy-code">{{ deployment.localEndpoint }}</code>
          </el-descriptions-item>
          <el-descriptions-item :label="t('deploy.token')">
            <template v-if="runtimeToken">
              <code class="deploy-code">{{ runtimeToken }}</code>
              <el-button size="small" text type="primary" @click="copyText(runtimeToken)">{{ t('deploy.copy') }}</el-button>
            </template>
            <span v-else class="hint">{{ t('deploy.tokenMissing') }}</span>
            <el-button size="small" text type="warning" @click="rotateDeployToken">{{ t('deploy.rotateToken') }}</el-button>
          </el-descriptions-item>
          <el-descriptions-item :label="t('deploy.container')">
            {{ deployment.containerName }}
            <span class="hint">{{ deployment.containerId }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('deploy.image')">{{ deployment.image }}</el-descriptions-item>
          <el-descriptions-item :label="t('deploy.updatedAt')">{{ deployment.updatedAt }}</el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="deployment.lastError && deployment.status !== 'RUNNING'" type="error" :closable="false"
                  :title="deployment.lastError" class="mt8" />
        <div class="deploy-replicas">
          <span>{{ t('deploy.replicas') }}</span>
          <el-input-number v-model="deployReplicas" :min="1" :max="deployMaxReplicas" size="small" />
          <span class="hint">{{ t('deploy.replicasHint') }}</span>
        </div>
        <el-table v-if="(deployment.replicas || []).length > 1" :data="deployment.replicas" size="small" class="mt8">
          <el-table-column prop="name" :label="t('deploy.replicaContainer')" min-width="150" />
          <el-table-column prop="hostPort" :label="t('deploy.replicaPort')" width="80" />
          <el-table-column :label="t('deploy.keyStatus')" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'RUNNING' ? 'success' : row.status === 'FAILED' ? 'danger' : 'info'">
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="endpoint" :label="t('deploy.endpoint')" show-overflow-tooltip />
        </el-table>
        <div class="mt8"><b>{{ t('deploy.curl') }}</b></div>
        <pre class="deploy-curl">{{ deployCurl }}</pre>
        <div class="hint mt8">{{ t('deploy.keyHint') }}</div>
        <div class="deploy-actions">
          <el-button @click="refreshDeploy">{{ t('deploy.refresh') }}</el-button>
          <el-button v-if="deployment.status !== 'RUNNING'" @click="deployAction('start')">{{ t('deploy.start') }}</el-button>
          <el-button v-else @click="deployAction('stop')">{{ t('deploy.stop') }}</el-button>
          <el-button @click="openDeployLogs">{{ t('deploy.logs') }}</el-button>
          <el-button @click="openDeployHistory">{{ t('deploy.history') }}</el-button>
          <el-button type="primary" :loading="deploying"
                     :disabled="deployInfo.enabled === false || !deployInfo.dockerAvailable" @click="doDeploy">{{ t('deploy.redeploy') }}</el-button>
          <el-button type="danger" plain @click="removeDeploy">{{ t('deploy.remove') }}</el-button>
        </div>

        <el-divider content-position="left">{{ t('deploy.keysTitle') }}</el-divider>
        <div class="deploy-keys-head">
          <span class="hint">{{ t('deploy.keysHint') }}</span>
          <div>
            <el-button size="small" @click="loadDeployStats">{{ t('deploy.refreshUsage') }}</el-button>
            <el-button size="small" type="primary" plain @click="openKeyDialog()">{{ t('deploy.addKey') }}</el-button>
          </div>
        </div>
        <el-table v-if="runtimeKeys.length" :data="runtimeKeys" size="small">
          <el-table-column prop="name" :label="t('deploy.keyName')" min-width="110" />
          <el-table-column :label="t('deploy.keyPrefix')" width="120">
            <template #default="{ row }"><code>{{ row.keyPrefix }}…</code></template>
          </el-table-column>
          <el-table-column :label="t('deploy.keyQuota')" width="85">
            <template #default="{ row }">{{ row.dailyQuota ? row.dailyQuota : t('deploy.unlimited') }}</template>
          </el-table-column>
          <el-table-column :label="t('deploy.keyUsage')" width="85">
            <template #default="{ row }">{{ keyUsage(row) }}</template>
          </el-table-column>
          <el-table-column :label="t('deploy.keyStatus')" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('deploy.actionCol')" width="180">
            <template #default="{ row }">
              <el-button text size="small" @click="openKeyDialog(row)">{{ t('deploy.edit') }}</el-button>
              <el-button text size="small" @click="toggleRuntimeKey(row)">
                {{ row.status === 'ENABLED' ? t('deploy.disable') : t('deploy.enable') }}
              </el-button>
              <el-button text size="small" type="danger" @click="removeRuntimeKey(row)">{{ t('deploy.delete') }}</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-else class="hint">{{ t('deploy.noKeys') }}</div>
        <div class="hint mt8">{{ t('deploy.mainTokenUsage') }}{{ mainTokenUsage }}</div>
      </template>
      <el-empty v-else :image-size="60" :description="t('deploy.empty')">
        <el-button type="primary" :loading="deploying" :disabled="!deployInfo.dockerAvailable"
                   @click="doDeploy">{{ t('deploy.deployNow') }}</el-button>
        <el-button @click="openDeployHistory">{{ t('deploy.history') }}</el-button>
      </el-empty>
    </el-dialog>

    <el-dialog v-model="deployLogsVisible" append-to-body :title="t('deploy.logs')" width="760px">
      <div class="hint mb8">{{ t('deploy.logsHint') }}</div>
      <pre class="deploy-logs">{{ deployLogs || t('deploy.noLogs') }}</pre>
    </el-dialog>

    <el-drawer v-model="deployHistoryVisible" :title="t('deploy.history')" size="780px">
      <el-table v-if="deployHistory.length" :data="deployHistory" size="small">
        <el-table-column prop="createdAt" :label="t('deploy.time')" width="165" />
        <el-table-column :label="t('deploy.action')" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="deployActionTag(row.action)">{{ deployActionText(row.action) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="version" :label="t('deploy.versionCol')" width="70" />
        <el-table-column prop="hostPort" :label="t('deploy.port')" width="65" />
        <el-table-column :label="t('deploy.statusCol')" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'SUCCESS' ? 'success' : 'danger'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="message" :label="t('deploy.message')" show-overflow-tooltip />
        <el-table-column :label="t('deploy.actionCol')" width="120">
          <template #default="{ row }">
            <el-button v-if="row.action === 'DEPLOY' && row.version" size="small" text type="primary"
                       @click="rollbackDeployment(row.version)">{{ t('deploy.rollbackDeploy') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else :description="t('deploy.noHistory')" :image-size="60" />
    </el-drawer>

    <el-dialog v-model="runtimeKeyDialogVisible" append-to-body width="440px"
               :title="runtimeKeyForm.id ? t('deploy.editKey') : t('deploy.addKey')">
      <el-form label-width="100px">
        <el-form-item :label="t('deploy.keyName')">
          <el-input v-model="runtimeKeyForm.name" maxlength="64" :placeholder="t('deploy.keyNamePh')" />
        </el-form-item>
        <el-form-item :label="t('deploy.keyQuota')">
          <el-input-number v-model="runtimeKeyForm.dailyQuota" :min="0" :max="1000000" />
          <span class="hint">{{ t('deploy.quotaHint') }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="runtimeKeyDialogVisible = false">{{ t('agentDlg.cancel') }}</el-button>
        <el-button type="primary" :loading="runtimeKeySaving" @click="saveRuntimeKey">{{ t('agentDlg.save') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="newRuntimeKeyVisible" append-to-body width="520px" :title="t('deploy.keyCreated')">
      <el-alert type="warning" :closable="false" :title="t('deploy.keyOnce')" />
      <el-input :model-value="createdRuntimeKey" readonly class="mt8">
        <template #append>
          <el-button @click="copyText(createdRuntimeKey)">{{ t('deploy.copy') }}</el-button>
        </template>
      </el-input>
      <div class="hint mt8">{{ t('deploy.keyUsageHint') }}</div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElLoading, ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import http from '../api/http'
import { escapeHtml, renderGateFailures } from '../utils/gateDetails'
import WorkflowNodeForm from '../components/WorkflowNodeForm.vue'
import AgentRoutingPanel from '../components/AgentRoutingPanel.vue'
import KbRerankControl from '../components/KbRerankControl.vue'
import { hydrateRoutingForm } from '../utils/agentEditRoutingForm'
import { useAgentEditorActions } from './agentEditorActions'
import { useAgentWorkflowEditor } from './agentWorkflowEditor'


const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const agentId = Number(route.params.id)

interface VariableRow { key: string; type: string; required: boolean; defaultValue: string; description: string }

const activePanels = ref(['base', 'model', 'prompt', 'knowledge', 'skill', 'tool', 'subagent', 'variable'])
const models = ref<any[]>([])
const kbRows = ref<KbRow[]>([])
const resourceProfiles = ref<any[]>([])
const versions = ref<any[]>([])
const members = ref<any[]>([])
const grantedIds = ref<number[]>([])
const ownerId = ref<number>(0)
const versionsVisible = ref(false)
const deployVisible = ref(false)
const deployInfo = reactive({ enabled: true, dockerAvailable: false, dockerVersion: '', image: '' })
const deployment = ref<any>(null)
const publishInfoVisible = ref(false)
const publishInfoLoading = ref(false)
const testLinkCreating = ref(false)
const testWidgetUrl = ref('')
const testWidgetIframe = ref('')
const manualCopyVisible = ref(false)
const manualCopyText = ref('')
const manualCopyInput = ref<any>(null)
const isClusterEndpoint = computed(() => String(deployment.value?.endpoint || '').includes('.svc.cluster.local'))
const deploying = ref(false)
const deployLogsVisible = ref(false)
const deployLogs = ref('')
const deployHistoryVisible = ref(false)
const deployHistory = ref<any[]>([])
const runtimeToken = ref('')
const runtimeKeys = ref<any[]>([])
const runtimeStats = ref<any>({ callers: [] })
const deployReplicas = ref(1)
const deployProfileId = ref<number | null>(null)
const deployProfiles = ref<any[]>([])
const selectedDeployProfile = computed(() =>
  deployProfiles.value.find((p: any) => p.id === deployProfileId.value))
const deployMaxReplicas = computed(() => selectedDeployProfile.value?.maxReplicas || 5)
watch(deployMaxReplicas, (max) => {
  if (deployReplicas.value > max) deployReplicas.value = max
})
const runtimeKeyDialogVisible = ref(false)
const runtimeKeySaving = ref(false)
const runtimeKeyForm = reactive({ id: 0, name: '', dailyQuota: 0 })
const createdRuntimeKey = ref('')
const newRuntimeKeyVisible = ref(false)
const saving = ref(false)

const form = reactive({
  status: 'DRAFT', updatedAt: '',
  name: '', description: '', category: '', visibility: 'TENANT', listed: true,
  modelName: '', fallbackModelName: '', grayModel: '', grayTag: '', temperature: 0.7,
  thinking: false, reasoningEffort: 'medium',
  routingEnabled: false, routingClassifierModel: '', routingSimpleModel: '', routingComplexModel: '',
  routingRules: [] as Array<{ keywords: string; model: string }>,
  systemPrompt: '', openingStatement: '',
  capabilities: { streamOutput: true, functionCall: false, maxToolCalls: 0, redactSecrets: true,
    maxInputValidationAttempts: 3, maxToolOutputChars: 0, multimodal: false, selfCorrection: false,
    selfCorrectionRounds: 1, writeToolPolicy: 'ALLOW_ALL', allowedWriteToolIds: [] as number[], writeToolMaxArgs: 16384 },
  memory: { enabled: true, windowSize: 10, autoExtract: true },
  variables: [] as VariableRow[],
  resourceProfileId: null as number | null
})
const tagsStr = ref('')
const writeToolPatternsText = ref('')
const redactPatternsText = ref('[]')
const writeToolAmountLimitsText = ref('')
const enabledKbCount = computed(() => kbRows.value.filter(k => k.enabled).length)
const selectedProfile = computed(() => resourceProfiles.value.find((p: any) => p.id === form.resourceProfileId))
function kbCompatible(kb: KbRow) {
  const p = selectedProfile.value
  if (!p) return true
  return (kb.searchBackend || 'MYSQL') === p.searchBackend
    && (kb.vectorBackend || 'PGVECTOR') === p.vectorBackend
}

/** 模型能力（capabilities 可能是 JSON 字符串）：决定是否允许开启思考、以及可选思考级别 */
function modelCaps(m: any): Record<string, any> {
  const caps = m?.capabilities
  if (!caps) return {}
  if (typeof caps === 'string') {
    try { return JSON.parse(caps) } catch { return {} }
  }
  return caps
}
const selectedModel = computed(() => models.value.find((m: any) => m.modelName === form.modelName))
const supportsThinking = computed(() => modelCaps(selectedModel.value).reasoning === true)
const thinkingActive = computed(() => supportsThinking.value && form.thinking)
const reasoningLevels = computed<string[]>(() => {
  const levels = modelCaps(selectedModel.value).reasoningLevels
  return Array.isArray(levels) && levels.length
    ? levels.map((x: any) => String(x))
    : ['low', 'medium', 'high']
})
// 换模型或关闭思考时，把非法/不适用的级别收敛到模型支持的范围内
watch([() => form.modelName, () => form.thinking], () => {
  if (!supportsThinking.value) {
    form.thinking = false
    return
  }
  if (!reasoningLevels.value.includes(form.reasoningEffort)) {
    form.reasoningEffort = reasoningLevels.value.includes('medium') ? 'medium' : reasoningLevels.value[0]
  }
})

const sectionOptions = ['abstract', 'introduction', 'methods', 'results', 'discussion', 'conclusion', 'references', 'body']
interface KbRow {
  knowledgeBaseId: number; name: string; enabled: boolean; topK: number; scoreThreshold: number; mode: string
  rerank: boolean; rerankTopN?: number
  documentType?: string; sectionType?: string; regulatoryCategory?: string
  chunkLevel?: number; minRelevance?: number; defaultTopK?: number; maxTopK?: number
  searchBackend?: string; vectorBackend?: string; missing?: boolean
}
interface RefRow { id: number; name: string; description?: string; type?: string; isBuiltin?: boolean; enabled: boolean; missing?: boolean }
type RefType = 'skill' | 'tool' | 'sub'


const editorCtx: any = { agentId, activePanels, models, kbRows, resourceProfiles, versions, members, grantedIds, ownerId, versionsVisible, deployVisible, deployInfo, deployment, publishInfoVisible, publishInfoLoading, testLinkCreating, testWidgetUrl, testWidgetIframe, manualCopyVisible, manualCopyText, manualCopyInput, isClusterEndpoint, deploying, deployLogsVisible, deployLogs, deployHistoryVisible, deployHistory, runtimeToken, runtimeKeys, runtimeStats, deployReplicas, deployProfileId, deployProfiles, selectedDeployProfile, deployMaxReplicas, runtimeKeyDialogVisible, runtimeKeySaving, runtimeKeyForm, createdRuntimeKey, newRuntimeKeyVisible, saving, form, tagsStr, writeToolPatternsText, redactPatternsText, writeToolAmountLimitsText, enabledKbCount, selectedProfile, kbCompatible, modelCaps, selectedModel, supportsThinking, thinkingActive, reasoningLevels, sectionOptions }
const { skillRows, toolRows, subRows, skillCatalog, toolCatalog, agentCatalog, picker, credentialVisible, credentials, credentialFormVisible, credentialSaving, credentialForm, scheduleVisible, schedules, scheduleFormVisible, scheduleSaving, scheduleForm, resultsVisible, scheduleResults, resultsTitle, integrationVisible, apiKeys, mcpEndpoint, createdKey, channelsVisible, channelMessagesVisible, channelMessages, messagesChannelName, channels, channelCreateVisible, channelSaving, channelTestingId, createdInbound, channelForm, widgetSnippet, widgetKey, widgetCreating, keyCreateVisible, keySaving, keyForm, optimizeVisible, optimizing, optimizeStyle, optimizeInstruction, optimizeResult, optimizeUsage, optimizeModel, promptTemplates, templateVisible, templateId, selectedTemplate, enabledSkillCount, enabledToolCount, enabledSubCount, pickerTitle, pickerPlaceholder, pickerOptions, debugMsgs, debugInput, debugRunning, dbgBox, buildRefRows, openCredentials, loadCredentials, toolName, openCredentialCreate, openCredentialEdit, saveCredential, removeCredential, openSchedules, loadSchedules, openScheduleCreate, openScheduleEdit, saveSchedule, toggleSchedule, runSchedule, openResults, removeSchedule, openIntegration, openChannels, createChannel, inboundUrl, openChannelMessages, copyInbound, testChannel, toggleChannel, removeChannel, loadApiKeys, openKeyCreate, createKey, createWidgetKey, secureWidgetOrigin, openManualCopy, legacyCopy, selectAllText, copyText, deployTagType, deployStatusText, deployCurl, loadPublishInfo, openPublishInfo, createTestLink, openTestPage, openDeploy, loadDeployProfiles, refreshDeploy, doDeploy, deployAction, openDeployLogs, openDeployHistory, deployActionText, deployActionTag, removeDeploy, rollbackDeployment, mainTokenUsage, keyUsage, loadRuntimeKeys, loadDeployStats, openKeyDialog, saveRuntimeKey, toggleRuntimeKey, removeRuntimeKey, applyRuntimeKeys, rotateDeployToken, copyWidget, previewWidget, copyKey, toggleKey, removeKey, integratedAgentId, modelLabel, openOptimize, runOptimize, applyOptimized, openTemplatePicker, applyTemplate, openPicker, goCreate, confirmPicker, removeRow, parsePolicyJson, parseRedactPatterns, buildPayload, save, publishCheckSummary, autoRedeployAfterPublish, publish, toggleListing, openVersions, rollback, addVariable, debugSend, scrollDbg, evalEntry, fmtTime, clip } = useAgentEditorActions(editorCtx)
const { workflowVisible, workflowConfigured, workflowSaving, workflowRunning, workflowResuming, wfAddType, wfTypes, workflowForm, workflowRunInput, workflowRunResult, workflowPublishedVersion, workflowHasUnpublishedChanges, workflowPublishing, wfVersionsVisible, wfVersions, wfMode, wfSelected, wfLinkMode, wfLinkFrom, wfHistory, wfHistoryIndex, wfSelectedEdge, wfDraggingType, wfDropActive, wfLinkDrag, wfNodePanel, wfRewireMode, wfMultiSelected, wfClipboardSize, wfReplayIndex, wfReplayPlaying, wfReplayTimer, wfBoxSelect, wfClipboard, wfNodeEditBefore, wfSelectedNode, wfBoxStyle, wfCanvasEl, wfDiffVisible, wfDiffTitle, wfDiffRows, wfZoom, wfTemplatesVisible, wfTemplates, applyWorkflowData, openWorkflow, wfTypeText, wfTagType, wfTargets, wfBranchTargets, ensureWorkflowPositions, openNodePanel, closeNodePanel, openNodeInList, removeSelectedNode, snapshotWorkflow, resetWorkflowHistory, commitWorkflowHistory, restoreWorkflow, undoWorkflow, redoWorkflow, onWorkflowKeydown, onPaletteDragStart, onCanvasDrop, startLinkDrag, connectNodes, startRewireEdge, finishRewire, cancelRewire, selectEdge, deleteSelectedEdge, removeGraphEdge, fitCanvas, nodeX, nodeY, wfCanvasSize, wfEdges, wfLinkPath, wfRunClass, wfRunNodeStatus, toggleLinkMode, wfDragMoved, wfDragEndedAt, onCanvasNodeClick, zoomCanvas, autoLayoutWorkflow, editWorkflowNode, startBoxSelect, startCanvasPan, openWorkflowTemplates, applyWorkflowTemplate, copySelectedNodes, pasteNodes, removeMultiSelected, startNodeDrag, compareVersion, diffTag, workflowNodeType, addGraphEdge, buildWorkflowNode, freeWorkflowPosition, avoidWorkflowOverlap, insertWorkflowNode, addWorkflowNode, addWorkflowNodeOfType, removeWorkflowNode, moveWorkflowNode, rewireWorkflow, saveWorkflow, publishWorkflow, openWorkflowVersions, rollbackWorkflow, resumeWorkflow, runWorkflow, wfReplaySteps, wfReplayCurrent, replayDuration, clearReplayTimer, focusReplayNode, startReplay, advanceReplay, toggleReplay, stepReplay, stopReplay, goApprovals } = useAgentWorkflowEditor(editorCtx)

onMounted(async () => {
  const [detail, modelList, kbList, skillsRes, toolsRes, agentRes, templatesRes, profileRes] = await Promise.all([
    http.get(`/agent/${agentId}`) as any,
    http.get('/provider/models') as any,
    http.get('/knowledge-base', { params: { pageSize: 100 } }) as any,
    http.get('/skills') as any,
    http.get('/tools') as any,
    http.get('/agent', { params: { pageSize: 100 } }) as any,
    http.get('/prompt-templates') as any,
    http.get('/resource-profiles', { _silent: true } as any).catch(() => [])
  ])
  resourceProfiles.value = Array.isArray(profileRes) ? profileRes : []
  promptTemplates.value = templatesRes?.list || []
  skillCatalog.value = skillsRes?.list || []
  toolCatalog.value = toolsRes?.list || []
  agentCatalog.value = (agentRes?.list || []).filter((a: any) => a.id !== agentId)
  form.visibility = detail.visibility || 'TENANT'
  form.listed = detail.listed !== false
  http.get(`/agent/${agentId}/grants`).then((g: any) => { grantedIds.value = g || [] }).catch(() => {})
  http.get('/users').then((ms: any) => { members.value = ms || [] }).catch(() => {})
  http.get(`/agent/${agentId}`).then(() => {}).catch(() => {})
  models.value = (modelList || []).filter((m: any) => !m.purpose || String(m.purpose).toUpperCase() === 'CHAT')

  form.status = detail.status
  form.updatedAt = detail.updatedAt || ''
  form.name = detail.name || ''
  form.description = detail.description || ''
  form.category = detail.category || ''
  tagsStr.value = ((detail.tags as string[]) || []).join(',')
  const mc = detail.modelConfig || {}
  form.modelName = mc.modelName || ''
  form.fallbackModelName = mc.fallbackModelName || ''
  form.grayModel = mc.grayModel || ''
  form.grayTag = mc.grayTag || ''
  form.temperature = mc.temperature ?? 0.7
  form.thinking = mc.thinking === true
  form.reasoningEffort = typeof mc.reasoningEffort === 'string' ? mc.reasoningEffort : 'medium'
  hydrateRoutingForm(form, mc)
  form.systemPrompt = detail.systemPrompt || ''
  form.openingStatement = detail.openingStatement || ''
  form.resourceProfileId = detail.resourceProfileId || null
  const caps = detail.agentCapabilities || {}
  form.capabilities = { ...caps, streamOutput: caps.streamOutput !== false, functionCall: !!caps.functionCall,
    maxToolCalls: Number.isFinite(Number(caps.maxToolCalls)) ? Number(caps.maxToolCalls) : 0,
    redactSecrets: caps.redactSecrets !== false,
    maxInputValidationAttempts: Number.isFinite(Number(caps.maxInputValidationAttempts))
      ? Number(caps.maxInputValidationAttempts) : 3,
    maxToolOutputChars: Number.isFinite(Number(caps.maxToolOutputChars))
      ? Number(caps.maxToolOutputChars) : 0,
    redactPatterns: Array.isArray(caps.redactPatterns) ? caps.redactPatterns : [],
    multimodal: !!caps.multimodal, selfCorrection: !!caps.selfCorrection,
    selfCorrectionRounds: caps.selfCorrectionRounds ?? 1,
    writeToolPolicy: caps.writeToolPolicy || 'ALLOW_ALL',
    allowedWriteToolIds: Array.isArray(caps.allowedWriteToolIds) ? caps.allowedWriteToolIds : [],
    writeToolMaxArgs: caps.writeToolMaxArgs ?? 16384 }
  writeToolPatternsText.value = JSON.stringify(caps.writeToolArgPatterns || {}, null, 2)
  redactPatternsText.value = JSON.stringify(caps.redactPatterns || [], null, 2)
  writeToolAmountLimitsText.value = JSON.stringify(caps.writeToolAmountLimits || {}, null, 2)
  const mem = detail.memoryConfig || {}
  form.memory = { enabled: mem.enabled !== false, windowSize: mem.windowSize ?? 10,
    autoExtract: mem.autoExtract !== false }
  form.variables = ((detail.variables as any[]) || []).map(v => ({
    key: v.key || '', type: v.type || 'STRING', required: !!v.required,
    defaultValue: v.defaultValue ?? '', description: v.description || ''
  }))

  const bound = new Map<number, any>()
  for (const b of detail.knowledgeBases || []) bound.set(b.knowledgeBaseId, b)
  const kbListArr: any[] = Array.isArray(kbList) ? kbList : (kbList?.list || [])
  kbRows.value = kbListArr.map(kb => {
    const b = bound.get(kb.id)
    const rc = b?.retrievalConfig || {}
    return {
      knowledgeBaseId: kb.id, name: kb.name,
      enabled: b ? b.enabled !== false : false,
      topK: rc.topK ?? kb.defaultTopK ?? 5, scoreThreshold: rc.scoreThreshold ?? 0.5, mode: rc.mode || 'hybrid',
      rerank: rc.rerank !== false, rerankTopN: rc.rerankTopN ?? rc.topK ?? kb.defaultTopK ?? 5,
      defaultTopK: kb.defaultTopK ?? 5, maxTopK: kb.maxTopK ?? 20,
      searchBackend: kb.searchBackend || 'MYSQL', vectorBackend: kb.vectorBackend || 'PGVECTOR',
      documentType: rc.documentType || undefined, sectionType: rc.sectionType || undefined,
      regulatoryCategory: rc.regulatoryCategory || undefined, chunkLevel: rc.chunkLevel,
      minRelevance: rc.minRelevance
    }
  })
  buildRefRows('skill', detail.skills, skillCatalog.value, skillRows)
  buildRefRows('tool', detail.tools, toolCatalog.value, toolRows)
  buildRefRows('sub', detail.subAgents, agentCatalog.value, subRows)
  // 工作流面板徽标随页面加载刷新：此前只在打开工作流弹窗后才更新，已配置的 Agent 进页面也显示「未配置」
  http.get(`/agent/${agentId}/workflow`, { _silent: true } as any)
    .then((d: any) => applyWorkflowData(d))
    .catch(() => {})

  // 已删除/无权限的知识库显示为「已失效」，运行时跳过（需求 3.6.3(4)）
  const visibleIds = new Set(kbListArr.map(kb => kb.id))
  // 发布信息需要展示已部署服务的对外地址；不阻塞编辑页加载
  loadPublishInfo().catch(() => {})
  for (const b of detail.knowledgeBases || []) {
    if (!visibleIds.has(b.knowledgeBaseId)) {
      kbRows.value.push({
        knowledgeBaseId: b.knowledgeBaseId, name: `${t('agentMsg.invalidSuffix')}KB #${b.knowledgeBaseId}${t('agentMsg.invalidSuffix2')}`,
        enabled: false, topK: 5, scoreThreshold: 0.5, mode: 'hybrid', rerank: false, rerankTopN: 5, missing: true
      })
    }
  }
})
</script>

<style scoped src="./AgentEditView.css"></style>
