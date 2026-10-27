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
              </el-form-item>
              <el-form-item :label="t('agentEdit.fallback')">
                <el-select v-model="form.fallbackModelName" clearable :placeholder="t('agentEdit.fallbackPh')"
                           style="width: 320px">
                  <el-option v-for="m in models.filter(x => x.modelName !== form.modelName)"
                             :key="m.modelName" :value="m.modelName" :label="modelLabel(m)" />
                </el-select>
                <span class="hint">{{ t('agentEdit.fallbackHint') }}</span>
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
            <div v-if="kbRows.length === 0" class="hint">{{ t('agentEdit.noKb') }}</div>
            <div v-for="kb in kbRows" :key="kb.knowledgeBaseId" class="kb-row">
              <el-switch v-model="kb.enabled" :disabled="kb.missing" />
              <div class="kb-name">{{ kb.name }}</div>
              <el-tag v-if="kb.missing" size="small" type="danger">{{ t('agentEdit.invalid') }}</el-tag>
              <div class="kb-params" v-if="kb.enabled">
                {{ t('agentEdit.mode') }}
                <el-select v-model="kb.mode" size="small" style="width: 100px">
                  <el-option :label="t('agentEdit.hybrid')" value="hybrid" />
                  <el-option :label="t('agentEdit.vector')" value="vector" />
                  <el-option :label="t('agentEdit.keyword')" value="keyword" />
                </el-select>
                topK <el-input-number v-model="kb.topK" :min="1" :max="20" size="small" />
                {{ t('agentEdit.threshold') }} <el-input-number v-model="kb.scoreThreshold" :min="0" :max="1" :step="0.05" size="small" />
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
        <el-button type="primary" :loading="workflowSaving" @click="saveWorkflow">{{ t('wf.saveDraft') }}</el-button>
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
                 @dblclick="openNodePanel(n)"
                 @click="onCanvasNodeClick(n, $event)">
              <el-tag size="small" :type="wfTagType(n.type)">{{ wfTypeText(n.type) }}</el-tag>
              <div class="wf-cn-name">{{ n.name }}</div>
              <div class="wf-cn-id">#{{ n.id }}</div>
              <span v-if="n.type !== 'END'" class="wf-port" :title="t('wf.dragPortHint')"
                    @mousedown.stop="startLinkDrag($event, n)" @click.stop />
              <span v-if="n.type !== 'START'" class="wf-port wf-port-in" />
            </div>
            <div v-if="wfBoxSelect" class="wf-select-box" :style="wfBoxStyle" />
          </div>
          <div class="hint mt8">{{ t('wf.canvasHint') }}</div>
          </div>
          <div v-if="wfNodePanel && wfSelectedNode" class="wf-node-panel">
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
      <el-input v-model="workflowRunInput" type="textarea" :rows="2" :placeholder="t('wf.runInputPh')" />
      <div class="hint mt8">{{ t('wf.runHint') }}</div>
      <div class="mt8">
        <el-button type="primary" :loading="workflowRunning" @click="runWorkflow">{{ t('wf.run') }}</el-button>
        <el-link v-if="workflowRunResult?.status === 'WAITING_APPROVAL'" type="warning"
                 @click="goApprovals">{{ t('wf.waitingApproval') }}</el-link>
      </div>
      <div v-if="workflowRunResult" class="wf-run">
        <div>
          {{ t('wf.status') }}{{ workflowRunResult.status }}{{ t('wf.currentNode') }}{{ workflowRunResult.currentNode || '-' }}
          · {{ t('wf.tokenLabel') }}{{ workflowRunResult.inputTokens || 0 }}/{{ workflowRunResult.outputTokens || 0 }}
        </div>
        <pre>{{ workflowRunResult.output || workflowRunResult.error || '' }}</pre>
        <div v-if="workflowRunResult.nodeRuns?.length" class="hint mt8">
          {{ t('wf.nodes') }}
          {{ workflowRunResult.nodeRuns.map((x: any) => `${x.name || x.nodeId}(${x.status})`).join(' → ') }}
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
        <el-button type="primary" :loading="workflowSaving" @click="saveWorkflow">{{ t('wf.saveWorkflow') }}</el-button>
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

    <!-- 容器部署 -->
    <el-dialog v-model="deployVisible" :title="t('deploy.title')" width="760px">
      <el-alert v-if="form.status !== 'PUBLISHED'" type="warning" :closable="false"
                :title="t('deploy.publishedOnly')" class="mb8" />
      <el-alert v-if="!deployInfo.dockerAvailable" type="error" :closable="false"
                :title="t('deploy.dockerMissing')" class="mb8" />
      <template v-if="deployment">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item :label="t('deploy.status')">
            <el-tag :type="deployTagType" size="small">{{ deployStatusText }}</el-tag>
            <span class="hint">{{ t('deploy.versionPrefix') }}{{ deployment.version || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item :label="t('deploy.endpoint')">
            <code class="deploy-code">{{ deployment.endpoint }}</code>
            <el-button size="small" text type="primary" @click="copyText(deployment.endpoint!)">
              {{ t('deploy.copy') }}
            </el-button>
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
        <el-alert v-if="deployment.lastError" type="error" :closable="false"
                  :title="deployment.lastError" class="mt8" />
        <div class="deploy-replicas">
          <span>{{ t('deploy.replicas') }}</span>
          <el-input-number v-model="deployReplicas" :min="1" :max="5" size="small" />
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
                     :disabled="!deployInfo.dockerAvailable" @click="doDeploy">{{ t('deploy.redeploy') }}</el-button>
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
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElLoading, ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import http from '../api/http'
import { escapeHtml, renderGateFailures } from '../utils/gateDetails'
import WorkflowNodeForm from '../components/WorkflowNodeForm.vue'

const { t } = useI18n()

const route = useRoute()
const router = useRouter()
const agentId = Number(route.params.id)

const activePanels = ref(['base', 'model', 'prompt', 'knowledge', 'skill', 'tool', 'subagent', 'variable'])
const models = ref<any[]>([])
const kbRows = ref<KbRow[]>([])
const versions = ref<any[]>([])
const members = ref<any[]>([])
const grantedIds = ref<number[]>([])
const ownerId = ref<number>(0)
const versionsVisible = ref(false)
const deployVisible = ref(false)
const deployInfo = reactive({ dockerAvailable: false, dockerVersion: '', image: '' })
const deployment = ref<any>(null)
const deploying = ref(false)
const deployLogsVisible = ref(false)
const deployLogs = ref('')
const deployHistoryVisible = ref(false)
const deployHistory = ref<any[]>([])
const runtimeToken = ref('')
const runtimeKeys = ref<any[]>([])
const runtimeStats = ref<any>({ callers: [] })
const deployReplicas = ref(1)
const runtimeKeyDialogVisible = ref(false)
const runtimeKeySaving = ref(false)
const runtimeKeyForm = reactive({ id: 0, name: '', dailyQuota: 0 })
const createdRuntimeKey = ref('')
const newRuntimeKeyVisible = ref(false)
const saving = ref(false)

const form = reactive({
  status: 'DRAFT', updatedAt: '',
  name: '', description: '', category: '', visibility: 'TENANT', listed: true,
  modelName: '', fallbackModelName: '', temperature: 0.7,
  thinking: false, reasoningEffort: 'medium',
  systemPrompt: '', openingStatement: '',
  capabilities: { streamOutput: true, functionCall: false, multimodal: false, selfCorrection: false,
    selfCorrectionRounds: 1, writeToolPolicy: 'ALLOW_ALL', allowedWriteToolIds: [] as number[], writeToolMaxArgs: 16384 },
  memory: { enabled: true, windowSize: 10, autoExtract: true },
  variables: [] as VariableRow[]
})
const tagsStr = ref('')
const writeToolPatternsText = ref('')
const writeToolAmountLimitsText = ref('')
const enabledKbCount = computed(() => kbRows.value.filter(k => k.enabled).length)

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
  documentType?: string; sectionType?: string; regulatoryCategory?: string
  chunkLevel?: number; minRelevance?: number; missing?: boolean
}
interface RefRow { id: number; name: string; description?: string; type?: string; isBuiltin?: boolean; enabled: boolean; missing?: boolean }
type RefType = 'skill' | 'tool' | 'sub'

const skillRows = ref<RefRow[]>([])
const toolRows = ref<RefRow[]>([])
const subRows = ref<RefRow[]>([])
const skillCatalog = ref<any[]>([])
const toolCatalog = ref<any[]>([])
const agentCatalog = ref<any[]>([])
const picker = reactive({ visible: false, type: 'skill' as RefType, selected: [] as number[] })
const credentialVisible = ref(false)
const credentials = ref<any[]>([])
const credentialFormVisible = ref(false)
const credentialSaving = ref(false)
const credentialForm = reactive({
  id: 0, name: '', type: 'API_KEY', content: '', toolId: null as number | null, enabled: true
})
const scheduleVisible = ref(false)
const schedules = ref<any[]>([])
const scheduleFormVisible = ref(false)
const scheduleSaving = ref(false)
const scheduleForm = reactive({
  id: 0, name: '', cronExpr: '0 */5 * * * *', inputParamsText: '{}',
  timeoutMs: 60000, notifyType: 'STORE', webhookUrl: '', enabled: true
})
const resultsVisible = ref(false)
const scheduleResults = ref<any[]>([])
const resultsTitle = ref('')
const integrationVisible = ref(false)
const apiKeys = ref<any[]>([])
const mcpEndpoint = `${window.location.origin}/mcp`
const createdKey = ref('')
const channelsVisible = ref(false)
const channelMessagesVisible = ref(false)
const channelMessages = ref<any[]>([])
const messagesChannelName = ref('')
const channels = ref<any[]>([])
const channelCreateVisible = ref(false)
const channelSaving = ref(false)
const channelTestingId = ref(0)
const createdInbound = ref('')
const channelForm = reactive({ name: '', type: 'WEBHOOK', outboundUrl: '', secret: '', verificationToken: '', encryptKey: '', encodingAesKey: '', corpId: '', rateLimitPerMinute: 60 })
const widgetSnippet = ref('')
const widgetKey = ref('')
const widgetCreating = ref(false)
const keyCreateVisible = ref(false)
const keySaving = ref(false)
const keyForm = reactive({ name: '', channel: 'API', dailyLimit: 0, minuteLimit: 0 })
const optimizeVisible = ref(false)
const optimizing = ref(false)
const optimizeStyle = ref('')
const optimizeInstruction = ref('')
const optimizeResult = ref('')
const optimizeUsage = ref<any>(null)
const optimizeModel = ref('')
const promptTemplates = ref<any[]>([])
const templateVisible = ref(false)
const templateId = ref(0)
const selectedTemplate = computed(() => promptTemplates.value.find(t => t.id === templateId.value))

// ---- 工作流 / 人工审批 ----
const workflowVisible = ref(false)
const workflowConfigured = ref(false)
const workflowSaving = ref(false)
const workflowRunning = ref(false)
const wfAddType = ref('LLM')
const wfTypes = [
  { label: t('wf.typeLlm'), value: 'LLM' },
  { label: t('wf.typeAgent'), value: 'AGENT' },
  { label: 'Agent Handoff', value: 'HANDOFF' },
  { label: t('wf.typeState'), value: 'STATE' },
  { label: t('wf.typeTool'), value: 'TOOL' },
  { label: t('wf.typeKnowledge'), value: 'KNOWLEDGE' },
  { label: t('wf.typeCondition'), value: 'CONDITION' },
  { label: t('wf.typeParallel'), value: 'PARALLEL' },
  { label: t('wf.typeLoop'), value: 'LOOP' },
  { label: t('wf.typeJoin'), value: 'JOIN' },
  { label: t('wf.typeApproval'), value: 'APPROVAL' }
]
const workflowForm = reactive({ name: '', status: 'ENABLED', nodes: [] as any[], edges: [] as any[] })
const workflowRunInput = ref('')
const workflowRunResult = ref<any>(null)
const workflowPublishedVersion = ref('')
const workflowHasUnpublishedChanges = ref(false)
const workflowPublishing = ref(false)
const wfVersionsVisible = ref(false)
const wfVersions = ref<any[]>([])
const wfMode = ref<'list' | 'canvas'>('list')
const wfSelected = ref('')
const wfLinkMode = ref(false)
const wfLinkFrom = ref('')
const wfHistory = ref<string[]>([])
const wfHistoryIndex = ref(-1)
const wfSelectedEdge = ref<number | null>(null)
const wfDraggingType = ref('')
const wfDropActive = ref(false)
const wfLinkDrag = ref<null | { from: string; x1: number; y1: number; x2: number; y2: number }>(null)
const wfNodePanel = ref(false)
const wfRewireMode = ref(false)
const wfMultiSelected = ref<string[]>([])
const wfClipboardSize = ref(0)
const wfReplayIndex = ref(-1)
const wfReplayPlaying = ref(false)
let wfReplayTimer: ReturnType<typeof setTimeout> | null = null
const wfBoxSelect = ref<null | { x1: number; y1: number; x2: number; y2: number }>(null)
let wfClipboard: { nodes: any[]; edges: any[] } | null = null
let wfNodeEditBefore = ''
const wfSelectedNode = computed(() => workflowForm.nodes.find((n: any) => n.id === wfSelected.value) || null)
const wfBoxStyle = computed(() => {
  const b = wfBoxSelect.value
  if (!b) return {}
  return {
    left: Math.min(b.x1, b.x2) + 'px',
    top: Math.min(b.y1, b.y2) + 'px',
    width: Math.abs(b.x2 - b.x1) + 'px',
    height: Math.abs(b.y2 - b.y1) + 'px'
  }
})
const wfCanvasEl = ref<HTMLElement>()
const wfDiffVisible = ref(false)
const wfDiffTitle = ref('')
const wfDiffRows = ref<any[]>([])
const wfZoom = ref(1)
const wfTemplatesVisible = ref(false)
const wfTemplates = ref<any[]>([])

const enabledSkillCount = computed(() => skillRows.value.filter(r => r.enabled && !r.missing).length)
const enabledToolCount = computed(() => toolRows.value.filter(r => r.enabled && !r.missing).length)
const enabledSubCount = computed(() => subRows.value.filter(r => r.enabled && !r.missing).length)
const pickerTitle = computed(() => ({ skill: t('wf.pickerAddSkill'), tool: t('wf.pickerAddTool'), sub: t('wf.pickerAddSub') }[picker.type]))
const pickerPlaceholder = computed(() => ({ skill: t('wf.pickerSelectSkill'), tool: t('wf.pickerSelectTool'), sub: t('wf.pickerSelectSub') }[picker.type]))
const pickerOptions = computed(() => {
  const rows = picker.type === 'skill' ? skillRows.value : picker.type === 'tool' ? toolRows.value : subRows.value
  const existing = new Set(rows.map(r => r.id))
  const source = picker.type === 'skill' ? skillCatalog.value : picker.type === 'tool' ? toolCatalog.value : agentCatalog.value
  return source.filter(o => !existing.has(o.id))
})
interface VariableRow { key: string; type: string; required: boolean; defaultValue: string; description: string }

const debugMsgs = ref<any[]>([])
const debugInput = ref('')
const debugRunning = ref(false)
const dbgBox = ref<HTMLElement>()

onMounted(async () => {
  const [detail, modelList, kbList, skillsRes, toolsRes, agentRes, templatesRes] = await Promise.all([
    http.get(`/agent/${agentId}`) as any,
    http.get('/provider/models') as any,
    http.get('/knowledge-base', { params: { pageSize: 100 } }) as any,
    http.get('/skills') as any,
    http.get('/tools') as any,
    http.get('/agent', { params: { pageSize: 100 } }) as any,
    http.get('/prompt-templates') as any
  ])
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
  form.temperature = mc.temperature ?? 0.7
  form.thinking = mc.thinking === true
  form.reasoningEffort = typeof mc.reasoningEffort === 'string' ? mc.reasoningEffort : 'medium'
  form.systemPrompt = detail.systemPrompt || ''
  form.openingStatement = detail.openingStatement || ''
  const caps = detail.agentCapabilities || {}
  form.capabilities = { ...caps, streamOutput: caps.streamOutput !== false, functionCall: !!caps.functionCall,
    multimodal: !!caps.multimodal, selfCorrection: !!caps.selfCorrection,
    selfCorrectionRounds: caps.selfCorrectionRounds ?? 1,
    writeToolPolicy: caps.writeToolPolicy || 'ALLOW_ALL',
    allowedWriteToolIds: Array.isArray(caps.allowedWriteToolIds) ? caps.allowedWriteToolIds : [],
    writeToolMaxArgs: caps.writeToolMaxArgs ?? 16384 }
  writeToolPatternsText.value = JSON.stringify(caps.writeToolArgPatterns || {}, null, 2)
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
      topK: rc.topK ?? 5, scoreThreshold: rc.scoreThreshold ?? 0.5, mode: rc.mode || 'hybrid',
      documentType: rc.documentType || undefined, sectionType: rc.sectionType || undefined,
      regulatoryCategory: rc.regulatoryCategory || undefined, chunkLevel: rc.chunkLevel,
      minRelevance: rc.minRelevance
    }
  })
  buildRefRows('skill', detail.skills, skillCatalog.value, skillRows)
  buildRefRows('tool', detail.tools, toolCatalog.value, toolRows)
  buildRefRows('sub', detail.subAgents, agentCatalog.value, subRows)

  // 已删除/无权限的知识库显示为「已失效」，运行时跳过（需求 3.6.3(4)）
  const visibleIds = new Set(kbListArr.map(kb => kb.id))
  for (const b of detail.knowledgeBases || []) {
    if (!visibleIds.has(b.knowledgeBaseId)) {
      kbRows.value.push({
        knowledgeBaseId: b.knowledgeBaseId, name: `${t('agentMsg.invalidSuffix')}KB #${b.knowledgeBaseId}${t('agentMsg.invalidSuffix2')}`,
        enabled: false, topK: 5, scoreThreshold: 0.5, mode: 'hybrid', missing: true
      })
    }
  }
})

