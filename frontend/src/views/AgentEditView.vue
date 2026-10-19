<template>
  <div class="edit-page">
    <!-- 顶栏 -->
    <div class="topbar">
      <el-button @click="$router.push('/agents')" text>
        <el-icon><ArrowLeft /></el-icon> 返回列表
      </el-button>
      <div class="title">
        编辑 Agent
        <el-tag size="small" :type="form.status === 'PUBLISHED' ? 'success' : 'info'" class="ml8">
          {{ form.status === 'PUBLISHED' ? '已发布' : '草稿' }}
        </el-tag>
      </div>
      <div class="actions">
        <el-button @click="openVersions">版本</el-button>
        <el-button @click="openIntegration">集成</el-button>
        <el-button @click="openSchedules">定时</el-button>
        <el-button @click="openCredentials">凭证</el-button>
        <el-button @click="evalEntry">评测</el-button>
        <el-button type="success" plain @click="publish()">发布</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
    </div>

    <div class="edit-body">
      <!-- 左侧：配置面板 -->
      <div class="panels">
        <el-collapse v-model="activePanels">
          <el-collapse-item name="base">
            <template #title><span class="p-title">基本信息</span></template>
            <el-form label-width="70px">
              <el-form-item label="名称"><el-input v-model="form.name" maxlength="64" /></el-form-item>
              <el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
              <el-form-item label="分类"><el-input v-model="form.category" placeholder="如：客服" style="width: 200px" /></el-form-item>
              <el-form-item label="标签"><el-input v-model="tagsStr" placeholder="逗号分隔，如：售后,售前" /></el-form-item>
            </el-form>
          </el-collapse-item>

          <el-collapse-item name="model">
            <template #title><span class="p-title">模型选择</span></template>
            <el-form label-width="70px">
              <el-form-item label="模型" required>
                <el-select v-model="form.modelName" style="width: 320px">
                  <el-option v-for="m in models" :key="m.modelName" :value="m.modelName"
                             :label="modelLabel(m)" />
                </el-select>
              </el-form-item>
              <el-form-item label="温度">
                <el-slider v-model="form.temperature" :min="0" :max="2" :step="0.1" style="width: 320px" />
              </el-form-item>
            </el-form>
          </el-collapse-item>

          <el-collapse-item name="prompt">
            <template #title><span class="p-title">提示词</span><span class="req">*</span></template>
            <div class="prompt-actions">
              <el-button size="small" @click="openTemplatePicker">从模板插入</el-button>
              <span class="hint">插入后仍可编辑；双花括号变量由「占位变量」面板统一替换</span>
            </div>
            <el-input v-model="form.systemPrompt" type="textarea" :rows="8"
                      placeholder="系统提示词，可用双花括号变量占位（见「占位变量」面板）" />
          </el-collapse-item>

          <el-collapse-item name="welcome">
            <template #title><span class="p-title">欢迎语</span></template>
            <el-input v-model="form.openingStatement" type="textarea" :rows="3"
                      placeholder="对话开始时展示给用户的第一句话，支持 {{变量key}}" />
          </el-collapse-item>

          <el-collapse-item name="capability">
            <template #title><span class="p-title">Agent 能力</span></template>
            <el-form label-width="90px">
              <el-form-item label="流式输出"><el-switch v-model="form.capabilities.streamOutput" /></el-form-item>
              <el-form-item label="函数调用"><el-switch v-model="form.capabilities.functionCall" /></el-form-item>
              <el-form-item label="多模态输入"><el-switch v-model="form.capabilities.multimodal" /></el-form-item>
            </el-form>
          </el-collapse-item>

          <el-collapse-item name="memory">
            <template #title><span class="p-title">记忆</span></template>
            <el-form label-width="90px">
              <el-form-item label="启用记忆"><el-switch v-model="form.memory.enabled" /></el-form-item>
              <el-form-item label="窗口条数">
                <el-input-number v-model="form.memory.windowSize" :min="2" :max="50" :disabled="!form.memory.enabled" />
              </el-form-item>
            </el-form>
          </el-collapse-item>

          <el-collapse-item name="knowledge">
            <template #title><span class="p-title">知识库</span>
              <el-tag size="small" class="ml8">{{ enabledKbCount ? `已绑定 ${enabledKbCount} 个` : '未绑定' }}</el-tag>
            </template>
            <div v-if="kbRows.length === 0" class="hint">暂无知识库，请先在知识库模块创建并上传文档</div>
            <div v-for="kb in kbRows" :key="kb.knowledgeBaseId" class="kb-row">
              <el-switch v-model="kb.enabled" :disabled="kb.missing" />
              <div class="kb-name">{{ kb.name }}</div>
              <el-tag v-if="kb.missing" size="small" type="danger">已失效</el-tag>
              <div class="kb-params" v-if="kb.enabled">
                topK <el-input-number v-model="kb.topK" :min="1" :max="20" size="small" />
                阈值 <el-input-number v-model="kb.scoreThreshold" :min="0" :max="1" :step="0.05" size="small" />
              </div>
            </div>
          </el-collapse-item>

          <el-collapse-item name="variable">
            <template #title><span class="p-title">占位变量</span>
              <el-tag size="small" class="ml8">{{ form.variables.length }} 个</el-tag>
            </template>
            <el-button size="small" @click="addVariable">＋ 添加变量</el-button>
            <el-table :data="form.variables" size="small" v-if="form.variables.length" class="mt8">
              <el-table-column label="key" width="140">
                <template #default="{ row }"><el-input v-model="row.key" placeholder="user_name" size="small" /></template>
              </el-table-column>
              <el-table-column label="类型" width="110">
                <template #default="{ row }">
                  <el-select v-model="row.type" size="small">
                    <el-option v-for="t in ['STRING','NUMBER','SELECT','FILE']" :key="t" :value="t" :label="t" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="必填" width="60">
                <template #default="{ row }"><el-checkbox v-model="row.required" /></template>
              </el-table-column>
              <el-table-column label="默认值" width="130">
                <template #default="{ row }"><el-input v-model="row.defaultValue" size="small" /></template>
              </el-table-column>
              <el-table-column label="描述">
                <template #default="{ row }"><el-input v-model="row.description" size="small" /></template>
              </el-table-column>
              <el-table-column width="60">
                <template #default="{ $index }">
                  <el-button text type="danger" size="small" @click="form.variables.splice($index, 1)">删</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-collapse-item>

          <el-collapse-item name="access">
            <template #title><span class="p-title">可见性与授权</span>
              <el-tag size="small" class="ml8">{{ form.visibility === 'PRIVATE' ? '私有' : '租户可见' }}</el-tag>
            </template>
            <el-form label-width="90px">
              <el-form-item label="可见性">
                <el-radio-group v-model="form.visibility">
                  <el-radio value="TENANT">租户内全员可见</el-radio>
                  <el-radio value="PRIVATE">私有（仅授权用户）</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item v-if="form.status === 'PUBLISHED'" label="上架广场">
                <el-switch v-model="form.listed" @change="toggleListing" />
                <span class="hint ml8">下架后广场不可见，已使用的用户仍可继续对话</span>
              </el-form-item>
              <el-form-item v-if="form.visibility === 'PRIVATE'" label="授权用户">
                <el-select v-model="grantedIds" multiple placeholder="选择可使用的成员" style="width: 100%">
                  <el-option v-for="m in members.filter(m => m.id !== ownerId)"
                             :key="m.id" :value="m.id"
                             :label="`${m.displayName}(${m.username})`" />
                </el-select>
              </el-form-item>
            </el-form>
          </el-collapse-item>

          <el-collapse-item name="skill">
            <template #title><span class="p-title">技能</span>
              <el-tag size="small" class="ml8">{{ enabledSkillCount ? `已启用 ${enabledSkillCount} 个` : '未配置' }}</el-tag>
            </template>
            <div class="panel-links">
              <el-button size="small" @click="openPicker('skill')">＋ 添加技能</el-button>
              <el-link type="primary" :underline="false" @click="$router.push('/skills')">技能管理</el-link>
            </div>
            <div v-if="skillRows.length === 0" class="hint mt8">暂未添加技能；内置技能可直接选用（技能管理可创建自定义技能）</div>
            <div v-for="row in skillRows" :key="'s' + row.id" class="kb-row">
              <el-switch v-model="row.enabled" :disabled="row.missing" />
              <div class="kb-name">{{ row.name }}</div>
              <el-tag v-if="row.isBuiltin" size="small" type="info">内置</el-tag>
              <el-tag v-if="row.missing" size="small" type="danger">已失效</el-tag>
              <div class="row-desc">{{ row.description }}</div>
              <el-button size="small" text type="danger" @click="removeRow(skillRows, row)">移除</el-button>
            </div>
          </el-collapse-item>

          <el-collapse-item name="tool">
            <template #title><span class="p-title">工具</span>
              <el-tag size="small" class="ml8">{{ enabledToolCount ? `已启用 ${enabledToolCount} 个` : '未配置' }}</el-tag>
            </template>
            <div class="panel-links">
              <el-button size="small" @click="openPicker('tool')">＋ 添加工具</el-button>
              <el-link type="primary" :underline="false" @click="$router.push('/tools')">工具管理</el-link>
              <el-link type="primary" :underline="false" @click="$router.push('/mcp-servers')">MCP Server</el-link>
            </div>
            <div v-if="toolRows.length === 0" class="hint mt8">暂未添加工具；需在「Agent 能力」中开启函数调用后才会在对话中生效</div>
            <div v-for="row in toolRows" :key="'t' + row.id" class="kb-row">
              <el-switch v-model="row.enabled" :disabled="row.missing" />
              <div class="kb-name">{{ row.name }}</div>
              <el-tag size="small" :type="row.type === 'BUILTIN' ? 'info' : 'success'">
                {{ row.type === 'BUILTIN' ? '内置' : 'API' }}
              </el-tag>
              <el-tag v-if="row.missing" size="small" type="danger">已失效</el-tag>
              <div class="row-desc">{{ row.description }}</div>
              <el-button size="small" text type="danger" @click="removeRow(toolRows, row)">移除</el-button>
            </div>
          </el-collapse-item>

          <el-collapse-item name="subagent">
            <template #title><span class="p-title">子 Agent</span>
              <el-tag size="small" class="ml8">{{ enabledSubCount ? `已启用 ${enabledSubCount} 个` : '未配置' }}</el-tag>
            </template>
            <div class="panel-links">
              <el-button size="small" @click="openPicker('sub')">＋ 添加子 Agent</el-button>
              <el-link type="primary" :underline="false" @click="$router.push('/agents')">Agent 列表</el-link>
            </div>
            <div v-if="subRows.length === 0" class="hint mt8">子 Agent 将作为工具被父 Agent 调用（保存时校验循环依赖，上限 10 个）</div>
            <div v-for="row in subRows" :key="'a' + row.id" class="kb-row">
              <el-switch v-model="row.enabled" :disabled="row.missing" />
              <div class="kb-name">{{ row.name }}</div>
              <el-tag v-if="row.missing" size="small" type="danger">已失效</el-tag>
              <div class="row-desc">{{ row.description }}</div>
              <el-button size="small" text type="danger" @click="removeRow(subRows, row)">移除</el-button>
            </div>
          </el-collapse-item>
        </el-collapse>
      </div>

      <!-- 右侧：调试 -->
      <div class="debug">
        <div class="debug-head">调试（用当前表单配置，未保存的修改也生效）</div>
        <div class="debug-msgs" ref="dbgBox">
          <div v-for="(m, i) in debugMsgs" :key="i" class="d-msg" :class="m.role.toLowerCase()">
            <div class="d-bubble">
              <div class="d-content">{{ m.content }}</div>
              <div v-if="m.toolCalls?.length" class="d-tools">
                <div v-for="(tc, j) in m.toolCalls" :key="j" class="d-tool"
                     :class="tc.status === 'ERROR' ? 'err' : ''">
                  ⚙ {{ tc.displayName || tc.toolName }}
                  <span v-if="tc.toolType === 'SUB_AGENT'">（子 Agent）</span>
                  · {{ tc.status === 'ERROR' ? '失败' : '成功' }} · {{ tc.durationMs }}ms
                </div>
              </div>
              <div v-if="m.citations?.length" class="d-cites">
                引用 {{ m.citations.length }} 条：
                <span v-for="(c, j) in m.citations" :key="j" class="d-cite">
                  [{{ j + 1 }}] {{ (c.score * 100).toFixed(0) }}% {{ clip(c.content, 30) }}
                </span>
              </div>
              <div v-if="m.usage" class="d-usage">
                {{ m.latencyMs }}ms · 输入 {{ m.usage.inputTokens }} / 输出 {{ m.usage.outputTokens }} tokens
              </div>
            </div>
          </div>
          <el-empty v-if="debugMsgs.length === 0" description="发一条消息试试 RAG 与提示词效果" :image-size="60" />
        </div>
        <div class="debug-input">
          <el-input v-model="debugInput" placeholder="调试消息" @keydown.enter.prevent="debugSend"
                    :disabled="debugRunning" />
          <el-button type="primary" :loading="debugRunning" @click="debugSend">发送</el-button>
        </div>
      </div>
    </div>

    <!-- Agent 凭证 -->
    <el-dialog v-model="credentialVisible" :title="`Agent 凭证：${form.name || ''}`" width="760px">
      <div class="tab-actions">
        <el-button type="primary" size="small" @click="openCredentialCreate">新建凭证</el-button>
        <span class="hint">凭证加密存储、界面不回显明文；绑定工具后运行时自动注入（API_KEY→X-API-Key / OAUTH→Bearer / BASIC→Basic）</span>
      </div>
      <el-table :data="credentials" size="small" empty-text="暂无凭证">
        <el-table-column prop="name" label="名称" width="150" />
        <el-table-column prop="type" label="类型" width="100" />
        <el-table-column label="绑定工具" width="170">
          <template #default="{ row }">{{ toolName(row.toolId) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">
              {{ row.status === 'ENABLED' ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="内容" width="90">
          <template #default="{ row }"><span class="hint">已加密</span></template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button size="small" text @click="openCredentialEdit(row)">编辑</el-button>
            <el-button size="small" text type="danger" @click="removeCredential(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="credentialFormVisible" :title="credentialForm.id ? '编辑凭证' : '新建凭证'" width="520px">
      <el-form label-width="90px">
        <el-form-item label="名称" required><el-input v-model="credentialForm.name" maxlength="64" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="credentialForm.type" style="width: 160px">
            <el-option label="API Key" value="API_KEY" />
            <el-option label="OAuth" value="OAUTH" />
            <el-option label="Basic Auth" value="BASIC" />
          </el-select>
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="credentialForm.content" type="password" show-password
                    :placeholder="credentialForm.id ? '留空表示不修改' : '如 API Key / Bearer Token / user:pass'" />
        </el-form-item>
        <el-form-item label="绑定工具">
          <el-select v-model="credentialForm.toolId" filterable clearable style="width: 100%"
                     placeholder="可选：运行时注入到该工具请求">
            <el-option v-for="t in toolCatalog" :key="t.id" :value="t.id"
                       :label="t.name + (t.toolType === 'MCP' ? '（MCP）' : '')" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="credentialForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="credentialFormVisible = false">取消</el-button>
        <el-button type="primary" :loading="credentialSaving" @click="saveCredential">保存</el-button>
      </template>
    </el-dialog>

    <!-- 定时调度 -->
    <el-dialog v-model="scheduleVisible" :title="`定时调度：${form.name || ''}`" width="820px">
      <div class="tab-actions">
        <el-button type="primary" size="small" @click="openScheduleCreate">新建任务</el-button>
        <span class="hint">Cron 6 段（秒 分 时 日 月 周），兼容 5 段；系统身份执行，Trace tags=scheduled</span>
      </div>
      <el-table :data="schedules" size="small">
        <el-table-column prop="name" label="名称" width="150" />
        <el-table-column prop="cronExpr" label="Cron" width="150" />
        <el-table-column label="下次执行" width="160">
          <template #default="{ row }">{{ fmtTime(row.nextRunAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">
              {{ row.status === 'ENABLED' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近执行" width="150">
          <template #default="{ row }">
            <el-tag v-if="row.lastStatus" size="small" :type="row.lastStatus === 'SUCCESS' ? 'success' : 'danger'">
              {{ row.lastStatus }}
            </el-tag>
            <span v-else class="hint">未执行</span>
            <div class="hint">{{ fmtTime(row.lastRunAt) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="290">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="runSchedule(row)">立即执行</el-button>
            <el-button size="small" text @click="openResults(row)">结果</el-button>
            <el-button size="small" text @click="openScheduleEdit(row)">编辑</el-button>
            <el-button size="small" text @click="toggleSchedule(row)">{{ row.status === 'ENABLED' ? '停用' : '启用' }}</el-button>
            <el-button size="small" text type="danger" @click="removeSchedule(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="schedules.length === 0" description="暂无定时任务" :image-size="50" />
    </el-dialog>

    <el-dialog v-model="scheduleFormVisible" :title="scheduleForm.id ? '编辑定时任务' : '新建定时任务'" width="560px">
      <el-form label-width="100px">
        <el-form-item label="名称" required><el-input v-model="scheduleForm.name" maxlength="64" /></el-form-item>
        <el-form-item label="Cron" required>
          <el-input v-model="scheduleForm.cronExpr" placeholder="0 */5 * * * *（每 5 分钟）" />
        </el-form-item>
        <el-form-item label="输入参数">
          <el-input v-model="scheduleForm.inputParamsText" type="textarea" :rows="4"
                    placeholder='JSON 对象，如 {"brand":"智云","message":"请生成日报"}' />
        </el-form-item>
        <el-form-item label="超时(ms)">
          <el-input-number v-model="scheduleForm.timeoutMs" :min="1000" :max="600000" :step="1000" />
        </el-form-item>
        <el-form-item label="结果去处">
          <el-select v-model="scheduleForm.notifyType" style="width: 160px">
            <el-option label="存储" value="STORE" />
            <el-option label="Webhook" value="WEBHOOK" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="scheduleForm.notifyType === 'WEBHOOK'" label="回调地址">
          <el-input v-model="scheduleForm.webhookUrl" placeholder="https://example.com/webhook" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="scheduleForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scheduleFormVisible = false">取消</el-button>
        <el-button type="primary" :loading="scheduleSaving" @click="saveSchedule">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="resultsVisible" :title="resultsTitle" size="620px">
      <el-table :data="scheduleResults" size="small">
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'SUCCESS' ? 'success' : 'danger'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="结果">
          <template #default="{ row }">
            <div class="result-text">{{ row.status === 'SUCCESS' ? row.answer : row.error }}</div>
            <div class="hint" v-if="row.traceId">trace: {{ row.traceId }}</div>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="scheduleResults.length === 0" description="暂无执行结果" :image-size="50" />
    </el-drawer>

    <!-- 集成 API Key -->
    <el-dialog v-model="integrationVisible" :title="`集成调用：${form.name || ''}`" width="780px">
      <div class="hint mb8">
        外部调用：POST /api/v1/open/chat/completions，请求头 X-Api-Key: {key}（或 Authorization: ApiKey {key}）。
        API Key 归属当前 Agent，调用计入审计与计费。
      </div>
      <div class="tab-actions">
        <el-button type="primary" size="small" @click="openKeyCreate">创建 API Key</el-button>
        <span class="hint">每个 Agent 最多 10 个；0 表示不限制</span>
      </div>
      <el-table :data="apiKeys" size="small">
        <el-table-column prop="name" label="名称" width="150" />
        <el-table-column prop="keyPrefix" label="Key" width="180" />
        <el-table-column prop="channel" label="渠道" width="100" />
        <el-table-column label="限额（日/分钟）" width="140">
          <template #default="{ row }">{{ row.dailyLimit || '不限' }} / {{ row.minuteLimit || '不限' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">
              {{ row.status === 'ENABLED' ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最后使用" width="160">
          <template #default="{ row }">{{ row.lastUsedAt ? String(row.lastUsedAt).replace('T',' ').slice(0,19) : '未使用' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button size="small" text @click="toggleKey(row)">
              {{ row.status === 'ENABLED' ? '禁用' : '启用' }}
            </el-button>
            <el-button size="small" text type="danger" @click="removeKey(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="apiKeys.length === 0" description="暂无 API Key" :image-size="50" />

      <div v-if="createdKey" class="created-key">
        <div class="ck-title">API Key 已创建（仅显示一次，请立即保存）</div>
        <div class="ck-row">
          <code class="ck-value">{{ createdKey }}</code>
          <el-button size="small" type="primary" @click="copyKey">复制</el-button>
        </div>
        <div class="hint">保存后关闭弹窗将无法再次查看明文。</div>
      </div>
    </el-dialog>

    <!-- 创建 API Key -->
    <el-dialog v-model="keyCreateVisible" title="创建 API Key" width="520px">
      <el-form label-width="110px">
        <el-form-item label="名称" required><el-input v-model="keyForm.name" maxlength="64" /></el-form-item>
        <el-form-item label="调用渠道">
          <el-select v-model="keyForm.channel" style="width: 180px">
            <el-option label="API" value="API" />
            <el-option label="Web" value="WEB" />
            <el-option label="第三方集成" value="THIRD_PARTY" />
          </el-select>
        </el-form-item>
        <el-form-item label="日调用上限">
          <el-input-number v-model="keyForm.dailyLimit" :min="0" />
          <span class="hint ml8">0 = 不限制</span>
        </el-form-item>
        <el-form-item label="分钟上限">
          <el-input-number v-model="keyForm.minuteLimit" :min="0" />
          <span class="hint ml8">0 = 不限制</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="keyCreateVisible = false">取消</el-button>
        <el-button type="primary" :loading="keySaving" @click="createKey">创建</el-button>
      </template>
    </el-dialog>

    <!-- Prompt 模板插入 -->
    <el-dialog v-model="templateVisible" title="从 Prompt 模板插入" width="640px">
      <el-select v-model="templateId" filterable style="width: 100%" placeholder="选择模板">
        <el-option v-for="t in promptTemplates" :key="t.id" :value="t.id"
                   :label="t.name + (t.category ? '（' + t.category + '）' : '')" />
      </el-select>
      <div v-if="selectedTemplate" class="tpl-preview">
        <div class="tpl-vars">
          变量：
          <el-tag v-for="v in selectedTemplate.variables || []" :key="v.key" size="small" class="ml8">{{ v.key }}</el-tag>
          <span v-if="!(selectedTemplate.variables || []).length" class="hint">无</span>
        </div>
        <pre class="tpl-content">{{ selectedTemplate.content }}</pre>
      </div>
      <template #footer>
        <el-button @click="templateVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!selectedTemplate" @click="applyTemplate">插入</el-button>
      </template>
    </el-dialog>

    <!-- 能力选择弹窗 -->
    <el-dialog v-model="picker.visible" :title="pickerTitle" width="520px">
      <el-select v-model="picker.selected" multiple filterable style="width: 100%"
                 :placeholder="pickerPlaceholder">
        <el-option v-for="o in pickerOptions" :key="o.id" :value="o.id"
                   :label="o.name + (o.isBuiltin ? '（内置）' : '')" />
      </el-select>
      <div class="hint mt8">只展示未添加项；添加后可在面板内启用/禁用</div>
      <template #footer>
        <el-button @click="picker.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmPicker">添加</el-button>
      </template>
    </el-dialog>

    <!-- 版本抽屉 -->
    <el-drawer v-model="versionsVisible" title="版本历史" size="420px">
      <el-table :data="versions" size="small">
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column prop="createdAt" label="时间" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="rollback(row.version)">回滚</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElLoading, ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import http from '../api/http'

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
const saving = ref(false)

const form = reactive({
  status: 'DRAFT', updatedAt: '',
  name: '', description: '', category: '', visibility: 'TENANT', listed: true,
  modelName: '', temperature: 0.7,
  systemPrompt: '', openingStatement: '',
  capabilities: { streamOutput: true, functionCall: false, multimodal: false },
  memory: { enabled: true, windowSize: 10 },
  variables: [] as VariableRow[]
})
const tagsStr = ref('')
const enabledKbCount = computed(() => kbRows.value.filter(k => k.enabled).length)

interface KbRow { knowledgeBaseId: number; name: string; enabled: boolean; topK: number; scoreThreshold: number; missing?: boolean }
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
const createdKey = ref('')
const keyCreateVisible = ref(false)
const keySaving = ref(false)
const keyForm = reactive({ name: '', channel: 'API', dailyLimit: 0, minuteLimit: 0 })
const promptTemplates = ref<any[]>([])
const templateVisible = ref(false)
const templateId = ref(0)
const selectedTemplate = computed(() => promptTemplates.value.find(t => t.id === templateId.value))

const enabledSkillCount = computed(() => skillRows.value.filter(r => r.enabled && !r.missing).length)
const enabledToolCount = computed(() => toolRows.value.filter(r => r.enabled && !r.missing).length)
const enabledSubCount = computed(() => subRows.value.filter(r => r.enabled && !r.missing).length)
const pickerTitle = computed(() => ({ skill: '添加技能', tool: '添加工具', sub: '添加子 Agent' }[picker.type]))
const pickerPlaceholder = computed(() => ({ skill: '选择技能', tool: '选择工具', sub: '选择本租户 Agent' }[picker.type]))
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
  form.temperature = mc.temperature ?? 0.7
  form.systemPrompt = detail.systemPrompt || ''
  form.openingStatement = detail.openingStatement || ''
  const caps = detail.agentCapabilities || {}
  form.capabilities = { streamOutput: caps.streamOutput !== false, functionCall: !!caps.functionCall, multimodal: !!caps.multimodal }
  const mem = detail.memoryConfig || {}
  form.memory = { enabled: mem.enabled !== false, windowSize: mem.windowSize ?? 10 }
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
      topK: rc.topK ?? 5, scoreThreshold: rc.scoreThreshold ?? 0.5
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
        knowledgeBaseId: b.knowledgeBaseId, name: `已失效（KB #${b.knowledgeBaseId}）`,
        enabled: false, topK: 5, scoreThreshold: 0.5, missing: true
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
      const label = type === 'skill' ? '技能' : type === 'tool' ? '工具' : 'Agent'
      rows.push({ id, name: `已失效（${label} #${id}）`, enabled: false, missing: true })
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
  return toolCatalog.value.find((t: any) => t.id === id)?.name || ('工具 #' + id)
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
  if (!credentialForm.name.trim()) { ElMessage.warning('名称必填'); return }
  if (!credentialForm.id && !credentialForm.content.trim()) { ElMessage.warning('凭证内容必填'); return }
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
    ElMessage.success('已保存')
    credentialFormVisible.value = false
    await loadCredentials()
  } finally {
    credentialSaving.value = false
  }
}

async function removeCredential(row: any) {
  await ElMessageBox.confirm(`删除凭证「${row.name}」？引用该凭证的工具调用将失效。`, '删除凭证',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  await http.delete(`/agent/${agentId}/credentials/${row.id}`)
  ElMessage.success('已删除')
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
    ElMessage.warning('名称与 Cron 必填'); return
  }
  let params: any
  try {
    params = JSON.parse(scheduleForm.inputParamsText || '{}')
  } catch {
    ElMessage.error('输入参数不是合法 JSON'); return
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
    ElMessage.success('已保存')
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
    res.status === 'SUCCESS' ? '执行成功' : ('执行失败：' + res.message))
  await loadSchedules()
}

async function openResults(row: any) {
  resultsTitle.value = `执行结果：${row.name}`
  scheduleResults.value = await http.get(`/agent/${agentId}/schedules/${row.id}/results`) as any
  resultsVisible.value = true
}

async function removeSchedule(row: any) {
  await ElMessageBox.confirm(`删除定时任务「${row.name}」？执行结果将一并删除。`, '删除定时任务',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  await http.delete(`/agent/${agentId}/schedules/${row.id}`)
  ElMessage.success('已删除')
  await loadSchedules()
}

async function openIntegration() {
  integratedAgentId.value = agentId
  createdKey.value = ''
  await loadApiKeys()
  integrationVisible.value = true
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
  if (!keyForm.name.trim()) { ElMessage.warning('名称必填'); return }
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

async function copyKey() {
  try {
    await navigator.clipboard.writeText(createdKey.value)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}

async function toggleKey(row: any) {
  await http.put(`/agent/${agentId}/api-keys/${row.id}`, {
    status: row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  })
  await loadApiKeys()
}

async function removeKey(row: any) {
  await ElMessageBox.confirm(`删除 API Key「${row.name}」？删除后使用该 Key 的调用将立即失败。`, '删除 API Key',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  await http.delete(`/agent/${agentId}/api-keys/${row.id}`)
  ElMessage.success('已删除')
  await loadApiKeys()
}

const integratedAgentId = ref(0)

function modelLabel(m: any) {
  const name = m.displayName || m.modelName
  const c = Number(m.contextWindow || 0)
  return c > 0 ? `${name}（${c >= 1048576 ? (c / 1048576) + 'M' : (c / 1024) + 'K'} 上下文）` : name
}

function openTemplatePicker() {
  templateId.value = 0
  templateVisible.value = true
}

async function applyTemplate() {
  if (!selectedTemplate.value) return
  if (form.systemPrompt.trim()) {
    try {
      await ElMessageBox.confirm('插入模板将覆盖当前提示词，是否继续？', '覆盖提示词', { type: 'warning' })
    } catch { return }
  }
  form.systemPrompt = selectedTemplate.value.content || ''
  templateVisible.value = false
  ElMessage.success('已插入模板，变量可在「占位变量」面板配置')
}

function openPicker(type: RefType) {
  picker.type = type
  picker.selected = []
  picker.visible = true
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

function buildPayload() {
  return {
    name: form.name,
    description: form.description,
    category: form.category,
    tags: tagsStr.value.split(/[,，]/).map(t => t.trim()).filter(Boolean),
    systemPrompt: form.systemPrompt,
    openingStatement: form.openingStatement,
    agentCapabilities: { ...form.capabilities },
    memoryConfig: { ...form.memory },
    modelConfig: { modelName: form.modelName, temperature: form.temperature },
    variables: form.variables.filter(v => v.key),
    visibility: form.visibility,
    knowledgeBases: kbRows.value.filter(k => k.enabled).map(k => ({
      knowledgeBaseId: k.knowledgeBaseId, enabled: true,
      retrievalConfig: { topK: k.topK, scoreThreshold: k.scoreThreshold }
    })),
    skills: skillRows.value.filter(r => !r.missing).map(r => ({ skillId: r.id, enabled: r.enabled })),
    tools: toolRows.value.filter(r => !r.missing).map(r => ({ toolId: r.id, enabled: r.enabled })),
    subAgents: subRows.value.filter(r => !r.missing).map(r => ({ subAgentId: r.id, enabled: r.enabled }))
  }
}

async function save() {
  if (!form.name.trim() || !form.modelName || !form.systemPrompt.trim()) {
    ElMessage.warning('名称、模型、提示词必填')
    return
  }
  saving.value = true
  try {
    const res = await http.put(`/agent/${agentId}`, buildPayload(), {
      headers: form.updatedAt ? { 'If-Match': form.updatedAt } : {}
    }) as any
    form.updatedAt = res.updatedAt
    if (form.visibility === 'PRIVATE') {
      await http.put(`/agent/${agentId}/grants`, { userIds: grantedIds.value }).catch(() => {})
    }
    ElMessage.success('已保存')
  } finally {
    saving.value = false
  }
}

async function publish(force = false) {
  await save()
  // 发布可能先执行预发布门禁评测（每样本约 5-30s），超时放宽至 10 分钟
  const loading = ElLoading.service({
    text: force ? '强制发布中…' : '发布中…若配置了预发布门禁，将先执行评测（可能需数分钟）',
    background: 'rgba(0, 0, 0, 0.4)'
  })
  try {
    const res = await http.post(`/agent/${agentId}/publish`, { force }, { timeout: 600000 }) as any
    form.status = 'PUBLISHED'
    ElMessage.success(`已发布 ${res.version}`)
  } catch (err: any) {
    const msg = String(err?.response?.data?.message || err?.message || err)
    if (msg.includes('预发布测评未通过')) {
      loading.close()
      try {
        await ElMessageBox.confirm(
          `${msg}\n\n是否强制发布？（需 OWNER/ADMIN 权限）`, '预发布门禁未通过',
          { type: 'warning', confirmButtonText: '强制发布', cancelButtonText: '取消' })
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
      await ElMessageBox.confirm('下架后 Agent 广场不可见，已使用的用户仍可继续对话。是否下架？',
        '下架 Agent', { type: 'warning', confirmButtonText: '下架', cancelButtonText: '取消' })
    } catch {
      form.listed = true
      return
    }
  }
  try {
    await http.put(`/square/agents/${agentId}/listing`, { listed: value })
    ElMessage.success(value ? '已上架广场' : '已下架')
  } catch {
    form.listed = !value
  }
}

async function openVersions() {
  versions.value = await http.get(`/agent/${agentId}/versions`) as any
  versionsVisible.value = true
}

async function rollback(version: string) {
  await ElMessageBox.confirm(`回滚到 ${version} 将生成新版本，继续？`, '回滚确认', { type: 'warning' })
  await http.post(`/agent/${agentId}/versions/${version}/rollback`)
  ElMessage.success('已回滚（生成新版本），页面即将刷新')
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
    reply.content = '调试失败：' + (e.message || e)
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
  gap: 12px;
}
.prompt-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.tpl-preview { margin-top: 12px; }
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
</style>
