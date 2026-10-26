<template>
  <div class="wf-node-body">

            <template v-if="node.type === 'LLM'">
              <el-input v-model="node.prompt" type="textarea" :rows="2" :placeholder="t('wf.nodePromptPh')" />
              <el-input v-model="node.modelName" :placeholder="t('wf.modelPh')" style="width: 240px" class="mt8" />
            </template>
            <template v-else-if="node.type === 'AGENT'">
              <el-select v-model="node.agentId" filterable :placeholder="t('wf.selectAgent')" style="width: 240px">
                <el-option v-for="a in agents" :key="a.id" :value="a.id" :label="a.name" />
              </el-select>
              <el-input v-model="node.prompt" type="textarea" :rows="2" :placeholder="t('wf.extraPromptPh')" class="mt8" />
            </template>
            <template v-else-if="node.type === 'STATE'">
              <el-input v-model="node.stateKey" :placeholder="t('wf.stateKeyPh')" style="width: 260px" />
              <el-input v-model="node.valueTemplate" type="textarea" :rows="2" class="mt8"
                        :placeholder="t('wf.valuePh')" />
            </template>
            <template v-else-if="node.type === 'HANDOFF'">
              <el-select v-model="node.agentId" filterable :placeholder="t('wf.handoffAgent')" style="width: 260px">
                <el-option v-for="a in agents" :key="a.id" :value="a.id" :label="a.name" />
              </el-select>
              <el-input v-model="node.inputTemplate" type="textarea" :rows="2" class="mt8"
                        :placeholder="t('wf.handoffPh')" />
              <div class="wf-line mt8">
                <el-select v-model="node.returnAgentId" filterable clearable :placeholder="t('wf.returnAgent')" style="width: 260px">
                  <el-option v-for="a in agents" :key="a.id" :value="a.id" :label="a.name" />
                </el-select>
                <span class="hint">{{ t('wf.returnHint') }}</span>
              </div>
              <el-input v-if="node.returnAgentId" v-model="node.returnTemplate" type="textarea" :rows="2" class="mt8"
                        :placeholder="t('wf.returnPh')" />
              <div v-if="node.returnAgentId" class="wf-line mt8">
                <span>{{ t('wf.rounds') }}</span>
                <el-input-number v-model="node.returnRounds" :min="1" :max="3" size="small" />
                <span class="hint">{{ t('wf.roundsHint') }}</span>
              </div>
              <div class="hint">{{ t('wf.handoffDesc') }}</div>
            </template>
            <template v-else-if="node.type === 'TOOL'">
              <el-select v-model="node.toolId" filterable :placeholder="t('wf.selectTool')" style="width: 260px">
                <el-option v-for="tool in tools" :key="tool.id" :value="tool.id"
                           :label="tool.name + (tool.toolType === 'MCP' ? t('agentDlg.mcpSuffix') : '')" />
              </el-select>
              <el-input v-model="node.argsTemplate" type="textarea" :rows="2" class="mt8"
                        :placeholder="t('wf.argsPh')" />
            </template>
            <template v-else-if="node.type === 'KNOWLEDGE'">
              <div class="wf-line">
                <el-select v-model="node.knowledgeBaseId" filterable :placeholder="t('wf.selectKb')" style="width: 240px">
                  <el-option v-for="k in kbs" :key="k.knowledgeBaseId" :value="k.knowledgeBaseId" :label="k.name" />
                </el-select>
                <el-select v-model="node.mode" style="width: 110px">
                  <el-option :label="t('agentEdit.hybrid')" value="hybrid" />
                  <el-option :label="t('agentEdit.vector')" value="vector" />
                  <el-option :label="t('agentEdit.keyword')" value="keyword" />
                </el-select>
                topK <el-input-number v-model="node.topK" :min="1" :max="20" size="small" />
              </div>
              <el-input v-model="node.queryTemplate" class="mt8"
                        :placeholder="t('wf.queryPh')" />
            </template>
            <template v-else-if="node.type === 'CONDITION'">
              <div class="wf-line">
                <el-select v-model="node.conditionType" style="width: 130px">
                  <el-option :label="t('wf.contains')" value="contains" />
                  <el-option :label="t('wf.notContains')" value="notContains" />
                  <el-option :label="t('wf.equals')" value="equals" />
                  <el-option :label="t('wf.lengthGt')" value="lengthGt" />
                </el-select>
                <el-input v-model="node.conditionValue" :placeholder="t('wf.conditionPh')" style="width: 220px" />
              </div>
            </template>
            <template v-else-if="node.type === 'PARALLEL'">
              <div class="wf-line">
                <el-select v-model="node.branches" multiple filterable :placeholder="t('wf.branchesPh')" style="width: 420px">
                  <el-option v-for="o in branchTargets(node.id)" :key="o.id" :value="o.id"
                             :label="typeText(o.type) + ' · ' + o.name" />
                </el-select>
                <el-select v-model="node.joinNode" :placeholder="t('wf.joinPh')" style="width: 200px">
                  <el-option v-for="o in nodes.filter((x: any) => x.type === 'JOIN')"
                             :key="o.id" :value="o.id" :label="'JOIN · ' + o.name" />
                </el-select>
                <el-select v-model="node.strategy" style="width: 170px">
                  <el-option :label="t('wf.strategyAll')" value="ALL_OR_NOTHING" />
                  <el-option :label="t('wf.strategyBest')" value="BEST_EFFORT" />
                </el-select>
              </div>
            </template>
            <template v-else-if="node.type === 'LOOP'">
              <div class="wf-line">
                <span>{{ t('wf.loopBody') }}</span>
                <el-select v-model="node.bodyNode" style="width: 190px">
                  <el-option v-for="o in branchTargets(node.id)" :key="o.id" :value="o.id"
                             :label="typeText(o.type) + ' · ' + o.name" />
                </el-select>
                <span>{{ t('wf.loopEnd') }}</span>
                <el-select v-model="node.loopEnd" clearable style="width: 180px">
                  <el-option v-for="o in targets(node.id)" :key="o.id" :value="o.id"
                             :label="typeText(o.type) + ' · ' + o.name" />
                </el-select>
                {{ t('wf.maxIter') }} <el-input-number v-model="node.maxIterations" :min="1" :max="50" size="small" />
              </div>
              <el-input v-model="node.itemsText" type="textarea" :rows="2" class="mt8"
                        :placeholder="t('wf.itemsPh')" />
              <div class="wf-line mt8">
                <span class="hint">{{ t('wf.loopHint') }}</span>
                {{ t('wf.approvalRole') }}
                <el-select v-model="node.requiredRole" clearable placeholder="OWNER/ADMIN" size="small" style="width: 150px">
                  <el-option label="OWNER" value="OWNER" />
                  <el-option label="ADMIN" value="ADMIN" />
                  <el-option label="EDITOR" value="EDITOR" />
                </el-select>
                {{ t('wf.timeoutMin') }} <el-input-number v-model="node.timeoutMinutes" :min="0" :max="10080" size="small" />
              </div>
            </template>
            <template v-else-if="node.type === 'JOIN'">
              <span class="hint">{{ t('wf.joinHint') }}</span>
            </template>
            <template v-else-if="node.type === 'APPROVAL'">
              <div class="wf-line">
                <el-input v-model="node.title" :placeholder="t('wf.approvalTitlePh')" style="width: 300px" />
                <el-select v-model="node.requiredRole" clearable :placeholder="t('wf.approvalRolePh')" style="width: 180px">
                  <el-option label="OWNER" value="OWNER" />
                  <el-option label="ADMIN" value="ADMIN" />
                  <el-option label="EDITOR" value="EDITOR" />
                </el-select>
                {{ t('wf.timeoutMin') }}
                <el-input-number v-model="node.timeoutMinutes" :min="0" :max="10080" size="small" />
                <span class="hint">{{ t('wf.unlimited') }}</span>
              </div>
            </template>
            <template v-else>
              <span class="hint">{{ t('wf.noConfig') }}</span>
            </template>
            <div v-if="['LLM','AGENT','HANDOFF','TOOL','KNOWLEDGE'].includes(node.type)" class="wf-line mt8">
              <span class="hint">{{ t('wf.failStrategy') }}</span>
              {{ t('wf.retry') }} <el-input-number v-model="node.retryTimes" :min="0" :max="3" size="small" />
              <el-select v-model="node.onError" size="small" style="width: 160px">
                <el-option :label="t('wf.fail')" value="FAIL" />
                <el-option :label="t('wf.continue')" value="CONTINUE" />
                <el-option :label="t('wf.branch')" value="BRANCH" />
              </el-select>
              <template v-if="node.onError === 'BRANCH'">
                {{ t('wf.failBranch') }}
                <el-select v-model="node.errorNext" size="small" style="width: 170px">
                  <el-option v-for="o in targets(node.id)" :key="o.id" :value="o.id"
                             :label="typeText(o.type) + ' · ' + o.name" />
                </el-select>
              </template>
            </div>
            </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'