function buildRefRows(type: RefType, bindings: any[], catalog: any[], target: any) {
  const bound = new Map<number, any>()
  for (const b of bindings || []) {
    bound.set(type === 'skill' ? b.skillId : type === 'tool' ? b.toolId : b.subAgentId, b)
  }
  const rows: RefRow[] = []
  for (const item of catalog) {
    const b = bound.get(item.id)
    if (!b) continue
    rows.push({
      id: item.id, name: item.name, description: item.description,
      type: item.toolType || item.skillType, isBuiltin: !!item.isBuiltin,
      enabled: b.enabled !== false, missing: false
    })
  }
  const visibleIds = new Set(catalog.map((o: any) => o.id))
  for (const b of bindings || []) {
    const id = type === 'skill' ? b.skillId : type === 'tool' ? b.toolId : b.subAgentId
    if (!visibleIds.has(id)) {
      const label = type === 'skill' ? t('agentMsg.typeSkill') : type === 'tool' ? t('agentMsg.typeTool') : t('agentMsg.typeAgent')
      rows.push({ id, name: `${t('agentMsg.invalidSuffix')}${label} #${id}${t('agentMsg.invalidSuffix2')}`, enabled: false, missing: true })
    }
  }
  target.value = rows
}

async function openCredentials() {
  await loadCredentials()
  credentialVisible.value = true
}

async function loadCredentials() {
  const res = await http.get(`/agent/${agentId}/credentials`) as any
  credentials.value = res?.list || []
}

function toolName(id?: number) {
  if (!id) return '—'
  return toolCatalog.value.find((t: any) => t.id === id)?.name || (`${t('agentMsg.typeTool')} #${id}`)
}

function openCredentialCreate() {
  Object.assign(credentialForm, {
    id: 0, name: '', type: 'API_KEY', content: '', toolId: null, enabled: true
  })
  credentialFormVisible.value = true
}

function openCredentialEdit(row: any) {
  Object.assign(credentialForm, {
    id: row.id, name: row.name || '', type: row.type || 'API_KEY',
    content: '', toolId: row.toolId || null, enabled: row.status !== 'DISABLED'
  })
  credentialFormVisible.value = true
}

async function saveCredential() {
  if (!credentialForm.name.trim()) { ElMessage.warning(t('agentDlg.keyNameRequired')); return }
  if (!credentialForm.id && !credentialForm.content.trim()) { ElMessage.warning(t('agentMsg.credContentRequired')); return }
  credentialSaving.value = true
  try {
    const payload: any = {
      name: credentialForm.name.trim(),
      type: credentialForm.type,
      toolId: credentialForm.toolId,
      status: credentialForm.enabled ? 'ENABLED' : 'DISABLED'
    }
    if (credentialForm.content.trim()) payload.content = credentialForm.content
    if (credentialForm.id) await http.put(`/agent/${agentId}/credentials/${credentialForm.id}`, payload)
    else await http.post(`/agent/${agentId}/credentials`, payload)
    ElMessage.success(t('agentDlg.saved'))
    credentialFormVisible.value = false
    await loadCredentials()
  } finally {
    credentialSaving.value = false
  }
}

async function removeCredential(row: any) {
  await ElMessageBox.confirm(`${t('agentMsg.deleteCredPrefix')}${row.name}${t('agentMsg.deleteCredMid')}`, t('agentMsg.deleteCredTitle'),
    { type: 'warning', confirmButtonText: t('agentDlg.delete'), cancelButtonText: t('agentDlg.cancel') })
  await http.delete(`/agent/${agentId}/credentials/${row.id}`)
  ElMessage.success(t('agentDlg.deleted'))
  await loadCredentials()
}

async function openSchedules() {
  await loadSchedules()
  scheduleVisible.value = true
}

async function loadSchedules() {
  const res = await http.get(`/agent/${agentId}/schedules`) as any
  schedules.value = res?.list || []
}

function openScheduleCreate() {
  Object.assign(scheduleForm, {
    id: 0, name: '', cronExpr: '0 */5 * * * *', inputParamsText: '{}',
    timeoutMs: 60000, notifyType: 'STORE', webhookUrl: '', enabled: true
  })
  scheduleFormVisible.value = true
}

function openScheduleEdit(row: any) {
  Object.assign(scheduleForm, {
    id: row.id,
    name: row.name || '',
    cronExpr: row.cronExpr || '',
    inputParamsText: JSON.stringify(row.inputParams || {}, null, 2),
    timeoutMs: row.timeoutMs ?? 60000,
    notifyType: row.notifyType || 'STORE',
    webhookUrl: row.webhookUrl || '',
    enabled: row.status !== 'DISABLED'
  })
  scheduleFormVisible.value = true
}

async function saveSchedule() {
  if (!scheduleForm.name.trim() || !scheduleForm.cronExpr.trim()) {
    ElMessage.warning(t('agentDlg.nameCronRequired')); return
  }
  let params: any
  try {
    params = JSON.parse(scheduleForm.inputParamsText || '{}')
  } catch {
    ElMessage.error(t('agentDlg.invalidJson')); return
  }
  scheduleSaving.value = true
  try {
    const payload = {
      name: scheduleForm.name.trim(),
      cronExpr: scheduleForm.cronExpr.trim(),
      inputParams: params,
      timeoutMs: scheduleForm.timeoutMs,
      notifyType: scheduleForm.notifyType,
      webhookUrl: scheduleForm.webhookUrl || null,
      status: scheduleForm.enabled ? 'ENABLED' : 'DISABLED'
    }
    if (scheduleForm.id) await http.put(`/agent/${agentId}/schedules/${scheduleForm.id}`, payload)
    else await http.post(`/agent/${agentId}/schedules`, payload)
    ElMessage.success(t('agentDlg.saved'))
    scheduleFormVisible.value = false
    await loadSchedules()
  } finally {
    scheduleSaving.value = false
  }
}

async function toggleSchedule(row: any) {
  await http.put(`/agent/${agentId}/schedules/${row.id}`, {
    status: row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  })
  await loadSchedules()
}

async function runSchedule(row: any) {
  const res = await http.post(`/agent/${agentId}/schedules/${row.id}/run`) as any
  ElMessage[res.status === 'SUCCESS' ? 'success' : 'error'](
    res.status === 'SUCCESS' ? t('agentDlg.execSuccess') : (t('agentDlg.execFailed') + res.message))
  await loadSchedules()
}

async function openResults(row: any) {
  resultsTitle.value = `${t('agentDlg.resultTitle')}${row.name}`
  scheduleResults.value = await http.get(`/agent/${agentId}/schedules/${row.id}/results`) as any
  resultsVisible.value = true
}

async function removeSchedule(row: any) {
  await ElMessageBox.confirm(`${t('agentDlg.deleteSchedulePrefix')}${row.name}${t('agentDlg.deleteScheduleMid')}`, t('agentDlg.deleteScheduleTitle'),
    { type: 'warning', confirmButtonText: t('agentDlg.delete'), cancelButtonText: t('agentDlg.cancel') })
  await http.delete(`/agent/${agentId}/schedules/${row.id}`)
  ElMessage.success(t('agentDlg.deleted'))
  await loadSchedules()
}

async function openIntegration() {
  integratedAgentId.value = agentId
  createdKey.value = ''
  widgetSnippet.value = ''
  widgetKey.value = ''
  await loadApiKeys()
  integrationVisible.value = true
}

async function openChannels() {
  channels.value = await http.get(`/agent/${agentId}/channels`) as any
  createdInbound.value = ''
  channelsVisible.value = true
}

async function createChannel() {
  channelSaving.value = true
  try {
    const res = await http.post(`/agent/${agentId}/channels`, {
      name: channelForm.name,
      type: channelForm.type,
      outboundUrl: channelForm.outboundUrl,
      secret: channelForm.secret,
      config: {
        verificationToken: channelForm.verificationToken,
        encryptKey: channelForm.encryptKey,
        encodingAesKey: channelForm.encodingAesKey,
        corpId: channelForm.corpId,
        rateLimitPerMinute: channelForm.rateLimitPerMinute
      }
    }) as any
    ElMessage.success(t('agentDlg.channelCreated'))
    channelCreateVisible.value = false
    await openChannels()
    createdInbound.value = location.origin + res.inboundPath
    Object.assign(channelForm, { name: '', type: 'WEBHOOK', outboundUrl: '', secret: '',
      verificationToken: '', encryptKey: '', encodingAesKey: '', corpId: '', rateLimitPerMinute: 60 })
  } finally {
    channelSaving.value = false
  }
}

function inboundUrl(row: any) {
  return location.origin + row.inboundPath
}

async function openChannelMessages(row: any) {
  messagesChannelName.value = row.name
  const data = await http.get(`/agent/${agentId}/channels/${row.id}/messages`, { params: { pageSize: 50 } }) as any
  channelMessages.value = data?.list || []
  channelMessagesVisible.value = true
}

async function copyInbound(row: any) {
  await copyText(inboundUrl(row))
}

async function testChannel(row: any) {
  channelTestingId.value = row.id
  try {
    const res = await http.post(`/agent/${agentId}/channels/${row.id}/test`) as any
    if (res.success) ElMessage.success(`${t('agentDlg.outboundOk')}${res.latencyMs}ms)`)
    else ElMessage.error(`${t('agentDlg.outboundFail')}${res.message}`)
  } finally {
    channelTestingId.value = 0
  }
}

async function toggleChannel(row: any) {
  await http.put(`/agent/${agentId}/channels/${row.id}`, {
    status: row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  })
  await openChannels()
}

async function removeChannel(row: any) {
  await ElMessageBox.confirm(`${t('agentDlg.deleteChannelPrefix')}${row.name}${t('agentDlg.deleteChannelMid')}`, t('agentDlg.deleteChannelTitle'),
    { type: 'warning', confirmButtonText: t('agentDlg.delete'), cancelButtonText: t('agentDlg.cancel') })
  await http.delete(`/agent/${agentId}/channels/${row.id}`)
  ElMessage.success(t('agentDlg.deleted'))
  await openChannels()
}

async function loadApiKeys() {
  const res = await http.get(`/agent/${agentId}/api-keys`) as any
  apiKeys.value = res?.list || []
}

function openKeyCreate() {
  Object.assign(keyForm, { name: '', channel: 'API', dailyLimit: 0, minuteLimit: 0 })
  keyCreateVisible.value = true
}

async function createKey() {
  if (!keyForm.name.trim()) { ElMessage.warning(t('agentDlg.keyNameRequired')); return }
  keySaving.value = true
  try {
    const res = await http.post(`/agent/${agentId}/api-keys`, {
      name: keyForm.name.trim(), channel: keyForm.channel,
      dailyLimit: keyForm.dailyLimit, minuteLimit: keyForm.minuteLimit
    }) as any
    createdKey.value = res.apiKey
    keyCreateVisible.value = false
    await loadApiKeys()
  } finally {
    keySaving.value = false
  }
}

async function createWidgetKey() {
  widgetCreating.value = true
  try {
    const res = await http.post(`/agent/${agentId}/api-keys`, {
      name: t('agentMsg.widgetName'), channel: 'WIDGET', dailyLimit: 0, minuteLimit: 60
    }) as any
    widgetKey.value = res.apiKey
    const src = `${location.origin}/widget?key=${encodeURIComponent(res.apiKey)}`
    widgetSnippet.value = `<iframe src="${src}" :title="t('widget.defaultName')" style="width:100%;height:640px;border:0;border-radius:12px"></iframe>`
    ElMessage.success(t('agentDlg.widgetGenerated'))
    await loadApiKeys()
  } finally {
    widgetCreating.value = false
  }
}

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(t('agentDlg.copied'))
  } catch {
    ElMessage.warning(t('agentDlg.copyFailManual'))
  }
}

// ---- 容器部署 ----
const deployTagType = computed(() => ({
  RUNNING: 'success', STOPPED: 'info', FAILED: 'danger', PENDING: 'warning'
} as Record<string, string>)[deployment.value?.status] || 'info')

const deployStatusText = computed(() =>
  t('deploy.statuses.' + (deployment.value?.status || 'PENDING')))

const deployCurl = computed(() => {
  const endpoint = deployment.value?.endpoint || 'http://localhost:19000/v1'
  const token = runtimeToken.value || '<访问令牌>'
  return `curl -s ${endpoint}/chat/completions \\\n  -H 'Authorization: Bearer ${token}' \\\n  -H 'Content-Type: application/json' \\\n  -d '{"model":"agent","messages":[{"role":"user","content":"你好"}]}'`
})

async function openDeploy() {
  deployVisible.value = true
  await refreshDeploy()
}

async function refreshDeploy() {
  try {
    const data = await http.get(`/agent/${agentId}/deployment`) as any
    deployInfo.dockerAvailable = !!data.dockerAvailable
    deployInfo.dockerVersion = data.dockerVersion || ''
    deployInfo.image = data.image || ''
    deployment.value = data.deployment || null
    deployReplicas.value = deployment.value?.replicaCount || 1
    runtimeToken.value = ''
    if (deployment.value?.hasToken) {
      try {
        runtimeToken.value = ((await http.get(`/agent/${agentId}/deployment/token`) as any).token) || ''
      } catch { /* 旧部署可能没有令牌 */ }
    }
    await Promise.all([loadRuntimeKeys(), loadDeployStats()])
  } catch {
    deployment.value = null
    runtimeToken.value = ''
    runtimeKeys.value = []
    runtimeStats.value = { callers: [] }
  }
}

async function doDeploy() {
  if (!deployInfo.dockerAvailable) {
    ElMessage.warning(t('deploy.dockerMissing'))
    return
  }
  deploying.value = true
  try {
    deployment.value = await http.post(`/agent/${agentId}/deployment`, { replicas: deployReplicas.value }) as any
    await refreshDeploy()
    ElMessage.success(t('deploy.ok'))
  } finally {
    deploying.value = false
  }
}

async function deployAction(action: 'start' | 'stop') {
  const data = await http.post(`/agent/${agentId}/deployment/${action}`, {}) as any
  deployment.value = data
  ElMessage.success(t(action === 'start' ? 'deploy.started' : 'deploy.stopped'))
}

async function openDeployLogs() {
  const data = await http.get(`/agent/${agentId}/deployment/logs`, { params: { tail: 200 } }) as any
  deployLogs.value = data?.logs || ''
  deployLogsVisible.value = true
}

async function openDeployHistory() {
  deployHistory.value = await http.get(`/agent/${agentId}/deployment/history`,
    { params: { limit: 50 } }) as any || []
  deployHistoryVisible.value = true
}

function deployActionText(action: string) {
  const key = `deploy.actions.${action}`
  return t(key) === key ? action : t(key)
}

function deployActionTag(action: string) {
  return ({ DEPLOY: 'primary', START: 'success', STOP: 'warning', REMOVE: 'danger' } as any)[action] || 'info'
}

async function removeDeploy() {
  await ElMessageBox.confirm(t('deploy.confirmRemove'), t('deploy.remove'), { type: 'warning' })
  await http.delete(`/agent/${agentId}/deployment`)
  deployment.value = null
  ElMessage.success(t('deploy.removed'))
}

async function rollbackDeployment(version: string) {
  await ElMessageBox.confirm(`${t('deploy.rollbackConfirm')}${version}${t('deploy.rollbackConfirmSuffix')}`,
    t('deploy.rollbackDeploy'), { type: 'warning' })
  await http.post(`/agent/${agentId}/versions/${version}/rollback`)
  await http.post(`/agent/${agentId}/deployment`, {})
  ElMessage.success(t('deploy.rollbackDeployed'))
  deployHistoryVisible.value = false
  setTimeout(() => router.go(0), 800)
}

const mainTokenUsage = computed(() => {
  const row = (runtimeStats.value?.callers || []).find((c: any) => c.caller === 'default')
  return row?.requests || 0
})

function keyUsage(row: any) {
  const stat = (runtimeStats.value?.callers || []).find((c: any) => c.caller === row.callerId)
  return stat?.requests || 0
}

async function loadRuntimeKeys() {
  try {
    runtimeKeys.value = await http.get(`/agent/${agentId}/runtime-keys`) as any || []
  } catch {
    runtimeKeys.value = []
  }
}

async function loadDeployStats() {
  if (!deployment.value) {
    runtimeStats.value = { callers: [] }
    return
  }
  try {
    runtimeStats.value = await http.get(`/agent/${agentId}/deployment/stats`) as any || { callers: [] }
  } catch {
    runtimeStats.value = { callers: [] }
  }
}

function openKeyDialog(row?: any) {
  runtimeKeyForm.id = row?.id || 0
  runtimeKeyForm.name = row?.name || ''
  runtimeKeyForm.dailyQuota = row?.dailyQuota || 0
  runtimeKeyDialogVisible.value = true
}

async function saveRuntimeKey() {
  if (!runtimeKeyForm.name.trim()) {
    ElMessage.warning(t('deploy.keyNamePh'))
    return
  }
  runtimeKeySaving.value = true
  try {
    if (runtimeKeyForm.id) {
      await http.put(`/agent/${agentId}/runtime-keys/${runtimeKeyForm.id}`,
        { name: runtimeKeyForm.name.trim(), dailyQuota: runtimeKeyForm.dailyQuota })
    } else {
      const created = await http.post(`/agent/${agentId}/runtime-keys`,
        { name: runtimeKeyForm.name.trim(), dailyQuota: runtimeKeyForm.dailyQuota }) as any
      createdRuntimeKey.value = created?.apiKey || ''
      newRuntimeKeyVisible.value = true
    }
    runtimeKeyDialogVisible.value = false
    await applyRuntimeKeys()
  } finally {
    runtimeKeySaving.value = false
  }
}