// 工作流节点配置表单：列表编辑与画布右侧面板共用，避免两套字段
const props = defineProps<{
  node: any
  nodes: any[]
  agents: any[]
  tools: any[]
  kbs: any[]
}>()

const { t } = useI18n()

function typeText(type: string) {
  return ({
    START: t('wf.typeStart'), END: t('wf.typeEnd'), LLM: t('wf.typeLlm'), AGENT: t('wf.typeAgent'),
    HANDOFF: t('wf.typeHandoff'), STATE: t('wf.typeState'), TOOL: t('wf.typeTool'),
    KNOWLEDGE: t('wf.typeKnowledge'), CONDITION: t('wf.typeCondition'), APPROVAL: t('wf.typeApproval'),
    PARALLEL: t('wf.typeParallel'), LOOP: t('wf.typeLoop'), JOIN: t('wf.typeJoin')
  } as Record<string, string>)[type] || type
}

function targets(currentId: string) {
  return props.nodes.filter((x: any) => x.id !== currentId)
}

function branchTargets(currentId: string) {
  return props.nodes.filter((x: any) => x.id !== currentId
    && !['START', 'END', 'JOIN', 'PARALLEL', 'LOOP'].includes(x.type))
}
</script>

<style scoped>
.wf-node-body { margin-top: 8px; }
.wf-line { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.mt8 { margin-top: 8px; }
.hint { color: #909399; font-size: 12px; }
</style>