async function toggleRuntimeKey(row: any) {
  await http.put(`/agent/${agentId}/runtime-keys/${row.id}`,
    { status: row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED' })
  await applyRuntimeKeys()
}

async function removeRuntimeKey(row: any) {
  await ElMessageBox.confirm(`${t('deploy.confirmDeleteKey')}${row.name}?`,
    t('deploy.delete'), { type: 'warning' })
  await http.delete(`/agent/${agentId}/runtime-keys/${row.id}`)
  await applyRuntimeKeys()
  ElMessage.success(t('deploy.keyDeleted'))
}

/** Key 变更后重建容器使其生效（未部署则等下次部署） */
async function applyRuntimeKeys() {
  if (deployment.value) {
    deploying.value = true
    try {
      deployment.value = await http.post(`/agent/${agentId}/deployment`, { replicas: deployReplicas.value }) as any
      await refreshDeploy()
      ElMessage.success(t('deploy.keySynced'))
    } finally {
      deploying.value = false
    }
  } else {
    await loadRuntimeKeys()
    ElMessage.info(t('deploy.keySavedPending'))
  }
}

async function rotateDeployToken() {
  await ElMessageBox.confirm(t('deploy.rotateConfirm'), t('deploy.rotateToken'), { type: 'warning' })
  deployment.value = await http.post(`/agent/${agentId}/deployment/token/rotate`, { replicas: deployReplicas.value }) as any
  await refreshDeploy()
  ElMessage.success(t('deploy.rotated'))
}

async function copyWidget() {
  try {
    await navigator.clipboard.writeText(widgetSnippet.value)
    ElMessage.success(t('agentDlg.copiedSnippet'))
  } catch {
    ElMessage.warning(t('agentDlg.copyFailManual'))
  }
}

function previewWidget() {
  if (widgetKey.value) window.open(`/widget?key=${encodeURIComponent(widgetKey.value)}`, '_blank')
}

async function copyKey() {
  try {
    await navigator.clipboard.writeText(createdKey.value)
    ElMessage.success(t('agentDlg.copied'))
  } catch {
    ElMessage.warning(t('agentDlg.copyFailManual'))
  }
}

async function toggleKey(row: any) {
  await http.put(`/agent/${agentId}/api-keys/${row.id}`, {
    status: row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  })
  await loadApiKeys()
}

async function removeKey(row: any) {
  await ElMessageBox.confirm(`${t('agentDlg.deleteKeyPrefix')}${row.name}${t('agentDlg.deleteKeyMid')}`, t('agentDlg.deleteKeyTitle'),
    { type: 'warning', confirmButtonText: t('agentDlg.delete'), cancelButtonText: t('agentDlg.cancel') })
  await http.delete(`/agent/${agentId}/api-keys/${row.id}`)
  ElMessage.success(t('agentDlg.deleted'))
  await loadApiKeys()
}

const integratedAgentId = ref(0)

function modelLabel(m: any) {
  const name = m.displayName || m.modelName
  const c = Number(m.contextWindow || 0)
  return c > 0 ? `${name}（${c >= 1048576 ? (c / 1048576) + 'M' : (c / 1024) + 'K'}${t('agentMsg.contextSuffix')}` : name
}

function openOptimize() {
  if (!form.systemPrompt || !form.systemPrompt.trim()) {
    ElMessage.warning(t('agentDlg.promptRequired')); return
  }
  optimizeResult.value = ''
  optimizeUsage.value = null
  optimizeInstruction.value = ''
  optimizeVisible.value = true
}

async function runOptimize() {
  if (!form.systemPrompt.trim()) { ElMessage.warning(t('agentDlg.promptRequired')); return }
  optimizing.value = true
  try {
    const res = await http.post('/agent/prompt/optimize', {
      prompt: form.systemPrompt,
      name: form.name,
      description: form.description,
      modelName: form.modelName,
      instruction: optimizeInstruction.value,
      style: optimizeStyle.value
    }, { timeout: 120000 }) as any
    optimizeResult.value = res.optimizedPrompt || ''
    optimizeUsage.value = res.usage || null
    optimizeModel.value = res.modelName || ''
    ElMessage.success(t('agentDlg.optimizeDone'))
  } finally {
    optimizing.value = false
  }
}

function applyOptimized(mode: 'replace' | 'append') {
  if (!optimizeResult.value) return
  form.systemPrompt = mode === 'replace'
    ? optimizeResult.value
    : (form.systemPrompt ? form.systemPrompt + '\n\n' + optimizeResult.value : optimizeResult.value)
  optimizeVisible.value = false
  ElMessage.success(mode === 'replace' ? t('agentDlg.replaced') : t('agentDlg.appended'))
}

function openTemplatePicker() {
  templateId.value = 0
  templateVisible.value = true
}

async function applyTemplate() {
  if (!selectedTemplate.value) return
  if (form.systemPrompt.trim()) {
    try {
      await ElMessageBox.confirm(t('agentDlg.overwrite'), t('agentDlg.overwrite'), { type: 'warning' })
    } catch { return }
  }
  form.systemPrompt = selectedTemplate.value.content || ''
  templateVisible.value = false
  ElMessage.success(t('agentDlg.templateInserted'))
}

function openPicker(type: RefType) {
  picker.type = type
  picker.selected = []
  picker.visible = true
}

function goCreate(kind: 'skill' | 'tool' | 'mcp') {
  picker.visible = false
  const path = kind === 'skill' ? '/skills?create=1'
    : kind === 'tool' ? '/tools?create=1' : '/mcp-servers?create=1'
  router.push(path)
}

async function openWorkflow() {
  const data = await http.get(`/agent/${agentId}/workflow`) as any
  workflowConfigured.value = !!data?.configured
  workflowForm.name = data?.name || `${form.name}${t('agentMsg.workflowNameSuffix')}`
  workflowForm.status = data?.status || 'ENABLED'
  workflowPublishedVersion.value = data?.publishedVersion || ''
  workflowHasUnpublishedChanges.value = !!data?.hasUnpublishedChanges
  workflowForm.nodes = (data?.definition?.nodes || []).map((n: any) => ({
    ...n, ...(n.type === 'LOOP' ? { itemsText: JSON.stringify(n.items || []) } : {})
  }))
  workflowForm.edges = (data?.definition?.edges || []).map((e: any) => ({ ...e }))
  ensureWorkflowPositions()
  wfMode.value = 'list'
  wfSelected.value = ''
  if (!workflowForm.nodes.length) {
    workflowForm.nodes = [
      { id: 'start', type: 'START', name: t('agentMsg.defaultStart'), next: 'end' },
      { id: 'end', type: 'END', name: t('agentMsg.defaultEnd') }
    ]
    ensureWorkflowPositions()
  }
  resetWorkflowHistory()
  workflowRunInput.value = ''
  workflowRunResult.value = null
  workflowVisible.value = true
}

function wfTypeText(type: string) {
  return ({ START: t('wf.typeStart'), END: t('wf.typeEnd'), LLM: t('wf.typeLlm'), AGENT: t('wf.typeAgent'), HANDOFF: t('wf.typeHandoff'), STATE: t('wf.typeState'), TOOL: t('wf.typeTool'), KNOWLEDGE: t('wf.typeKnowledge'), CONDITION: t('wf.typeCondition'), APPROVAL: t('wf.typeApproval'), PARALLEL: t('wf.typeParallel'), LOOP: t('wf.typeLoop'), JOIN: t('wf.typeJoin') } as any)[type] || type
}

function wfTagType(type: string) {
  return ({ START: 'info', END: 'info', LLM: 'success', AGENT: 'warning', HANDOFF: 'warning', STATE: 'info', CONDITION: 'primary', APPROVAL: 'danger' } as any)[type] || 'info'
}

function wfTargets(currentId: string) {
  return workflowForm.nodes.filter((n: any) => n.id !== currentId)
}

function wfBranchTargets(currentId: string) {
  return workflowForm.nodes.filter((n: any) => n.id !== currentId
    && !['START', 'END', 'JOIN', 'PARALLEL', 'LOOP'].includes(n.type))
}

function ensureWorkflowPositions() {
  workflowForm.nodes.forEach((n: any, i: number) => {
    if (!n.position || typeof n.position.x !== 'number' || typeof n.position.y !== 'number') {
      n.position = { x: 60 + (i % 3) * 230, y: 40 + Math.floor(i / 3) * 120 }
    }
  })
}

// ---------- 画布右侧节点配置面板 ----------
function openNodePanel(n: any) {
  if (wfNodeEditBefore) closeNodePanel()
  wfSelected.value = n.id
  wfSelectedEdge.value = null
  wfNodeEditBefore = snapshotWorkflow()
  wfNodePanel.value = true
}

function closeNodePanel() {
  if (wfNodeEditBefore) {
    commitWorkflowHistory(wfNodeEditBefore)
    wfNodeEditBefore = ''
  }
  wfNodePanel.value = false
}

function openNodeInList() {
  const id = wfSelected.value
  closeNodePanel()
  editWorkflowNode(id)
}

function removeSelectedNode() {
  const index = workflowForm.nodes.findIndex((x: any) => x.id === wfSelected.value)
  wfNodePanel.value = false
  wfNodeEditBefore = ''
  if (index >= 0) removeWorkflowNode(index)
}

// ---------- 画布撤销/重做 ----------
function snapshotWorkflow() {
  return JSON.stringify({ nodes: workflowForm.nodes, edges: workflowForm.edges })
}

function resetWorkflowHistory() {
  wfHistory.value = [snapshotWorkflow()]
  wfHistoryIndex.value = 0
  wfSelectedEdge.value = null
  wfLinkDrag.value = null
}

function commitWorkflowHistory(before: string) {
  const after = snapshotWorkflow()
  if (after === before) return
  if (wfHistory.value[wfHistoryIndex.value] === before) {
    wfHistory.value.splice(wfHistoryIndex.value + 1)
  } else {
    wfHistory.value = [before]
    wfHistoryIndex.value = 0
  }
  wfHistory.value.push(after)
  if (wfHistory.value.length > 60) wfHistory.value.shift()
  wfHistoryIndex.value = wfHistory.value.length - 1
}

function restoreWorkflow(snapshot: string) {
  const data = JSON.parse(snapshot)
  workflowForm.nodes = data.nodes || []
  workflowForm.edges = data.edges || []
  ensureWorkflowPositions()
  wfSelected.value = ''
  wfSelectedEdge.value = null
  wfNodePanel.value = false
  wfNodeEditBefore = ''
  wfRewireMode.value = false
  wfMultiSelected.value = []
}

function undoWorkflow() {
  if (wfHistoryIndex.value <= 0) return
  wfHistoryIndex.value -= 1
  restoreWorkflow(wfHistory.value[wfHistoryIndex.value])
}

function redoWorkflow() {
  if (wfHistoryIndex.value >= wfHistory.value.length - 1) return
  wfHistoryIndex.value += 1
  restoreWorkflow(wfHistory.value[wfHistoryIndex.value])
}

function onWorkflowKeydown(e: KeyboardEvent) {
  if (!workflowVisible.value) return
  const el = e.target as HTMLElement | null
  if (el && /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName)) return
  const meta = e.metaKey || e.ctrlKey
  if (meta && e.key.toLowerCase() === 'c' && wfMode.value === 'canvas') {
    e.preventDefault()
    copySelectedNodes()
    return
  }
  if (meta && e.key.toLowerCase() === 'v' && wfMode.value === 'canvas') {
    e.preventDefault()
    pasteNodes()
    return
  }
  if (meta && e.key.toLowerCase() === 'z') {
    e.preventDefault()
    if (e.shiftKey) redoWorkflow()
    else undoWorkflow()
    return
  }
  if (e.key === 'Escape' && wfRewireMode.value) {
    e.preventDefault()
    cancelRewire()
    return
  }
  if ((e.key === 'Delete' || e.key === 'Backspace') && wfMultiSelected.value.length > 1) {
    e.preventDefault()
    removeMultiSelected()
    return
  }
  if ((e.key === 'Delete' || e.key === 'Backspace') && wfSelectedEdge.value !== null) {
    e.preventDefault()
    deleteSelectedEdge()
  }
}

// ---------- 画布交互：节点面板拖入 / 端口连线 / 选中连线 ----------
function onPaletteDragStart(ev: DragEvent, type: string) {
  wfDraggingType.value = type
  if (ev.dataTransfer) {
    ev.dataTransfer.setData('text/plain', type)
    ev.dataTransfer.effectAllowed = 'copy'
  }
}

function onCanvasDrop(ev: DragEvent) {
  wfDropActive.value = false
  const type = wfDraggingType.value || ev.dataTransfer?.getData('text/plain') || ''
  wfDraggingType.value = ''
  if (!type) return
  const canvas = wfCanvasEl.value?.querySelector('.wf-canvas') as HTMLElement | null
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  const x = Math.max(0, (ev.clientX - rect.left) / wfZoom.value - 95)
  const y = Math.max(0, (ev.clientY - rect.top) / wfZoom.value - 28)
  insertWorkflowNode(type, { x: Math.round(x), y: Math.round(y) })
}

function startLinkDrag(ev: MouseEvent, n: any) {
  ensureWorkflowPositions()
  const canvas = wfCanvasEl.value?.querySelector('.wf-canvas') as HTMLElement | null
  if (!canvas) return
  const x1 = n.position.x + 190
  const y1 = n.position.y + 34
  wfLinkDrag.value = { from: n.id, x1, y1, x2: x1, y2: y1 }
  const toCanvas = (e: MouseEvent) => {
    const rect = canvas.getBoundingClientRect()
    return { x: (e.clientX - rect.left) / wfZoom.value, y: (e.clientY - rect.top) / wfZoom.value }
  }
  const move = (e: MouseEvent) => {
    if (!wfLinkDrag.value) return
    const point = toCanvas(e)
    wfLinkDrag.value.x2 = point.x
    wfLinkDrag.value.y2 = point.y
  }
  const up = (e: MouseEvent) => {
    window.removeEventListener('mousemove', move)
    window.removeEventListener('mouseup', up)
    const fromId = wfLinkDrag.value?.from
    wfLinkDrag.value = null
    if (!fromId) return
    const target = (document.elementFromPoint(e.clientX, e.clientY) as HTMLElement | null)
      ?.closest('.wf-canvas-node') as HTMLElement | null
    const toId = target?.dataset.nodeId
    if (toId && toId !== fromId) connectNodes(fromId, toId)
  }
  window.addEventListener('mousemove', move)
  window.addEventListener('mouseup', up)
  ev.preventDefault()
}

function connectNodes(fromId: string, toId: string) {
  const from = workflowForm.nodes.find((x: any) => x.id === fromId)
  if (!from) return
  if (workflowForm.edges.some((e: any) => e.from === fromId && e.to === toId)) {
    ElMessage.info(t('agentMsg.edgeExists'))
    return
  }
  // 顺序/条件/分支已经自动画出的连线不需要再手动加，避免"加了但看不到"
  if (wfEdges.value.some((e: any) => !e.explicit && e.id === `derived-${fromId}-${toId}`)) {
    ElMessage.info(t('agentMsg.edgeDerivedExists'))
    return
  }
  const before = snapshotWorkflow()
  workflowForm.edges.push({ from: fromId, to: toId, label: from.type === 'CONDITION' ? 'true' : '' })
  commitWorkflowHistory(before)
  ElMessage.success(t('agentMsg.edgeAdded'))
}

function startRewireEdge() {
  if (wfSelectedEdge.value === null) return
  wfRewireMode.value = true
  wfSelected.value = ''
  ElMessage.info(t('wf.rewireHint'))
}

function finishRewire(target: any) {
  const index = wfSelectedEdge.value
  const edge = index === null ? null : workflowForm.edges[index]
  wfRewireMode.value = false
  if (index === null || !edge) return
  if (edge.from === target.id) {
    ElMessage.warning(t('wf.rewireSelf'))
    return
  }
  if (workflowForm.edges.some((e: any, i: number) => i !== index && e.from === edge.from && e.to === target.id)) {
    ElMessage.info(t('agentMsg.edgeExists'))
    return
  }
  const before = snapshotWorkflow()
  workflowForm.edges[index].to = target.id
  wfSelectedEdge.value = null
  commitWorkflowHistory(before)
  ElMessage.success(t('wf.edgeRewired'))
}

function cancelRewire() {
  if (!wfRewireMode.value) return false
  wfRewireMode.value = false
  ElMessage.info(t('wf.rewireCancelled'))
  return true
}

function selectEdge(edge: any) {
  wfRewireMode.value = false
  if (!edge.explicit) {
    wfSelectedEdge.value = null
    ElMessage.info(t('wf.derivedEdgeHint'))
    return
  }
  wfSelectedEdge.value = edge.index
}

function deleteSelectedEdge() {
  const index = wfSelectedEdge.value
  wfRewireMode.value = false
  if (index === null) return
  const before = snapshotWorkflow()
  workflowForm.edges.splice(index, 1)
  wfSelectedEdge.value = null
  commitWorkflowHistory(before)
}

function removeGraphEdge(index: number) {
  const before = snapshotWorkflow()
  workflowForm.edges.splice(index, 1)
  wfSelectedEdge.value = null
  commitWorkflowHistory(before)
}

function fitCanvas() {
  const wrap = wfCanvasEl.value
  if (!wrap) return
  const scale = Math.min(1.2, Math.max(0.5, Math.min(
    (wrap.clientWidth - 24) / wfCanvasSize.value.w,
    (wrap.clientHeight - 24) / wfCanvasSize.value.h
  )))
  wfZoom.value = Number(scale.toFixed(2))
  wrap.scrollLeft = 0
  wrap.scrollTop = 0
}

function nodeX(n: any) {
  return n.position?.x ?? 0
}

function nodeY(n: any) {
  return n.position?.y ?? 0
}

const wfCanvasSize = computed(() => {
  let maxX = 640
  let maxY = 380
  for (const n of workflowForm.nodes) {
    maxX = Math.max(maxX, nodeX(n) + 260)
    maxY = Math.max(maxY, nodeY(n) + 140)
  }
  return { w: maxX, h: maxY }
})

const wfEdges = computed(() => {
  const nodes = workflowForm.nodes
  const map = new Map(nodes.map((n: any) => [n.id, n]))
  const edges: any[] = []
  // 同一对节点只画一条线；条件 true/false 指向同一节点时合并成 "true/false"
  const seen = new Map<string, any>()
  const add = (from: any, to: any, color: string, index?: number, forceLabel?: string) => {
    if (!from || !to) return
    const key = `${from.id}->${to.id}`
    const label = index != null
      ? String(workflowForm.edges[index]?.label || '')
      : (forceLabel || (color === '#67c23a' ? 'true' : color === '#f56c6c' ? 'false' : ''))
    const previous = seen.get(key)
    if (previous) {
      if (label && !previous.label) previous.label = label
      else if (label && previous.label && !previous.label.split('/').includes(label)) {
        previous.label = `${previous.label}/${label}`
        previous.color = '#e6a23c'
      }
      return
    }
    const x1 = nodeX(from) + 190
    const y1 = nodeY(from) + 34
    const x2 = nodeX(to)
    const y2 = nodeY(to) + 34
    const edge = {
      id: index != null ? `edge-${index}` : `derived-${from.id}-${to.id}`,
      index: index ?? null,
      explicit: index != null,
      color,
      label,
      labelX: (x1 + x2) / 2,
      labelY: (y1 + y2) / 2 - 6,
      d: `M ${x1} ${y1} C ${x1 + 60} ${y1}, ${x2 - 60} ${y2}, ${x2} ${y2}`
    }
    seen.set(key, edge)
    edges.push(edge)
  }
  // 手动连线优先（可选中删除）；其余按节点顺序/条件/并行分支推导，属于只读
  workflowForm.edges.forEach((e: any, i: number) => {
    const color = e.label === 'false' ? '#f56c6c' : e.label === 'true' ? '#67c23a' : '#409eff'
    add(map.get(e.from), map.get(e.to), color, i)
  })
  for (const n of nodes) {
    if (n.type === 'END') continue
    if (n.type === 'CONDITION') {
      add(n, map.get(n.trueNext), '#67c23a', undefined, 'true')
      add(n, map.get(n.falseNext), '#f56c6c', undefined, 'false')
    } else if (n.type === 'PARALLEL') {
      const branches: string[] = Array.isArray(n.branches) ? n.branches : []
      const join = map.get(n.joinNode)
      for (const b of branches) {
        const bn = map.get(b)
        add(n, bn, '#e6a23c')
        add(bn, join, '#e6a23c')
      }
      if (!branches.length) add(n, join, '#e6a23c')
    } else {
      add(n, map.get(n.next), '#909399')
    }
  }
  return edges
})

const wfLinkPath = computed(() => {
  const d = wfLinkDrag.value
  if (!d) return ''
  return `M ${d.x1} ${d.y1} C ${d.x1 + 60} ${d.y1}, ${d.x2 - 60} ${d.y2}, ${d.x2} ${d.y2}`
})

function wfRunClass(n: any) {
  const status = String(wfRunNodeStatus.value[n.id] || '')
  return {
    'run-success': status === 'SUCCESS',
    'run-failed': ['FAILED', 'ERROR', 'REJECTED'].includes(status),
    'run-running': status === 'RUNNING',
    'run-waiting': ['WAITING', 'SKIPPED'].includes(status)
  }
}

const wfRunNodeStatus = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const r of (workflowRunResult.value?.nodeRuns || [])) {
    if (r?.nodeId) map[String(r.nodeId)] = String(r.status || '')
  }
  return map
})

function toggleLinkMode() {
  wfLinkMode.value = !wfLinkMode.value
  wfLinkFrom.value = ''
}

let wfDragMoved = false
let wfDragEndedAt = 0

function onCanvasNodeClick(n: any, ev?: MouseEvent) {
  // 拖拽结束后的 click 不要重置多选/触发面板
  if (Date.now() - wfDragEndedAt < 300) {
    wfDragEndedAt = 0
    return
  }
  if (wfRewireMode.value) {
    finishRewire(n)
    return
  }
  if (ev?.shiftKey) {
    const i = wfMultiSelected.value.indexOf(n.id)
    if (i >= 0) wfMultiSelected.value.splice(i, 1)
    else wfMultiSelected.value.push(n.id)
    wfSelected.value = n.id
    return
  }
  wfMultiSelected.value = []
  wfSelected.value = n.id
  if (!wfLinkMode.value) {
    if (wfNodePanel.value) openNodePanel(n)
    return
  }
  if (!wfLinkFrom.value) {
    wfLinkFrom.value = n.id
    ElMessage.info(`${t('agentMsg.startSelected')}${n.name}${t('agentMsg.clickEnd')}`)
    return
  }
  if (wfLinkFrom.value === n.id) {
    wfLinkFrom.value = ''
    return
  }
  const from = workflowForm.nodes.find((x: any) => x.id === wfLinkFrom.value)
  const fromType = String(from?.type || '')
  const label = fromType === 'CONDITION' ? 'true' : ''
  const exists = workflowForm.edges.some((e: any) => e.from === wfLinkFrom.value && e.to === n.id)
  if (!exists) workflowForm.edges.push({ from: wfLinkFrom.value, to: n.id, label })
  wfLinkFrom.value = ''
  ElMessage.success(t('agentMsg.edgeAdded'))
}

function zoomCanvas(delta: number) {
  wfZoom.value = Math.min(1.6, Math.max(0.5, Number((wfZoom.value + delta).toFixed(2))))
}

function autoLayoutWorkflow() {
  const before = snapshotWorkflow()
  workflowForm.nodes.forEach((n: any, i: number) => {
    n.position = { x: 60 + (i % 3) * 240, y: 40 + Math.floor(i / 3) * 120 }
  })
  commitWorkflowHistory(before)
}

function editWorkflowNode(id: string) {
  wfSelected.value = id
  wfMode.value = 'list'
}

function startBoxSelect(ev: MouseEvent) {
  const canvas = wfCanvasEl.value?.querySelector('.wf-canvas') as HTMLElement | null
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  const toCanvas = (e: MouseEvent) => ({
    x: (e.clientX - rect.left) / wfZoom.value,
    y: (e.clientY - rect.top) / wfZoom.value
  })
  const start = toCanvas(ev)
  wfBoxSelect.value = { x1: start.x, y1: start.y, x2: start.x, y2: start.y }
  const move = (e: MouseEvent) => {
    if (!wfBoxSelect.value) return
    const point = toCanvas(e)
    wfBoxSelect.value.x2 = point.x
    wfBoxSelect.value.y2 = point.y
  }
  const up = () => {
    window.removeEventListener('mousemove', move)
    window.removeEventListener('mouseup', up)
    const box = wfBoxSelect.value
    wfBoxSelect.value = null
    if (!box) return
    const x1 = Math.min(box.x1, box.x2)
    const x2 = Math.max(box.x1, box.x2)
    const y1 = Math.min(box.y1, box.y2)
    const y2 = Math.max(box.y1, box.y2)
    if (x2 - x1 < 4 && y2 - y1 < 4) return
    const hits = workflowForm.nodes.filter((n: any) => {
      const nx1 = nodeX(n)
      const ny1 = nodeY(n)
      return nx1 < x2 && nx1 + 190 > x1 && ny1 < y2 && ny1 + 66 > y1
    }).map((n: any) => n.id)
    if (!hits.length) return
    wfMultiSelected.value = Array.from(new Set([...wfMultiSelected.value, ...hits]))
    wfSelected.value = ''
    wfNodePanel.value = false
  }
  window.addEventListener('mousemove', move)
  window.addEventListener('mouseup', up)
  ev.preventDefault()
}

function startCanvasPan(ev: MouseEvent) {
  const el = wfCanvasEl.value
  if (!el) return
  if (cancelRewire()) return
  if (ev.shiftKey) {
    startBoxSelect(ev)
    return
  }
  const startX = ev.clientX
  const startY = ev.clientY
  const sl = el.scrollLeft
  const st = el.scrollTop
  const move = (e: MouseEvent) => {
    el.scrollLeft = sl - (e.clientX - startX)
    el.scrollTop = st - (e.clientY - startY)
  }
  const up = () => {
    window.removeEventListener('mousemove', move)
    window.removeEventListener('mouseup', up)
  }
  window.addEventListener('mousemove', move)
  window.addEventListener('mouseup', up)
}

async function openWorkflowTemplates() {
  wfTemplates.value = await http.get('/workflow/templates') as any
  wfTemplatesVisible.value = true
}

function applyWorkflowTemplate(t: any) {
  const before = snapshotWorkflow()
  let def: any = t?.definition
  if (typeof def === 'string') {
    try { def = JSON.parse(def) } catch { def = {} }
  }
  const nodes = def?.nodes || []
  if (!nodes.length) { ElMessage.warning(t('agentMsg.templateNoNodes')); return }
  workflowForm.nodes = nodes.map((n: any) => ({
    ...n, ...(n.type === 'LOOP' ? { itemsText: JSON.stringify(n.items || []) } : {})
  }))
  ensureWorkflowPositions()
  wfMode.value = 'list'
  wfSelected.value = ''
  wfTemplatesVisible.value = false
  wfSelectedEdge.value = null
  commitWorkflowHistory(before)
  ElMessage.success(t('agentMsg.templateLoaded'))
}

function copySelectedNodes() {
  const ids = wfMultiSelected.value.length
    ? [...wfMultiSelected.value]
    : (wfSelected.value ? [wfSelected.value] : [])
  const nodes = workflowForm.nodes.filter((n: any) => ids.includes(n.id) && !['START', 'END'].includes(n.type))
  if (!nodes.length) {
    ElMessage.warning(t('wf.copyNone'))
    return
  }
  const idSet = new Set(nodes.map((n: any) => n.id))
  wfClipboard = {
    nodes: JSON.parse(JSON.stringify(nodes)),
    edges: JSON.parse(JSON.stringify(workflowForm.edges.filter((e: any) => idSet.has(e.from) && idSet.has(e.to))))
  }
  wfClipboardSize.value = nodes.length
  ElMessage.success(t('wf.copiedNodes'))
}

function pasteNodes() {
  if (!wfClipboard || !wfClipboard.nodes.length) {
    ElMessage.warning(t('wf.clipboardEmpty'))
    return
  }
  const before = snapshotWorkflow()
  const used = new Set(workflowForm.nodes.map((n: any) => n.id))
  const idMap = new Map<string, string>()
  const newId = () => {
    let id = ''
    do {
      id = `n${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`
    } while (used.has(id))
    used.add(id)
    return id
  }
  for (const n of wfClipboard.nodes) idMap.set(n.id, newId())

  const clones = wfClipboard.nodes.map((n: any, i: number) => {
    const copy = JSON.parse(JSON.stringify(n))
    copy.id = idMap.get(n.id)
    copy.name = `${n.name} ${t('wf.copySuffix')}`
    copy.position = avoidWorkflowOverlap({
      x: (n.position?.x ?? 60) + 40 + i * 16,
      y: (n.position?.y ?? 40) + 40 + i * 16
    })
    // 指向副本内部的引用做重映射；指向外部的引用收敛到 end/空，避免粘贴出意外连线或环
    copy.next = idMap.get(copy.next) || 'end'
    copy.trueNext = idMap.get(copy.trueNext) || 'end'
    copy.falseNext = idMap.get(copy.falseNext) || 'end'
    copy.joinNode = idMap.get(copy.joinNode) || ''
    copy.bodyNode = idMap.get(copy.bodyNode) || ''
    copy.loopEnd = idMap.get(copy.loopEnd) || ''
    if (Array.isArray(copy.branches)) copy.branches = copy.branches.map((b: string) => idMap.get(b)).filter(Boolean)
    return copy
  })

  const endIndex = workflowForm.nodes.findIndex((n: any) => n.type === 'END')
  if (endIndex >= 0) workflowForm.nodes.splice(endIndex, 0, ...clones)
  else workflowForm.nodes.push(...clones)
  for (const edge of wfClipboard.edges) {
    workflowForm.edges.push({ ...edge, from: idMap.get(edge.from), to: idMap.get(edge.to) })
  }
  wfSelected.value = ''
  wfSelectedEdge.value = null
  wfMultiSelected.value = clones.map((c: any) => c.id)
  commitWorkflowHistory(before)
  ElMessage.success(t('wf.pastedNodes'))
}

function removeMultiSelected() {
  const ids = new Set(wfMultiSelected.value)
  const targets = workflowForm.nodes.filter((x: any) => ids.has(x.id) && !['START', 'END'].includes(x.type))
  if (!targets.length) {
    ElMessage.warning(t('wf.multiNoneRemovable'))
    return
  }
  const before = snapshotWorkflow()
  workflowForm.nodes = workflowForm.nodes.filter((x: any) => !targets.some((r: any) => r.id === x.id))
  wfMultiSelected.value = []
  wfSelected.value = ''
  wfSelectedEdge.value = null
  rewireWorkflow()
  commitWorkflowHistory(before)
}

function startNodeDrag(ev: MouseEvent, n: any) {
  ensureWorkflowPositions()
  const before = snapshotWorkflow()
  wfSelected.value = n.id
  if (!wfRewireMode.value) wfSelectedEdge.value = null
  const grouped = wfMultiSelected.value.includes(n.id) && wfMultiSelected.value.length > 1
  // Shift+点击是多选操作，不要在这里清空已选集合
  if (!grouped && !ev.shiftKey) wfMultiSelected.value = []
  const ids = grouped ? [...wfMultiSelected.value] : [n.id]
  const starts = new Map<string, { x: number; y: number }>()
  for (const id of ids) {
    const node = workflowForm.nodes.find((x: any) => x.id === id)
    if (node) starts.set(id, { x: node.position.x, y: node.position.y })
  }
  const startX = ev.clientX
  const startY = ev.clientY
  wfDragMoved = false
  const move = (e: MouseEvent) => {
    const dx = e.clientX - startX
    const dy = e.clientY - startY
    if (Math.abs(dx) > 3 || Math.abs(dy) > 3) wfDragMoved = true
    for (const [id, start] of starts) {
      const node = workflowForm.nodes.find((x: any) => x.id === id)
      if (!node) continue
      node.position.x = Math.max(0, start.x + dx)
      node.position.y = Math.max(0, start.y + dy)
    }
  }
  const up = () => {
    window.removeEventListener('mousemove', move)
    window.removeEventListener('mouseup', up)
    if (wfDragMoved) wfDragEndedAt = Date.now()
    wfDragMoved = false
    commitWorkflowHistory(before)
  }
  window.addEventListener('mousemove', move)
  window.addEventListener('mouseup', up)
  ev.preventDefault()
}

function compareVersion(row: any) {
  let oldNodes: any[] = []
  try {
    oldNodes = JSON.parse(row.definition || '{}')?.nodes || []
  } catch {
    oldNodes = []
  }
  const current = workflowForm.nodes
  const oldMap = new Map(oldNodes.map((n: any) => [n.id, n]))
  const curMap = new Map(current.map((n: any) => [n.id, n]))
  const fields = ['type', 'name', 'prompt', 'modelName', 'agentId', 'toolId', 'argsTemplate',
    'knowledgeBaseId', 'mode', 'topK', 'queryTemplate', 'conditionType', 'conditionValue',
    'trueNext', 'falseNext', 'next', 'title', 'requiredRole', 'timeoutMinutes', 'branches', 'joinNode', 'strategy']
  const rows: any[] = []
  for (const n of current) {
    const old = oldMap.get(n.id)
    if (!old) {
      rows.push({ id: n.id, name: n.name, change: t('agentMsg.diffAdded'), detail: t('agentMsg.newNode') })
      continue
    }
    const changed = fields.filter(f => JSON.stringify(n[f] ?? null) !== JSON.stringify(old[f] ?? null))
    if (changed.length) rows.push({ id: n.id, name: n.name, change: t('agentMsg.diffModified'), detail: changed.join(', ') })
  }
  for (const o of oldNodes) {
    if (!curMap.has(o.id)) rows.push({ id: o.id, name: o.name, change: t('agentMsg.diffDeleted'), detail: t('agentMsg.removedDraft') })
  }
  wfDiffRows.value = rows
  wfDiffTitle.value = `${t('agentMsg.diffTitlePrefix')}${row.version}${t('agentMsg.diffTitleMid')}`
  wfDiffVisible.value = true
}

function diffTag(change: string) {
  if (change === t('agentMsg.diffAdded')) return 'success'
  if (change === t('agentMsg.diffDeleted')) return 'danger'
  return 'warning'
}

function workflowNodeType(id: string) {
  return workflowForm.nodes.find((n: any) => n.id === id)?.type || ''
}

function addGraphEdge() {
  workflowForm.edges.push({ from: 'start', to: 'end', label: '' })
}

function buildWorkflowNode(type: string): any {
  const id = `n${Date.now().toString(36)}${Math.floor(Math.random() * 100)}`
  const node: any = { id, type, name: wfTypeText(type), next: 'end' }
  if (type === 'APPROVAL') node.title = t('agentMsg.approvalTitle')
  if (type === 'LLM') node.prompt = ''
  if (type === 'HANDOFF') { node.inputTemplate = '{{input}}'; node.returnRounds = 1 }
  if (type === 'STATE') { node.stateKey = ''; node.valueTemplate = '{{last}}' }
  if (type === 'TOOL') node.argsTemplate = '{}'
  if (type === 'KNOWLEDGE') { node.mode = 'hybrid'; node.topK = 5; node.queryTemplate = '' }
  if (type === 'APPROVAL') { node.requiredRole = ''; node.timeoutMinutes = 0 }
  if (type === 'PARALLEL') { node.branches = []; node.joinNode = ''; node.next = ''; node.strategy = 'ALL_OR_NOTHING' }
  if (type === 'LOOP') { node.bodyNode = ''; node.loopEnd = ''; node.itemsText = '["A","B"]'; node.maxIterations = 10; node.requiredRole = ''; node.timeoutMinutes = 0 }
  if (['LLM', 'AGENT', 'HANDOFF', 'STATE', 'TOOL', 'KNOWLEDGE'].includes(type)) { node.retryTimes = 0; node.onError = 'FAIL' }
  if (type === 'JOIN') node.next = 'end'
  if (type === 'CONDITION') {
    node.conditionType = 'contains'
    node.conditionValue = ''
    node.trueNext = 'end'
    node.falseNext = 'end'
  }
  return node
}

/** 找一个不与现有节点重叠的空位（网格扫描） */
function freeWorkflowPosition() {
  const taken = (x: number, y: number) => workflowForm.nodes.some((n: any) =>
    Math.abs(nodeX(n) - x) < 210 && Math.abs(nodeY(n) - y) < 100)
  // 先按 3 列向下排，保证新节点落在可视区域内，避免加到画布右侧看不见
  for (let row = 0; row < 30; row++) {
    for (let col = 0; col < 3; col++) {
      const x = 60 + col * 230
      const y = 40 + row * 110
      if (!taken(x, y)) return { x, y }
    }
  }
  return { x: 60, y: 40 + workflowForm.nodes.length * 110 }
}

/** 拖入位置若与其他节点重叠，向下顺延，避免卡片互相盖住 */
function avoidWorkflowOverlap(position: { x: number; y: number }) {
  const overlaps = (x: number, y: number) => workflowForm.nodes.some((n: any) =>
    Math.abs(nodeX(n) - x) < 210 && Math.abs(nodeY(n) - y) < 100)
  let { x, y } = position
  let guard = 0
  while (overlaps(x, y) && guard++ < 30) y += 110
  return { x, y }
}

/** 新增节点：列表模式按钮与画布拖入共用；position 为空时自动找空位 */
function insertWorkflowNode(type: string, position?: { x: number; y: number }) {
  const before = snapshotWorkflow()
  const node = buildWorkflowNode(type)
  position = position ? avoidWorkflowOverlap(position) : freeWorkflowPosition()
  node.position = position
  const endIndex = workflowForm.nodes.findIndex((n: any) => n.type === 'END')
  if (endIndex >= 0) workflowForm.nodes.splice(endIndex, 0, node)
  else workflowForm.nodes.push(node)
  ensureWorkflowPositions()
  if (position) node.position = position
  rewireWorkflow()
  wfSelected.value = node.id
  wfSelectedEdge.value = null
  commitWorkflowHistory(before)
  if (wfMode.value === 'canvas') openNodePanel(node)
  return node
}

function addWorkflowNode() {
  insertWorkflowNode(wfAddType.value)
}

function addWorkflowNodeOfType(type: string) {
  insertWorkflowNode(type)
}

function removeWorkflowNode(index: number) {
  const before = snapshotWorkflow()
  workflowForm.nodes.splice(index, 1)
  rewireWorkflow()
  wfSelectedEdge.value = null
  commitWorkflowHistory(before)
}

function moveWorkflowNode(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= workflowForm.nodes.length) return
  const [node] = workflowForm.nodes.splice(index, 1)
  workflowForm.nodes.splice(target, 0, node)
  rewireWorkflow()
}

function rewireWorkflow() {
  const nodes = workflowForm.nodes
  for (let i = 0; i < nodes.length; i++) {
    const n = nodes[i]
    if (n.type === 'END') continue
    const nextId = nodes[i + 1]?.id || 'end'
    if (n.type === 'CONDITION') {
      const ids = new Set(nodes.map((x: any) => x.id))
      if (!n.trueNext || !ids.has(n.trueNext)) n.trueNext = nextId
      if (!n.falseNext || !ids.has(n.falseNext)) n.falseNext = nextId
    } else if (n.type === 'PARALLEL') {
      const ids = new Set(nodes.map((x: any) => x.id))
      if (!n.joinNode || !ids.has(n.joinNode)) {
        n.joinNode = nodes.find((x: any) => x.type === 'JOIN')?.id || ''
      }
      n.next = n.joinNode || n.next
    } else if (!n.next || !nodes.some((x: any) => x.id === n.next)) {
      n.next = nextId
    }
  }
}

async function saveWorkflow() {
  if (!workflowForm.nodes.some((n: any) => n.type === 'START')
      || !workflowForm.nodes.some((n: any) => n.type === 'END')) {
    ElMessage.warning(t('agentMsg.workflowRequired'))
    return
  }
  workflowSaving.value = true
  try {
    await http.put(`/agent/${agentId}/workflow`, {
      name: workflowForm.name,
      status: workflowForm.status,
      definition: {
        nodes: workflowForm.nodes.map((n: any) => {
          if (n.type !== 'LOOP') return n
          let items = n.items
          try { items = JSON.parse(n.itemsText || '[]') } catch {
            items = String(n.itemsText || '').split(/[\n,，]/).map((x: string) => x.trim()).filter(Boolean)
          }
          const { itemsText, ...rest } = n
          return { ...rest, items }
        }),
        edges: workflowForm.edges.filter((e: any) => e.from && e.to)
      }
    })
    workflowConfigured.value = true
    const fresh = await http.get(`/agent/${agentId}/workflow`) as any
    workflowPublishedVersion.value = fresh?.publishedVersion || ''
    workflowHasUnpublishedChanges.value = !!fresh?.hasUnpublishedChanges
    ElMessage.success(t('agentMsg.workflowSaved'))
    return true
  } finally {
    workflowSaving.value = false
  }
  return false
}

async function publishWorkflow() {
  workflowPublishing.value = true
  try {
    const saved = await saveWorkflow()
    if (!saved) return
    const res = await http.post(`/agent/${agentId}/workflow/publish`) as any
    workflowPublishedVersion.value = res.version
    workflowHasUnpublishedChanges.value = false
    ElMessage.success(`${t('agentMsg.published')}${res.version}`)
  } finally {
    workflowPublishing.value = false
  }
}

async function openWorkflowVersions() {
  wfVersions.value = await http.get(`/agent/${agentId}/workflow/versions`) as any
  wfVersionsVisible.value = true
}

async function rollbackWorkflow(version: string) {
  await ElMessageBox.confirm(`${t('agentMsg.rollbackWorkflowConfirm')}${version}${t('agentMsg.rollbackWorkflowSuffix')}`, t('agentMsg.rollbackWorkflow'),
    { type: 'warning', confirmButtonText: t('agentMsg.rollbackWorkflow'), cancelButtonText: t('agentDlg.cancel') })
  const res = await http.post(`/agent/${agentId}/workflow/versions/${version}/rollback`) as any
  ElMessage.success(`${t('agentMsg.rolledBackPublished')}${res.version}`)
  wfVersionsVisible.value = false
  const data = await http.get(`/agent/${agentId}/workflow`) as any
  workflowForm.name = data.name
  workflowForm.status = data.status
  workflowForm.nodes = (data.definition?.nodes || []).map((n: any) => ({ ...n }))
  ensureWorkflowPositions()
  workflowPublishedVersion.value = data.publishedVersion || ''
  workflowHasUnpublishedChanges.value = !!data.hasUnpublishedChanges
}

async function runWorkflow() {
  if (!workflowRunInput.value.trim()) { ElMessage.warning(t('agentMsg.testInputRequired')); return }
  workflowRunning.value = true
  stopReplay()
  workflowRunResult.value = null
  try {
    workflowRunResult.value = await http.post(`/agent/${agentId}/workflow/run`,
      { input: workflowRunInput.value.trim() }, { timeout: 180000 }) as any
    if (workflowRunResult.value?.status === 'WAITING_APPROVAL') {
      ElMessage.info(t('agentMsg.pausedApproval'))
    }
  } finally {
    workflowRunning.value = false
  }
}

const wfReplaySteps = computed<any[]>(() => workflowRunResult.value?.nodeRuns || [])
const wfReplayCurrent = computed<any>(() => wfReplaySteps.value[wfReplayIndex.value] || null)

function replayDuration(step: any): number | null {
  if (!step?.startedAt || !step?.endedAt) return null
  const ms = new Date(step.endedAt).getTime() - new Date(step.startedAt).getTime()
  return Number.isFinite(ms) && ms >= 0 ? ms : null
}

function clearReplayTimer() {
  if (wfReplayTimer) {
    clearTimeout(wfReplayTimer)
    wfReplayTimer = null
  }
}

function focusReplayNode() {
  const step = wfReplayCurrent.value
  const wrap = wfCanvasEl.value
  if (!step || !wrap || wfMode.value !== 'canvas') return
  const node = workflowForm.nodes.find((n: any) => n.id === step.nodeId)
  if (!node) return
  const x = nodeX(node) * wfZoom.value
  const y = nodeY(node) * wfZoom.value
  if (x < wrap.scrollLeft || x > wrap.scrollLeft + wrap.clientWidth - 220) {
    wrap.scrollLeft = Math.max(0, x - 60)
  }
  if (y < wrap.scrollTop || y > wrap.scrollTop + wrap.clientHeight - 100) {
    wrap.scrollTop = Math.max(0, y - 40)
  }
}

function startReplay() {
  if (!wfReplaySteps.value.length) return
  clearReplayTimer()
  wfMode.value = 'canvas'
  wfSelectedEdge.value = null
  wfNodePanel.value = false
  if (wfReplayIndex.value < 0 || wfReplayIndex.value >= wfReplaySteps.value.length - 1) {
    wfReplayIndex.value = -1
  }
  wfReplayPlaying.value = true
  advanceReplay()
}

function advanceReplay() {
  clearReplayTimer()
  if (!wfReplayPlaying.value) return
  if (wfReplayIndex.value >= wfReplaySteps.value.length - 1) {
    wfReplayPlaying.value = false
    return
  }
  wfReplayIndex.value += 1
  focusReplayNode()
  wfReplayTimer = setTimeout(advanceReplay, 1100)
}

function toggleReplay() {
  if (wfReplayPlaying.value) {
    wfReplayPlaying.value = false
    clearReplayTimer()
    return
  }
  startReplay()
}

function stepReplay(delta: number) {
  if (!wfReplaySteps.value.length) return
  wfReplayPlaying.value = false
  clearReplayTimer()
  const next = Math.min(wfReplaySteps.value.length - 1, Math.max(0, wfReplayIndex.value + delta))
  wfReplayIndex.value = next
  focusReplayNode()
}

function stopReplay() {
  wfReplayPlaying.value = false
  clearReplayTimer()
  wfReplayIndex.value = -1
}

function goApprovals() {
  workflowVisible.value = false
  router.push('/approvals')
}

function confirmPicker() {
  const options = pickerOptions.value
  for (const id of picker.selected) {
    const o = options.find((x: any) => x.id === id)
    if (!o) continue
    const row: RefRow = {
      id, name: o.name, description: o.description,
      type: o.toolType || o.skillType, isBuiltin: !!o.isBuiltin, enabled: true
    }
    if (picker.type === 'skill') skillRows.value.push(row)
    else if (picker.type === 'tool') toolRows.value.push(row)
    else subRows.value.push(row)
  }
  picker.visible = false
}

function removeRow(rows: any, row: RefRow) {
  const i = rows.value.indexOf(row)
  if (i >= 0) rows.value.splice(i, 1)
}

function parsePolicyJson(text: string) {
  if (!text || !text.trim()) return {}
  let value: any
  try {
    value = JSON.parse(text)
  } catch {
    throw new Error('write policy JSON invalid')
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('write policy JSON must be an object')
  }
  return value
}

function buildPayload() {
  return {
    name: form.name,
    description: form.description,
    category: form.category,
    tags: tagsStr.value.split(/[,，]/).map(t => t.trim()).filter(Boolean),
    systemPrompt: form.systemPrompt,
    openingStatement: form.openingStatement,
    agentCapabilities: {
      ...form.capabilities,
      writeToolArgPatterns: parsePolicyJson(writeToolPatternsText.value),
      writeToolAmountLimits: parsePolicyJson(writeToolAmountLimitsText.value)
    },
    memoryConfig: { ...form.memory },
    modelConfig: { modelName: form.modelName, fallbackModelName: form.fallbackModelName || null,
      temperature: form.temperature,
      thinking: form.thinking, reasoningEffort: form.thinking ? form.reasoningEffort : null },
    variables: form.variables.filter(v => v.key),
    visibility: form.visibility,
    knowledgeBases: kbRows.value.filter(k => k.enabled).map(k => ({
      knowledgeBaseId: k.knowledgeBaseId, enabled: true,
      retrievalConfig: {
        topK: k.topK, scoreThreshold: k.scoreThreshold, mode: k.mode,
        documentType: k.documentType || undefined, sectionType: k.sectionType || undefined,
        regulatoryCategory: k.regulatoryCategory || undefined, chunkLevel: k.chunkLevel,
        minRelevance: k.minRelevance
      }
    })),
    skills: skillRows.value.filter(r => !r.missing).map(r => ({ skillId: r.id, enabled: r.enabled })),
    tools: toolRows.value.filter(r => !r.missing).map(r => ({ toolId: r.id, enabled: r.enabled })),
    subAgents: subRows.value.filter(r => !r.missing).map(r => ({ subAgentId: r.id, enabled: r.enabled }))
  }
}

async function save(): Promise<boolean> {
  if (!form.name.trim() || !form.modelName || !form.systemPrompt.trim()) {
    ElMessage.warning(t('agentMsg.fieldsRequired'))
    return false
  }
  saving.value = true
  try {
    let payload: any
    try {
      payload = buildPayload()
    } catch {
      ElMessage.error(t('agentEdit.writeToolPolicyJsonInvalid'))
      return false
    }
    const res = await http.put(`/agent/${agentId}`, payload, {
      headers: form.updatedAt ? { 'If-Match': form.updatedAt } : {}
    }) as any
    form.updatedAt = res.updatedAt
    if (form.visibility === 'PRIVATE') {
      await http.put(`/agent/${agentId}/grants`, { userIds: grantedIds.value }).catch(() => {})
    }
    ElMessage.success(t('agentDlg.saved'))
    return true
  } finally {
    saving.value = false
  }
}

/** 发布前配置检查摘要（需求 3.2：知情确认，不阻断；门禁仍由后端执行）。 */
async function publishCheckSummary(): Promise<boolean> {
  const items = [
    { label: t('agentMsg.chkModel'), ok: !!form.modelName },
    { label: t('agentMsg.chkPrompt'), ok: !!form.systemPrompt?.trim() },
    { label: t('agentMsg.chkKb'), ok: enabledKbCount.value > 0, count: enabledKbCount.value },
    { label: t('agentMsg.chkTools'), ok: toolRows.value.length > 0, count: toolRows.value.length },
    { label: t('agentMsg.chkSkills'), ok: skillRows.value.length > 0, count: skillRows.value.length },
    { label: t('agentMsg.chkVars'), ok: form.variables.length > 0, count: form.variables.length }
  ]
  const warns = items.filter(i => !i.ok)
  if (warns.length === 0) return true
  const lines = items.map(i => `${i.ok ? '✓' : '⚠'} ${i.label}` + (i.ok && i.count ? `（${i.count}）` : ''))
  try {
    await ElMessageBox.confirm(
      `<div style="line-height:2">${lines.join('<br/>')}</div>`,
      t('agentMsg.chkTitle'),
      { type: 'warning', dangerouslyUseHTMLString: true,
        confirmButtonText: t('agentMsg.chkPublishAnyway'), cancelButtonText: t('agentDlg.cancel') })
    return true
  } catch {
    return false
  }
}

/** 已部署过的 Agent 发布后自动用新版本重新部署，保证容器与线上版本一致 */
async function autoRedeployAfterPublish() {
  try {
    const data = await http.get(`/agent/${agentId}/deployment`) as any
    if (!data?.deployment || !data.dockerAvailable) return
    deployment.value = await http.post(`/agent/${agentId}/deployment`, {}) as any
    deployInfo.dockerAvailable = true
    ElMessage.success(t('deploy.autoRedeployed'))
  } catch {
    // 部署失败已由请求拦截器提示，不阻断发布结果
  }
}

async function publish(force = false) {
  if (!force) {
    const go = await publishCheckSummary()
    if (!go) return
  }
  if (!await save()) return
  // 发布可能先执行预发布门禁评测（每样本约 5-30s），超时放宽至 10 分钟
  const loading = ElLoading.service({
    text: force ? t('agentMsg.forcePublishing') : t('agentMsg.publishing'),
    background: 'rgba(0, 0, 0, 0.4)'
  })
  try {
    const res = await http.post(`/agent/${agentId}/publish`, { force }, { timeout: 600000 }) as any
    form.status = 'PUBLISHED'
    ElMessage.success(`${t('agentMsg.published')}${res.version}`)
    await autoRedeployAfterPublish()
  } catch (err: any) {
    const msg = String(err?.response?.data?.message || err?.message || err)
    if (msg.includes('writePolicyGate:')) {
      loading.close()
      try {
        await ElMessageBox.confirm(
          `${escapeHtml(msg)}<br/><br/>${escapeHtml(t('agentMsg.writePolicyGatePrompt'))}`,
          t('agentMsg.writePolicyGateTitle'),
          { type: 'warning', dangerouslyUseHTMLString: true,
            confirmButtonText: t('agentMsg.forcePublish'), cancelButtonText: t('agentDlg.cancel') })
        await publish(true)
      } catch { /* 取消 */ }
      return
    }
    if (msg.includes(t('agentMsg.gateFailed'))) {
      loading.close()
      let gateHtml = ''
      try {
        const gate = await http.post(`/agent/${agentId}/gate-preview`, {},
          { timeout: 600000 }) as any
        gateHtml = renderGateFailures(gate, {
          failedSteps: t('agentMsg.gateFailedSteps'), score: t('agentMsg.gateScore')
        })
      } catch {
        // 明细获取失败不阻断“强制发布”确认
      }
      try {
        await ElMessageBox.confirm(
          `${escapeHtml(msg)}${gateHtml ? '<br/><br/>' + gateHtml : ''}<br/><br/>${escapeHtml(t('agentMsg.forcePublishPrompt'))}`,
          t('agentMsg.gateTitle'),
          { type: 'warning', dangerouslyUseHTMLString: true,
            confirmButtonText: t('agentMsg.forcePublish'), cancelButtonText: t('agentDlg.cancel') })
        await publish(true)
      } catch { /* 取消 */ }
      return
    }
    throw err
  } finally {
    loading.close()
  }
}

async function toggleListing(value: boolean) {
  if (!value) {
    try {
      await ElMessageBox.confirm(t('agentMsg.delistConfirm'),
        t('agentMsg.delistTitle'), { type: 'warning', confirmButtonText: t('agentMsg.delist'), cancelButtonText: t('agentDlg.cancel') })
    } catch {
      form.listed = true
      return
    }
  }
  try {
    await http.put(`/square/agents/${agentId}/listing`, { listed: value })
    ElMessage.success(value ? t('agentMsg.listedMsg') : t('agentMsg.delistedMsg'))
  } catch {
    form.listed = !value
  }
}

async function openVersions() {
  versions.value = await http.get(`/agent/${agentId}/versions`) as any
  versionsVisible.value = true
}

async function rollback(version: string) {
  await ElMessageBox.confirm(`${t('agentMsg.rollbackConfirm')}${version}${t('agentMsg.rollbackSuffix')}`, t('agentMsg.rollbackTitle'), { type: 'warning' })
  await http.post(`/agent/${agentId}/versions/${version}/rollback`)
  ElMessage.success(t('agentMsg.rolledBackRefresh'))
  versionsVisible.value = false
  setTimeout(() => router.go(0), 600)
}

function addVariable() {
  form.variables.push({ key: '', type: 'STRING', required: false, defaultValue: '', description: '' })
}

async function debugSend() {
  const text = debugInput.value.trim()
  if (!text || debugRunning.value) return
  debugInput.value = ''
  debugMsgs.value.push({ role: 'USER', content: text })
  const reply = { role: 'ASSISTANT', content: '', citations: [] as any[], toolCalls: [] as any[],
    usage: null as any, latencyMs: 0 }
  debugMsgs.value.push(reply)
  debugRunning.value = true
  try {
    const res = await http.post('/agent/debug/chat', { config: buildPayload(), message: text }) as any
    reply.content = res.answer
    reply.citations = res.citations || []
    reply.toolCalls = res.toolCalls || []
    reply.usage = res.usage
    reply.latencyMs = res.latencyMs
  } catch (e: any) {
    reply.content = t('agentMsg.debugFailed') + (e.message || e)
  } finally {
    debugRunning.value = false
    scrollDbg()
  }
}

function scrollDbg() {
  nextTick(() => { if (dbgBox.value) dbgBox.value.scrollTop = dbgBox.value.scrollHeight })
}

function evalEntry() {
  router.push(`/eval/${agentId}`)
}

function fmtTime(v?: string) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '—'
}

function clip(text: string, n: number) {
  text = text || ''
  return text.length > n ? text.slice(0, n) + '…' : text
}
watch(wfMode, mode => { if (mode !== 'canvas') closeNodePanel() })
onMounted(() => window.addEventListener('keydown', onWorkflowKeydown))
onUnmounted(() => {
  window.removeEventListener('keydown', onWorkflowKeydown)
  clearReplayTimer()
})
</script>

<style scoped>
.tab-actions { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.mb8 { margin-bottom: 8px; }
.ml8 { margin-left: 8px; }
.created-key {
  margin-top: 12px;
  border: 1px solid #e6a23c;
  background: #fdf6ec;
  border-radius: 6px;
  padding: 10px 12px;
}
.ck-title { font-weight: 600; color: #e6a23c; margin-bottom: 6px; }
.ck-row { display: flex; align-items: center; gap: 10px; }
.ck-value { font-family: monospace; font-size: 13px; word-break: break-all; }
.edit-page {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.topbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 16px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
}
.title {
  font-weight: 600;
  font-size: 15px;
  flex: 1;
  display: flex;
  align-items: center;
}
.edit-body {
  flex: 1;
  display: flex;
  gap: 12px;
  margin-top: 12px;
  min-height: 0;
}
.panels {
  flex: 1.4;
  overflow-y: auto;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 0 16px 16px;
}
.p-title {
  font-weight: 600;
  font-size: 14px;
}
.req {
  color: #f56c6c;
  margin-left: 4px;
}
.ml8 {
  margin-left: 8px;
}
.mt8 {
  margin-top: 8px;
}
.result-text { white-space: pre-wrap; font-size: 12px; line-height: 1.6; }
.panel-links {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.picker-create {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 10px;
}
.prompt-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.tpl-preview { margin-top: 12px; }
.opt-origin { border: 1px solid #ebeef5; border-radius: 6px; padding: 8px 10px; background: #fafafa; }
.opt-origin-text { white-space: pre-wrap; font-size: 12px; max-height: 120px; overflow-y: auto; color: #606266; }
.opt-label { font-size: 12px; color: #909399; margin-bottom: 4px; }
.opt-result { margin-top: 12px; border-top: 1px dashed #ebeef5; padding-top: 10px; }
.mb8 { margin-bottom: 8px; }
.tpl-vars { font-size: 12px; color: #909399; margin-bottom: 6px; }
.tpl-content {
  white-space: pre-wrap;
  background: #f5f7fa;
  border-radius: 6px;
  padding: 10px;
  font-size: 13px;
  line-height: 1.6;
  max-height: 260px;
  overflow-y: auto;
}
.row-desc {
  flex: 1;
  min-width: 0;
  color: #909399;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.d-tools {
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px dashed #dcdfe6;
}
.d-tool {
  font-size: 12px;
  color: #606266;
}
.d-tool.err {
  color: #f56c6c;
}
.hint {
  color: #909399;
  font-size: 13px;
}
.kb-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px dashed #ebeef5;
}
.kb-name {
  min-width: 140px;
  font-size: 14px;
}
.kb-params {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #606266;
}
.debug {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  min-width: 0;
}
.debug-head {
  padding: 10px 14px;
  font-weight: 600;
  border-bottom: 1px solid #e4e7ed;
  font-size: 14px;
}
.debug-msgs {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.d-msg {
  display: flex;
}
.d-msg.user {
  justify-content: flex-end;
}
.d-bubble {
  max-width: 88%;
  padding: 8px 12px;
  border-radius: 8px;
  background: #f4f4f5;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.d-msg.user .d-bubble {
  background: #409eff;
  color: #fff;
}
.d-cites {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}
.d-cite {
  display: block;
}
.d-usage {
  margin-top: 4px;
  font-size: 12px;
  color: #c0c4cc;
}
.debug-input {
  display: flex;
  gap: 8px;
  padding: 10px;
  border-top: 1px solid #e4e7ed;
}
.wf-head { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.wf-flow { max-height: 46vh; overflow-y: auto; padding-right: 4px; }
.wf-node {
  border: 1px solid #e4e7ed;
  border-left: 3px solid #409eff;
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 10px;
  background: #fff;
}
.wf-node-head { display: flex; align-items: center; gap: 8px; }
.wf-node-body { margin-top: 8px; }
.wf-node-next { margin-top: 8px; font-size: 13px; color: #606266; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.wf-line { display: flex; align-items: center; gap: 8px; }
.wf-run { margin-top: 10px; padding: 10px; background: #f5f7fa; border-radius: 6px; }
.wf-run pre { white-space: pre-wrap; word-break: break-word; margin: 6px 0 0; font-size: 13px; line-height: 1.6; }
.wf-mode { margin-bottom: 10px; }
.wf-canvas-tools { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 8px; }
.wf-tool-sep { width: 1px; height: 18px; background: #e4e7ed; }
.wf-canvas-wrap { flex: 1 1 auto; min-width: 0; max-height: 52vh; overflow: auto; border: 1px solid #ebeef5; border-radius: 8px; background: #fafbfc; cursor: grab; }
.wf-canvas-wrap:active { cursor: grabbing; }
.wf-node.selected { border-color: #409eff; box-shadow: 0 0 0 2px rgba(64,158,255,.15); }
.wf-canvas { position: relative; min-width: 640px; min-height: 380px; }
.wf-edges { position: absolute; left: 0; top: 0; pointer-events: none; }
.wf-edges path { pointer-events: stroke; cursor: pointer; }
.wf-edges path.wf-edge-selected { stroke-width: 3.2; filter: drop-shadow(0 0 3px rgba(64,158,255,.6)); }
.wf-edge-temp { fill: none; stroke: #409eff; stroke-width: 2; stroke-dasharray: 5 4; pointer-events: none; }
.wf-edge-label {
  font-size: 10px; fill: #909399; pointer-events: none; text-anchor: middle;
  paint-order: stroke; stroke: #fafbfc; stroke-width: 3px; stroke-linejoin: round;
}
.wf-canvas-node {
  position: absolute;
  width: 190px;
  padding: 8px 10px;
  background: #fff;
  border: 1px solid #dcdfe6;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0,0,0,.06);
  cursor: grab;
  user-select: none;
}
.wf-canvas-node:active { cursor: grabbing; }
.wf-canvas-node.selected { border-color: #409eff; box-shadow: 0 0 0 2px rgba(64,158,255,.2); }
.wf-cn-name { margin-top: 4px; font-size: 13px; font-weight: 600; color: #303133; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.wf-cn-id { font-size: 11px; color: #a8abb2; margin-top: 2px; }

/* 画布：左节点面板 + 拖入 + 端口连线 + 运行态高亮 */
.wf-canvas-layout { display: flex; gap: 10px; align-items: stretch; }
.wf-palette { width: 148px; flex-shrink: 0; padding: 8px; border: 1px solid #ebeef5; border-radius: 8px; background: #fff; max-height: 52vh; overflow-y: auto; }
.wf-palette-title { font-size: 13px; font-weight: 600; margin-bottom: 6px; color: #303133; }
.wf-palette-item { margin-bottom: 6px; cursor: grab; }
.wf-palette-item:active { cursor: grabbing; }
.wf-canvas-wrap.drop-active { border-color: #409eff; box-shadow: inset 0 0 0 2px rgba(64,158,255,.15); }
.wf-port {
  position: absolute; right: -7px; top: 26px; width: 14px; height: 14px; border-radius: 50%;
  background: #fff; border: 2px solid #409eff; cursor: crosshair; z-index: 2;
}
.wf-port:hover { transform: scale(1.25); }
.wf-port-in { left: -7px; right: auto; border-color: #c0c4cc; cursor: default; }
.wf-node-panel {
  width: 340px; flex-shrink: 0; padding: 10px; border: 1px solid #ebeef5; border-radius: 8px;
  background: #fff; max-height: 52vh; overflow-y: auto;
}
.wf-node-panel-head { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; }
.wf-node-panel-head .wf-cn-id { margin-left: auto; }
.wf-canvas-node.run-success { border-color: #67c23a; box-shadow: 0 0 0 2px rgba(103,194,58,.18); }
.wf-canvas-node.run-failed { border-color: #f56c6c; box-shadow: 0 0 0 2px rgba(245,108,108,.18); }
.wf-canvas-node.run-running { border-color: #409eff; box-shadow: 0 0 0 2px rgba(64,158,255,.25); }
.wf-canvas-node.run-waiting { border-color: #e6a23c; box-shadow: 0 0 0 2px rgba(230,162,60,.18); }
.wf-canvas-node.multi { border-color: #e6a23c; box-shadow: 0 0 0 2px rgba(230,162,60,.25); }
.wf-canvas-node.replay-current {
  border-color: #409eff; box-shadow: 0 0 0 3px rgba(64, 158, 255, 0.35);
  animation: replay-pulse 1.2s ease-in-out infinite;
}
@keyframes replay-pulse {
  50% { box-shadow: 0 0 0 6px rgba(64, 158, 255, 0.12); }
}
.wf-replay { margin-top: 10px; border-top: 1px dashed #ebeef5; padding-top: 8px; }
.wf-replay-bar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.wf-replay-info { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 8px; font-size: 13px; }
.wf-replay-output {
  flex-basis: 100%; background: #f5f7fa; border-radius: 6px; padding: 8px 10px;
  font-size: 12px; color: #606266; max-height: 140px; overflow: auto; white-space: pre-wrap; word-break: break-word;
}
.wf-select-box {
  position: absolute; z-index: 3; pointer-events: none;
  border: 1px dashed #409eff; background: rgba(64, 158, 255, 0.08); border-radius: 2px;
}

.deploy-code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; word-break: break-all; }
.deploy-curl,
.deploy-logs {
  margin: 6px 0 0;
  padding: 10px 12px;
  background: #f5f7fa;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 220px;
  overflow: auto;
}
.deploy-logs { max-height: 50vh; }
.deploy-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.deploy-keys-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.deploy-replicas { display: flex; align-items: center; gap: 8px; margin-top: 10px; flex-wrap: wrap; }
:deep(.el-descriptions__label) { white-space: nowrap; }

@media (max-width: 768px) {
  .topbar { flex-wrap: wrap; gap: 8px; padding: 8px 10px; }
  .title { flex-basis: calc(100% - 110px); font-size: 14px; }
  .actions { width: 100%; display: flex; flex-wrap: wrap; gap: 6px; }
  .actions .el-button { margin-left: 0; flex: 1 1 calc(33.33% - 6px); }
  /* 配置面板与调试面板改为上下排列 */
  .edit-body { flex-direction: column; }
  .panels { flex: none; overflow: visible; padding: 0 10px 10px; }
  .debug { min-height: 420px; }
  /* 移动端：节点面板改为横向可滚动，画布在上 */
  .wf-canvas-layout { flex-direction: column; }
  .wf-palette { width: 100%; max-height: none; display: flex; flex-wrap: wrap; gap: 6px; }
  .wf-palette-title, .wf-palette .hint { flex-basis: 100%; }
  .wf-palette-item { margin-bottom: 0; }
  .wf-node-panel { width: 100%; max-height: none; }
}
</style>