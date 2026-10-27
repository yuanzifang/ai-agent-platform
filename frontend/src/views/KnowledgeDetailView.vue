<template>
  <div class="page">
    <div class="toolbar">
      <el-button text @click="$router.push('/knowledge')">
        <el-icon><ArrowLeft /></el-icon> {{ t('kbDetail.back') }}
      </el-button>
      <span class="kb-title">{{ detail.name }}</span>
      <el-tag size="small" :type="visTag(detail.visibility)">{{ visText(detail.visibility) }}</el-tag>
      <el-tag v-if="detail.graphEnabled === false" size="small" type="info">{{ t('knowledgeList.graphOff') }}</el-tag>
      <span class="kb-meta">{{ t('kbDetail.metaDocs') }}{{ detail.documentCount }}{{ t('kbDetail.metaChunks') }}{{ detail.chunkCount }}{{ t('kbDetail.creator') }}{{ detail.creatorName || '-' }}</span>
      <el-button v-if="detail.canEdit" @click="openEdit">{{ t('kbDetail.edit') }}</el-button>
      <el-button v-if="detail.graphEnabled !== false" @click="$router.push(`/knowledge/${kbId}/graph`)">{{ t('graphPage.title') }}</el-button>
      <el-button v-if="detail.canEdit" @click="openPermission">{{ t('kbDetail.permission') }}</el-button>
      <el-upload v-if="detail.canEdit" :show-file-list="false" :http-request="uploadFileRequest" multiple
                 accept=".md,.txt,.docx,.pdf,.xlsx,.xls,.pptx,.html,.htm,.csv,.json,.png,.jpg,.jpeg,.bmp,.tif,.tiff">
        <el-button type="primary" :loading="uploading">
          {{ uploading ? t('kbDetail.uploading') : t('kbDetail.batchUpload') }}
        </el-button>
      </el-upload>
      <el-button v-if="detail.canEdit" @click="parseVisible = true">{{ t('kbDetail.parseSettings') }}</el-button>
      <el-button v-if="detail.canEdit" @click="urlVisible = true">{{ t('kbDetail.urlImport') }}</el-button>
      <el-button v-if="detail.canEdit" type="danger" plain @click="removeKb">{{ t('kbDetail.deleteKb') }}</el-button>
    </div>

    <div v-if="detail.canEdit" class="upload-hint">
      {{ t('kbDetail.uploadHint') }}
      <div class="upload-validity">
        <span>{{ t('kbDetail.uploadValidity') }}</span>
        <el-date-picker v-model="uploadEffectiveRange" type="datetimerange" size="small"
                        :start-placeholder="t('kbDetail.validFrom')"
                        :end-placeholder="t('kbDetail.validTo')"
                        style="width: 340px" />
        <span class="hint">{{ t('kbDetail.uploadValidityHint') }}</span>
      </div>
      <el-collapse class="upload-cfg">
        <el-collapse-item :title="t('kbDetail.uploadCfgTitle')" name="cfg">
          <el-form inline label-width="130px" size="small">
            <el-form-item :label="t('kbDetail.poOcrEnabled')">
              <el-switch v-model="uploadCfg.ocrEnabled" />
            </el-form-item>
            <el-form-item :label="t('kbDetail.poOcrLanguages')">
              <el-select v-model="uploadCfg.ocrLanguages" style="width: 160px">
                <el-option label="简体中文 + 英文" value="chi_sim+eng" />
                <el-option label="简体中文" value="chi_sim" />
                <el-option label="English" value="eng" />
                <el-option label="日本語" value="jpn" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('kbDetail.poVisionEnabled')">
              <el-switch v-model="uploadCfg.visionEnabled" />
            </el-form-item>
            <el-form-item :label="t('kbDetail.poVisionModel')">
              <el-select v-model="uploadCfg.visionModel" style="width: 160px">
                <el-option v-for="m in visionModels" :key="m.modelName" :value="m.modelName"
                           :label="m.displayName || m.modelName" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('kbDetail.poPdfPassword')">
              <el-input v-model="uploadCfg.pdfPassword" type="password" show-password clearable
                        style="width: 220px" :placeholder="t('kbDetail.poPdfPasswordHint')" />
            </el-form-item>
          </el-form>
          <div class="hint">{{ t('kbDetail.uploadCfgHint') }}</div>
        </el-collapse-item>
      </el-collapse>
    </div>

    <div v-if="uploadTasks.length" class="upload-tasks">
      <div class="upload-task" v-for="task in uploadTasks" :key="task.id">
        <div class="upload-task-head">
          <span class="upload-task-name">{{ task.fileName }}</span>
          <span :class="['upload-task-status', task.status]">{{ uploadTaskText(task) }}</span>
        </div>
        <el-progress :percentage="task.percent" :stroke-width="6"
                     :status="task.status === 'error' ? 'exception' : task.status === 'done' ? 'success' : undefined" />
        <div v-if="task.message" class="err">{{ task.message }}</div>
      </div>
      <el-button text size="small" @click="clearFinishedUploads">{{ t('kbDetail.clearFinished') }}</el-button>
    </div>

    <div class="body">
      <!-- 文档列表 + 分段 -->
      <div class="docs">
        <div class="sec-title">{{ t('kbDetail.docs') }}{{ documents.length }}）
          <el-switch v-model="validOnly" size="small" />
          <span class="hint">{{ t('kbDetail.validOnly') }}</span>
          <el-button v-if="detail.canEdit" size="small" text type="primary"
                     :loading="validityRebuilding" @click="rebuildValidity">
            {{ t('kbDetail.rebuildValidity') }}
          </el-button>
          <el-button v-if="detail.canEdit && (legacyDocCount > 0
              || upgradeJob?.status === 'RUNNING' || upgradeJob?.status === 'PAUSED')"
                     size="small" text type="primary"
                     :disabled="upgradeJob?.status === 'RUNNING' || upgradeJob?.status === 'PAUSED'"
                     :loading="legacyReparsing" @click="reparseLegacy">
            {{ upgradeJob?.status === 'RUNNING'
              ? tf('kbDetail.upgradeRunning', { submitted: upgradeJob.submittedDocuments,
                  total: upgradeJob.totalDocuments })
              : upgradeJob?.status === 'PAUSED'
                ? t('kbDetail.upgradePaused')
                : t('kbDetail.reparseLegacy') + '（' + legacyDocCount + '）' }}
          </el-button>
          <span v-if="upgradeJob?.status === 'RUNNING'" class="hint">
            {{ tf('kbDetail.upgradeProgress', { succeeded: upgradeJob.succeededDocuments,
                failed: upgradeJob.failedDocuments, pending: upgradeJob.pendingDocuments }) }}
          </span>
          <span v-else-if="upgradeJob?.status === 'FAILED'" class="hint stale-parse">
            {{ t('kbDetail.upgradeFailed') }}（{{ upgradeJob.failedDocuments }}）
          </span>
          <span v-else-if="upgradeJob?.status === 'PAUSED'" class="hint">
            {{ t('kbDetail.upgradePausedHint') }}
          </span>
          <span v-else-if="upgradeJob?.status === 'CANCELLED'" class="hint">
            {{ t('kbDetail.upgradeCancelled') }}
          </span>
          <el-button v-if="upgradeJob?.status === 'RUNNING'" size="small" text type="warning"
                     @click="pauseUpgrade">{{ t('kbDetail.upgradePause') }}</el-button>
          <el-button v-if="upgradeJob?.status === 'PAUSED'" size="small" text type="primary"
                     @click="resumeUpgrade">{{ t('kbDetail.upgradeResume') }}</el-button>
          <el-button v-if="upgradeJob?.status === 'RUNNING' || upgradeJob?.status === 'PAUSED'"
                     size="small" text type="danger"
                     @click="cancelUpgrade">{{ t('kbDetail.upgradeCancel') }}</el-button>
          <el-button v-if="detail.canEdit" size="small" text type="warning"
                     :loading="recoveringStale" @click="recoverStale">
            {{ t('kbDetail.recoverStale') }}
          </el-button>
          <el-button v-if="detail.canEdit && failedDocCount > 0" size="small" text type="primary"
                     :loading="failedRetrying" @click="retryFailedDocs">
            {{ t('kbDetail.retryFailed') }}（{{ failedDocCount }}）
          </el-button>
          <el-button v-if="detail.canEdit && hashMissingCount > 0" size="small" text type="primary"
                     :loading="hashBackfilling" @click="backfillHashes">
            {{ t('kbDetail.backfillHashes') }}（{{ hashMissingCount }}）
          </el-button>
        </div>
        <el-table :data="visibleDocuments" size="small" @row-click="toggleChunks">
          <el-table-column prop="fileName" :label="t('kbDetail.fileName')" min-width="180" />
          <el-table-column :label="t('kbDetail.status')" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
              <div v-if="row.status === 'PARSING' && parseAgeMinutes(row) !== null"
                   class="hint" :class="{ 'stale-parse': parseIsStale(row) }">
                {{ parseAgeText(row) }}
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="chunkCount" :label="t('kbDetail.chunks')" width="70" />
          <el-table-column :label="t('kbDetail.validity')" width="150">
            <template #default="{ row }">
              <el-tag size="small" :type="validityTagType(row)">{{ validityText(row) }}</el-tag>
              <div class="validity-range">{{ validityRangeText(row) }}</div>
            </template>
          </el-table-column>
          <el-table-column :label="t('kbDetail.error')" min-width="160">
            <template #default="{ row }">
              <span class="err">{{ row.errorMessage || '' }}</span>
              <el-tag v-if="row.manualReviewRequired" size="small" type="warning">
                {{ t('kbDetail.manualReview') }}
              </el-tag>
              <span v-if="row.parseWarning" class="warn">{{ row.parseWarning }}</span>
              <span v-if="row.retryTimes > 0" class="retry-tip">{{ t('kbDetail.retriedPrefix') }}{{ row.retryTimes }}{{ t('kbDetail.retriedSuffix') }}</span>
            </template>
          </el-table-column>
          <el-table-column v-if="detail.canEdit" :label="t('kbDetail.actions')" width="400">
            <template #default="{ row }">
              <el-upload v-if="row.status === 'DONE' || row.status === 'FAILED'" :show-file-list="false"
                         :http-request="(options: any) => reuploadRequest(row, options)"
                         accept=".md,.txt,.docx,.pdf,.xlsx,.xls,.pptx,.html,.htm,.csv,.json,.png,.jpg,.jpeg,.bmp,.tif,.tiff">
                <el-button size="small" text type="primary">{{ t('kbDetail.reupload') }}</el-button>
              </el-upload>
              <el-button v-if="row.status === 'FAILED'" size="small" text type="warning"
                         @click.stop="retryDoc(row)">{{ t('kbDetail.retry') }}</el-button>
              <el-button v-if="reparseAvailable(row)" size="small" text type="primary"
                         :loading="row.reparseLoading" @click.stop="reparseDoc(row)">{{ t('kbDetail.reparse') }}</el-button>
              <el-button size="small" text type="primary"
                         @click.stop="openValidity(row)">{{ t('kbDetail.validity') }}</el-button>
              <el-button v-if="isPdf(row) && (row.status === 'DONE' || row.status === 'FAILED')"
                         size="small" text type="primary" @click.stop="openPdfPassword(row)">
                {{ t('kbDetail.pdfPassword') }}
              </el-button>
              <el-button size="small" text type="danger" @click.stop="removeDoc(row)">{{ t('kbDetail.deleteKb') }}</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="chunks.docId" class="chunks">
          <div class="sec-title">
            {{ t('kbDetail.chunkPreview') }}{{ chunks.docName }}{{ t('kbDetail.totalChunks') }}{{ chunks.total }}{{ t('kbDetail.totalSuffix') }}
            <el-button size="small" text @click="chunks.docId = null">{{ t('kbDetail.collapse') }}</el-button>
          </div>
          <div v-for="c in chunks.list" :key="c.id" class="chunk">
            <span class="idx">#{{ c.chunkIndex }}<template v-if="c.pageFrom"> · p.{{ c.pageFrom }}<template v-if="c.pageTo && c.pageTo !== c.pageFrom">-{{ c.pageTo }}</template></template></span>
            <span class="chunk-meta" v-if="c.documentType || c.sectionType || c.regulatoryCategory">
              <el-tag v-if="c.documentType" size="small" type="info">{{ c.documentType }}</el-tag>
              <el-tag v-if="c.sectionType" size="small">{{ c.sectionType }}</el-tag>
              <el-tag v-if="c.regulatoryCategory" size="small" type="warning">{{ c.regulatoryCategory }}</el-tag>
              <el-tag v-if="c.chunkLevel" size="small" type="success">L{{ c.chunkLevel }}</el-tag>
            </span>
            <span class="text">{{ c.content }}</span>
            <el-tag v-if="isChartChunk(c)" size="small"
                    :type="c.reviewStatus === 'REVIEWED' ? 'success' : 'warning'">
              {{ c.reviewStatus === 'REVIEWED' ? t('kbDetail.chartReviewed') : t('kbDetail.chartNeedsReview') }}
            </el-tag>
            <el-button v-if="detail.canEdit && isChartChunk(c)" size="small" text type="primary"
                       @click.stop="openReviewEdit(c)">{{ t('kbDetail.editChart') }}</el-button>
            <el-button v-if="detail.canEdit && isChartChunk(c)" size="small" text type="primary"
                       :loading="reviewSavingId === c.id" @click.stop="toggleChunkReview(c)">
              {{ c.reviewStatus === 'REVIEWED' ? t('kbDetail.undoReview') : t('kbDetail.markReviewed') }}
            </el-button>
            <el-button v-if="detail.canEdit" size="small" text type="primary"
                       @click="openChunkAcl(c)">{{ t('kbDetail.authorize') }}</el-button>
          </div>
        </div>
      </div>

      <!-- 检索测试 -->
      <div class="retrieval">
        <div class="sec-title">{{ t('kbDetail.searchTitle') }}</div>
        <el-input v-model="search.query" :placeholder="t('kbDetail.searchPh')" @keydown.enter="doSearch" />
        <div class="params">
          <el-select v-model="search.mode" size="small" style="width: 100px">
            <el-option :label="t('kbDetail.hybrid')" value="hybrid" />
            <el-option :label="t('kbDetail.vector')" value="vector" />
            <el-option :label="t('kbDetail.keyword')" value="keyword" />
          </el-select>
          <el-date-picker v-model="search.range" type="datetimerange" size="small"
                          :start-placeholder="t('kbDetail.timeFrom')"
                          :end-placeholder="t('kbDetail.timeTo')"
                          style="width: 300px" />
          <span class="hint">{{ t('kbDetail.timeFilterHint') }}</span>
          topK <el-input-number v-model="search.topK" :min="1" :max="20" size="small" />
          {{ t('kbDetail.threshold') }} <el-input-number v-model="search.scoreThreshold" :min="0" :max="1" :step="0.05" size="small" />
          <el-button size="small" type="primary" :loading="searching" @click="doSearch">{{ t('kbDetail.search') }}</el-button>
        </div>
        <div class="params meta-params">
          <span class="hint">{{ t('kbDetail.metaFilters') }}</span>
          <el-select v-model="search.documentType" size="small" clearable
                     :placeholder="t('kbDetail.docType')" style="width: 130px">
            <el-option :label="t('kbDetail.docTypeResearch')" value="research_paper" />
            <el-option :label="t('kbDetail.docTypeRegulatory')" value="regulatory" />
            <el-option :label="t('kbDetail.docTypeClinical')" value="clinical_trial" />
            <el-option :label="t('kbDetail.docTypeGeneral')" value="general" />
          </el-select>
          <el-select v-model="search.sectionType" size="small" clearable
                     :placeholder="t('kbDetail.sectionType')" style="width: 130px">
            <el-option v-for="s in sectionOptions" :key="s" :label="s" :value="s" />
          </el-select>
          <el-select v-model="search.regulatoryCategory" size="small" clearable
                     :placeholder="t('kbDetail.regulatoryCategory')" style="width: 130px">
            <el-option label="FDA" value="FDA" />
            <el-option label="EMA" value="EMA" />
            <el-option label="ICH" value="ICH" />
          </el-select>
          <el-select v-model="search.chunkLevel" size="small" clearable
                     :placeholder="t('kbDetail.chunkLevel')" style="width: 110px">
            <el-option v-for="n in [1, 2, 3, 4]" :key="n" :label="String(n)" :value="n" />
          </el-select>
          {{ t('kbDetail.minRelevance') }}
          <el-input-number v-model="search.minRelevance" :min="0" :max="1" :step="0.05"
                           size="small" style="width: 110px" />
        </div>
        <el-empty v-if="searched && results.length === 0" :description="t('kbDetail.noHits')" :image-size="60" />
        <div v-for="(r, i) in results" :key="i" class="hit">
          <div class="hit-head">
            <el-progress :percentage="Math.round(r.score * 100)" :stroke-width="10"
                         :color="r.score > 0.6 ? '#67c23a' : '#e6a23c'" class="score" />
            <span class="pct">{{ (r.score * 100).toFixed(1) }}%</span>
          </div>
          <div class="hit-text">{{ r.content }}</div>
          <div v-if="hitMeta(r).document_type || hitMeta(r).section_type || hitMeta(r).regulatory_category"
               class="hit-meta">
            <el-tag v-if="hitMeta(r).document_type" size="small" type="info">
              {{ hitMeta(r).document_type }}
            </el-tag>
            <el-tag v-if="hitMeta(r).section_type" size="small">
              {{ hitMeta(r).section_type }}
            </el-tag>
            <el-tag v-if="hitMeta(r).regulatory_category" size="small" type="warning">
              {{ hitMeta(r).regulatory_category }}
            </el-tag>
            <el-tag v-if="hitMeta(r).chunk_level" size="small" type="success">
              L{{ hitMeta(r).chunk_level }}
            </el-tag>
            <span v-if="hitMeta(r).relevance_score != null" class="hint">
              relevance {{ hitMeta(r).relevance_score }}
            </span>
          </div>
          <div v-if="hitEffectiveTime(r)" class="hit-time">
            {{ t('kbDetail.effectiveAt') }}{{ hitEffectiveTime(r) }}
          </div>
        </div>
      </div>
    </div>

    <!-- 解析设置（跟随默认/自定义） -->
    <el-dialog v-model="parseVisible" :title="t('kbDetail.parseTitle')" width="640px">
      <el-form label-width="150px">
        <el-divider content-position="left">OCR（扫描件/图片文字）</el-divider>
        <el-form-item :label="t('kbDetail.poOcrEnabled')">
          <el-switch v-model="po.ocrEnabled" />
        </el-form-item>
        <el-form-item :label="t('kbDetail.poOcrLanguages')">
          <el-select v-model="po.ocrLanguages" style="width: 220px">
            <el-option label="简体中文 + 英文" value="chi_sim+eng" />
            <el-option label="简体中文" value="chi_sim" />
            <el-option label="English" value="eng" />
            <el-option label="日本語" value="jpn" />
            <el-option label="한국어" value="kor" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('kbDetail.poOcrDpi')">
          <el-input-number v-model="po.ocrDpi" :min="72" :max="400" :step="10" />
        </el-form-item>
        <el-form-item :label="t('kbDetail.poOcrMaxPages')">
          <el-input-number v-model="po.ocrMaxPages" :min="1" :max="200" />
        </el-form-item>
        <el-divider content-position="left">图片理解（视觉模型描述图片）</el-divider>
        <el-form-item :label="t('kbDetail.poVisionEnabled')">
          <el-switch v-model="po.visionEnabled" />
        </el-form-item>
        <el-form-item :label="t('kbDetail.poVisionModel')">
          <el-select v-model="po.visionModel" style="width: 220px"
                     :placeholder="visionModels.length ? undefined : t('kbDetail.noVisionModels')">
            <el-option v-for="m in visionModels" :key="m.modelName" :value="m.modelName"
                       :label="m.displayName || m.modelName" />
          </el-select>
          <div v-if="!visionModels.length" class="hint">{{ t('kbDetail.noVisionHint') }}</div>
        </el-form-item>
        <el-form-item :label="t('kbDetail.poVisionMaxImages')">
          <el-input-number v-model="po.visionMaxImages" :min="1" :max="10" />
        </el-form-item>
      </el-form>
      <div class="hint">默认值来自 系统设置 → 知识库解析默认值；此处为本知识库的覆盖配置。</div>
      <template #footer>
        <el-button @click="resetParseToDefault">{{ t('kbDetail.poFollowDefault') }}</el-button>
        <el-button type="primary" :loading="parseSaving" @click="saveParse">{{ t('kbDetail.poSave') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" :title="t('kbDetail.editTitle')" width="480px">
      <el-form label-width="70px">
        <el-form-item :label="t('kbDetail.name')" required>
          <el-input v-model="editForm.name" maxlength="128" />
        </el-form-item>
        <el-form-item :label="t('kbDetail.desc')">
          <el-input v-model="editForm.description" type="textarea" :rows="2" maxlength="512" />
        </el-form-item>
        <el-form-item :label="t('knowledgeList.graphEnabled')">
          <el-switch v-model="editForm.graphEnabled" />
          <span class="hint">{{ t('knowledgeList.graphHint') }}</span>
          <div class="hint">{{ t('knowledgeList.graphRebuildHint') }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">{{ t('kbDetail.cancel') }}</el-button>
        <el-button type="primary" :loading="editSaving" @click="saveEdit">{{ t('kbDetail.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- 权限设置（需求 3.6.3(5)：只有我 / 所有团队成员 / 部分团队成员） -->
    <el-dialog v-model="urlVisible" :title="t('kbDetail.urlTitle')" width="540px">
      <el-form label-width="70px">
        <el-form-item label="URL" required>
          <el-input v-model="urlForm.url" placeholder="https://example.com/manual.html" />
        </el-form-item>
        <el-form-item :label="t('kbDetail.contentValidity')">
          <el-date-picker v-model="urlForm.effectiveRange" type="datetimerange" size="small"
                          :start-placeholder="t('kbDetail.validFrom')"
                          :end-placeholder="t('kbDetail.validTo')"
                          style="width: 320px" />
        </el-form-item>
      </el-form>
      <div class="hint">{{ t('kbDetail.urlHint') }}</div>
      <template #footer>
        <el-button @click="urlVisible = false">{{ t('kbDetail.cancel') }}</el-button>
        <el-button type="primary" :loading="urlImporting" @click="importUrl">{{ t('kbDetail.import') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="permVisible" :title="t('kbDetail.permTitle')" width="620px">
      <el-form label-width="90px">
        <el-form-item :label="t('kbDetail.visibility')">
          <el-radio-group v-model="perm.visibility">
            <el-radio value="PRIVATE">{{ t('kbDetail.private') }}</el-radio>
            <el-radio value="TENANT">{{ t('kbDetail.tenantAll') }}</el-radio>
            <el-radio value="PARTIAL">{{ t('kbDetail.partial') }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="perm.visibility === 'PARTIAL'" :label="t('kbDetail.grantedMembers')">
          <el-table :data="grantableMembers" size="small" max-height="300" class="member-table">
            <el-table-column width="46">
              <template #default="{ row }">
                <el-checkbox :model-value="isGranted(row.id)" @change="toggleGrant(row.id)" />
              </template>
            </el-table-column>
            <el-table-column :label="t('kbDetail.member')">
              <template #default="{ row }">{{ memberLabel(row) }}</template>
            </el-table-column>
            <el-table-column :label="t('kbDetail.perm')" width="130">
              <template #default="{ row }">
                <el-select :model-value="permOf(row.id)" :disabled="!isGranted(row.id)"
                           size="small" @change="(v: string) => setPerm(row.id, v)">
                  <el-option :label="t('kbDetail.view')" value="VIEW" />
                  <el-option :label="t('kbDetail.editPerm')" value="EDIT" />
                </el-select>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="grantableMembers.length === 0" :description="t('kbDetail.noGrantable')" :image-size="50" />
        </el-form-item>
        <div class="perm-tip">
          <div>{{ t('kbDetail.permTip1') }}</div>
          <div>{{ t('kbDetail.permTip2') }}</div>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="permVisible = false">{{ t('kbDetail.cancel') }}</el-button>
        <el-button type="primary" :loading="savingPerm" @click="savePermission">{{ t('kbDetail.save') }}</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="imagesVisible" :title="`${t('kbDetail.imagesTitle')}${imagesDocName}`" width="760px">
      <el-empty v-if="!documentImages.length" :description="t('kbDetail.noImages')" :image-size="60" />
      <div v-else class="image-grid">
        <div v-for="img in documentImages" :key="img.id" class="image-card">
          <el-image :src="img.url" fit="contain" style="width: 100%; height: 150px" />
          <div class="hint">{{ img.fileName }} · {{ img.mimeType }}</div>
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="pdfPasswordVisible" :title="t('kbDetail.pdfPasswordTitle')" width="480px">
      <el-input v-model="pdfPasswordValue" type="password" show-password
                :placeholder="t('kbDetail.pdfPasswordPlaceholder')" />
      <div class="hint" style="margin-top:8px">{{ t('kbDetail.pdfPasswordHint') }}</div>
      <template #footer>
        <el-button @click="pdfPasswordVisible = false">{{ t('kbDetail.cancel') }}</el-button>
        <el-button type="primary" :loading="pdfPasswordSaving" @click="savePdfPassword">
          {{ t('kbDetail.saveAndReparse') }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="validityVisible" :title="t('kbDetail.validityTitle')" width="520px">
      <el-form label-width="120px">
        <el-form-item :label="t('kbDetail.validityFrom')">
          <el-date-picker v-model="validityFrom" type="datetime" clearable style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('kbDetail.validityTo')">
          <el-date-picker v-model="validityTo" type="datetime" clearable style="width: 100%" />
        </el-form-item>
      </el-form>
      <div class="hint">{{ t('kbDetail.validityHint') }}</div>
      <template #footer>
        <el-button @click="validityVisible = false">{{ t('kbDetail.cancel') }}</el-button>
        <el-button type="primary" :loading="validitySaving" @click="saveValidity">
          {{ t('kbDetail.save') }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reviewEditVisible" :title="t('kbDetail.editChartTitle')" width="680px">
      <div class="hint" style="margin-bottom:8px">{{ t('kbDetail.editChartHint') }}</div>
      <el-input v-model="reviewEditBody" type="textarea" :rows="10"
                :placeholder="t('kbDetail.editChartPlaceholder')" />
      <template #footer>
        <el-button @click="reviewEditVisible = false">{{ t('kbDetail.cancel') }}</el-button>
        <el-button type="primary" :loading="reviewEditSaving" @click="saveReviewEdit">
          {{ t('kbDetail.saveCorrection') }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="aclVisible" :title="t('kbDetail.aclTitle')" width="520px">
      <el-form label-width="100px">
        <el-form-item :label="t('kbDetail.tenantVisible')">
          <el-switch v-model="aclForm.tenantVisible" />
          <span class="hint" style="margin-left:8px">{{ t('kbDetail.tenantVisibleHint') }}</span>
        </el-form-item>
        <el-form-item :label="t('kbDetail.assignedUsers')">
          <el-select v-model="aclForm.users" multiple filterable style="width:100%"
                     :disabled="aclForm.tenantVisible" :placeholder="t('kbDetail.selectUsers')">
            <el-option v-for="m in aclMembers" :key="m.id" :value="m.id"
                       :label="(m.displayName || m.username) + ' (' + m.id + ')'" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="aclVisible = false">{{ t('kbDetail.cancel') }}</el-button>
        <el-button type="primary" :loading="aclSaving" @click="saveChunkAcl">{{ t('kbDetail.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import http from '../api/http'

const { t } = useI18n()

/** 项目里 i18n 消息的花括号被统一转义，这里做一次手动命名参数替换 */
function tf(key: string, params: Record<string, unknown>): string {
  let text = t(key)
  for (const [name, value] of Object.entries(params)) {
    text = text.split(`{${name}}`).join(String(value ?? ''))
  }
  return text
}

const route = useRoute()
const router = useRouter()
const kbId = Number(route.params.id)
const detail = reactive<Record<string, any>>({
  name: '', visibility: 'TENANT', creatorId: null, creatorName: '', documentCount: 0, chunkCount: 0,
  canEdit: false, graphEnabled: false
})
const documents = ref<any[]>([])
const validOnly = ref(false)
const validityRebuilding = ref(false)
const legacyReparsing = ref(false)
const upgradeJob = ref<any>(null)
let upgradeTimer: ReturnType<typeof setTimeout> | null = null
const recoveringStale = ref(false)
const failedRetrying = ref(false)
const hashBackfilling = ref(false)
const failedDocCount = computed(() => documents.value.filter((d: any) => d.status === 'FAILED').length)
const hashMissingCount = computed(() => documents.value.filter(
  (d: any) => d.status === 'DONE' && !d.sourceSha256).length)
const uploadTasks = ref<any[]>([])
let uploadSeq = 0
const visibleDocuments = computed(() => validOnly.value
  ? documents.value.filter(doc => validityState(doc) === 'valid')
  : documents.value)
const legacyDocCount = computed(() => documents.value.filter((doc: any) =>
  doc.upgradePending ?? (doc.status === 'DONE' && !doc.parseFingerprint)).length)
const uploading = computed(() => uploadTasks.value.some(
  task => task.status === 'uploading' || task.status === 'parsing'))
const urlVisible = ref(false)
const urlImporting = ref(false)
const urlForm = reactive({ url: '', effectiveRange: null as Date[] | null })
const searching = ref(false)
const searched = ref(false)
const results = ref<any[]>([])
const sectionOptions = ['abstract', 'introduction', 'methods', 'results', 'discussion', 'conclusion', 'references', 'body']
const search = reactive({
  query: '', topK: 5, scoreThreshold: 0.5, mode: 'hybrid', range: null as Date[] | null,
  documentType: '', sectionType: '', regulatoryCategory: '', chunkLevel: undefined as number | undefined,
  minRelevance: undefined as number | undefined
})
const chunks = reactive<{ docId: number | null; docName: string; list: any[]; total: number }>({
  docId: null, docName: '', list: [], total: 0
})
const imagesVisible = ref(false)
const documentImages = ref<any[]>([])
const imagesDocName = ref('')
const aclVisible = ref(false)
const aclSaving = ref(false)
const reviewSavingId = ref<number | null>(null)
const reviewEditVisible = ref(false)
const reviewEditSaving = ref(false)
const reviewEditChunk = ref<any>(null)
const reviewEditBody = ref('')
const CHART_CORRECTED_PREFIX = '【图表数据·人工修正】'
const validityVisible = ref(false)
const validitySaving = ref(false)
const pdfPasswordVisible = ref(false)
const pdfPasswordSaving = ref(false)
const pdfPasswordDoc = ref<any>(null)
const pdfPasswordValue = ref('')
const validityDoc = ref<any>(null)
const validityFrom = ref<Date | null>(null)
const validityTo = ref<Date | null>(null)
const aclChunk = ref<any>(null)
const aclMembers = ref<any[]>([])
const aclForm = reactive<{ tenantVisible: boolean; users: number[] }>({ tenantVisible: true, users: [] })

// ---- 编辑/删除 ----
const editVisible = ref(false)
const editSaving = ref(false)
const editForm = reactive({
  name: '', description: '', graphEnabled: false, parseConfig: {} as Record<string, any>
})

// ---- 权限设置 ----
const permVisible = ref(false)
const savingPerm = ref(false)
const members = ref<any[]>([])
const perm = reactive<{ visibility: string; members: Record<number, string> }>({
  visibility: 'TENANT', members: {}
})
const grantableMembers = computed(() =>
  members.value.filter(m => m.id !== detail.creatorId && !(m.roles || []).includes('RESTRICTED')))

onMounted(async () => {
  await loadDetail()
  await loadDocs()
  await loadUpgradeJob()
})

onUnmounted(() => {
  if (upgradeTimer) clearTimeout(upgradeTimer)
})

async function loadDetail() {
  const data = await http.get(`/knowledge-base/${kbId}`) as any
  Object.assign(detail, data)
  loadParse()
  loadVisionModels()
}

async function loadDocs() {
  documents.value = await http.get(`/knowledge-base/${kbId}/documents`) as any
}

/** 解析中轮询刷新（异步解析，需求 3.6.3(2)）。 */
function pollDocs(times = 15) {
  setTimeout(async () => {
    await Promise.all([loadDocs(), loadDetail()])
    if (times > 0 && documents.value.some(d => d.status === 'PENDING' || d.status === 'PARSING')) {
      pollDocs(times - 1)
    }
  }, 2000)
}

function openEdit() {
  editForm.name = detail.name
  editForm.description = detail.description || ''
  editForm.graphEnabled = detail.graphEnabled !== false
  editForm.parseConfig = detail.parseConfig || {}
  editVisible.value = true
}

async function saveEdit() {
  if (!editForm.name.trim()) { ElMessage.warning(t('kbDetail.nameRequired')); return }
  editSaving.value = true
  try {
    await http.put(`/knowledge-base/${kbId}`, {
      name: editForm.name.trim(), description: editForm.description,
      graphEnabled: editForm.graphEnabled, parseConfig: editForm.parseConfig
    })
    ElMessage.success(t('kbDetail.saved'))
    editVisible.value = false
    await loadDetail()
  } finally {
    editSaving.value = false
  }
}

async function removeKb() {
  try {
    const refs = await http.get(`/knowledge-base/${kbId}/references`) as any
    const agents: string[] = refs?.agents || []
    const msg = agents.length
      ? `${t('kbDetail.deleteKbRefsPrefix')}${agents.length}${t('kbDetail.deleteKbRefsMid')}${agents.join('、')}${t('kbDetail.deleteKbRefsSuffix')}`
      : `${t('kbDetail.deleteKbConfirmPrefix')}${detail.name}${t('kbDetail.deleteKbConfirmMid')}`
    await ElMessageBox.confirm(msg, t('kbDetail.deleteKbTitle'), {
      type: 'warning', confirmButtonText: t('kbDetail.delete'), cancelButtonText: t('kbDetail.cancel')
    })
    await http.delete(`/knowledge-base/${kbId}`, { params: { force: agents.length > 0 } })
    ElMessage.success(t('kbDetail.deleted'))
    router.push('/knowledge')
  } catch {
    // 用户取消或请求失败（拦截器已提示）
  }
}

async function removeDoc(row: any) {
  try {
    await ElMessageBox.confirm(`${t('kbDetail.deleteDocPrefix')}${row.fileName}${t('kbDetail.deleteDocMid')}`,
      t('kbDetail.deleteDocTitle'), { type: 'warning', confirmButtonText: t('kbDetail.delete'), cancelButtonText: t('kbDetail.cancel') })
    await http.delete(`/knowledge-base/${kbId}/documents/${row.id}`)
    ElMessage.success(t('kbDetail.deleted'))
    if (chunks.docId === row.id) chunks.docId = null
    await Promise.all([loadDocs(), loadDetail()])
  } catch {
    // 用户取消或请求失败
  }
}

// ---- 本次上传解析配置（可选覆盖） ----
const uploadCustomEnabled = ref(false)
const uploadEffectiveRange = ref<Date[] | null>(null)
const uploadCfg = reactive({
  ocrEnabled: false, ocrLanguages: 'chi_sim+eng',
  visionEnabled: false, visionModel: 'qwen3-vl:32b', pdfPassword: ''
})
// 用户修改任一上传解析项即启用本次覆盖（含 PDF 密码）。
watch(uploadCfg, () => { uploadCustomEnabled.value = true }, { deep: true })

// ---- 视觉模型选项（模型广场 purpose=VISION） ----
const visionModels = ref<any[]>([])
async function loadVisionModels() {
  try {
    const all = await http.get('/provider/models') as any
    visionModels.value = (all || []).filter((m: any) => String(m.purpose || '').toUpperCase() === 'VISION')
  } catch { visionModels.value = [] }
}

// ---- 解析设置 ----
const parseVisible = ref(false)
const parseSaving = ref(false)
const po = reactive({
  ocrEnabled: false, ocrLanguages: 'chi_sim+eng', ocrDpi: 150, ocrMaxPages: 20,
  visionEnabled: false, visionModel: 'qwen3-vl:32b', visionMaxImages: 3
})

function loadParse() {
  // KB 自定义优先展示；无自定义回落解析链默认值
  const src = (detail.parseConfig && Object.keys(detail.parseConfig).length) ? detail.parseConfig : detail.parseResolved
  if (src) Object.assign(po, {
    ocrEnabled: !!src.ocrEnabled, ocrLanguages: src.ocrLanguages || 'chi_sim+eng',
    ocrDpi: Number(src.ocrDpi ?? 150), ocrMaxPages: Number(src.ocrMaxPages ?? 20),
    visionEnabled: !!src.visionEnabled, visionModel: src.visionModel || 'qwen3-vl:32b',
    visionMaxImages: Number(src.visionMaxImages ?? 3)
  })
}

function resetParseToDefault() {
  if (detail.parseResolved) Object.assign(po, {
    ocrEnabled: !!detail.parseResolved.ocrEnabled,
    ocrLanguages: detail.parseResolved.ocrLanguages || 'chi_sim+eng',
    ocrDpi: Number(detail.parseResolved.ocrDpi ?? 150),
    ocrMaxPages: Number(detail.parseResolved.ocrMaxPages ?? 20),
    visionEnabled: !!detail.parseResolved.visionEnabled,
    visionModel: detail.parseResolved.visionModel || 'qwen3-vl:32b',
    visionMaxImages: Number(detail.parseResolved.visionMaxImages ?? 3)
  })
}

async function saveParse() {
  parseSaving.value = true
  try {
    const cfg = {
      ocrEnabled: po.ocrEnabled, ocrLanguages: po.ocrLanguages, ocrDpi: po.ocrDpi,
      ocrMaxPages: po.ocrMaxPages, visionEnabled: po.visionEnabled,
      visionModel: po.visionModel, visionMaxImages: po.visionMaxImages
    }
    await http.put(`/knowledge-base/${kbId}`, { parseConfig: cfg })
    ElMessage.success(t('kbDetail.parseSaved'))
    parseVisible.value = false
    detail.parseConfig = cfg
  } finally {
    parseSaving.value = false
  }
}

// 重新解析：仅当知识库或该文档配置了自定义解析设置时显示（否则重跑结果不变）
function reparseAvailable(row: any) {
  const legacy = row.status === 'DONE' && !row.parseFingerprint
  const kbCustom = detail.parseConfig && Object.keys(detail.parseConfig).length > 0
  return legacy || !!kbCustom || !!row.parseConfig
}

async function reparseDoc(row: any) {
  row.reparseLoading = true
  try {
    await http.post(`/knowledge-base/${kbId}/documents/${row.id}/reparse`)
    ElMessage.success(t('kbDetail.reparseSubmitted'))
    await loadDocs()
    pollDocs()
  } finally {
    row.reparseLoading = false
  }
}

async function loadUpgradeJob() {
  try {
    const data = await http.get(`/knowledge-base/${kbId}/documents/upgrade-jobs/latest`) as any
    if (data?.jobId) {
      upgradeJob.value = data
      if (data.status === 'RUNNING') pollUpgrade()
    }
  } catch { /* 旧版本没有升级任务接口时忽略 */ }
}

async function reparseLegacy() {
  legacyReparsing.value = true
  try {
    upgradeJob.value = await http.post(`/knowledge-base/${kbId}/documents/upgrade-jobs`) as any
    ElMessage.success(t('kbDetail.upgradeStarted'))
    await loadDocs()
    pollUpgrade()
  } finally {
    legacyReparsing.value = false
  }
}

function pollUpgrade() {
  if (upgradeTimer) clearTimeout(upgradeTimer)
  upgradeTimer = setTimeout(async () => {
    try {
      const data = await http.get(`/knowledge-base/${kbId}/documents/upgrade-jobs/latest`) as any
      if (data?.jobId) upgradeJob.value = data
      await loadDocs()
      if (data?.status === 'RUNNING') pollUpgrade()
    } catch { /* 轮询失败下一轮页面刷新会恢复 */ }
  }, 3000)
}

async function pauseUpgrade() {
  if (!upgradeJob.value?.jobId) return
  upgradeJob.value = await http.post(
    `/knowledge-base/${kbId}/documents/upgrade-jobs/${upgradeJob.value.jobId}/pause`) as any
  if (upgradeTimer) {
    clearTimeout(upgradeTimer)
    upgradeTimer = null
  }
  ElMessage.success(t('kbDetail.upgradePausedMsg'))
  await loadDocs()
}

async function resumeUpgrade() {
  if (!upgradeJob.value?.jobId) return
  upgradeJob.value = await http.post(
    `/knowledge-base/${kbId}/documents/upgrade-jobs/${upgradeJob.value.jobId}/resume`) as any
  ElMessage.success(t('kbDetail.upgradeResumedMsg'))
  await loadDocs()
  pollUpgrade()
}

async function cancelUpgrade() {
  if (!upgradeJob.value?.jobId) return
  await ElMessageBox.confirm(t('kbDetail.upgradeCancelConfirm'),
    t('kbDetail.upgradeCancel'), { type: 'warning' })
  upgradeJob.value = await http.post(
    `/knowledge-base/${kbId}/documents/upgrade-jobs/${upgradeJob.value.jobId}/cancel`) as any
  if (upgradeTimer) {
    clearTimeout(upgradeTimer)
    upgradeTimer = null
  }
  ElMessage.success(t('kbDetail.upgradeCancelled'))
  await loadDocs()
}

async function recoverStale() {
  await ElMessageBox.confirm(t('kbDetail.recoverStaleConfirm'), t('kbDetail.recoverStale'), { type: 'warning' })
  recoveringStale.value = true
  try {
    const data = await http.post(`/knowledge-base/${kbId}/documents/recover-stale`) as any
    ElMessage.success(`${t('kbDetail.recoverStaleDone')} (${data?.recovered || 0})`)
    await loadDocs()
  } finally {
    recoveringStale.value = false
  }
}

function parseAgeMinutes(row: any): number | null {
  if (!row?.parseStartedAt) return null
  const started = new Date(row.parseStartedAt).getTime()
  if (!Number.isFinite(started)) return null
  return Math.max(0, Math.floor((Date.now() - started) / 60000))
}

function parseIsStale(row: any): boolean {
  const minutes = parseAgeMinutes(row)
  return minutes !== null && minutes >= 360
}

function parseAgeText(row: any): string {
  const minutes = parseAgeMinutes(row)
  if (minutes === null) return ''
  return minutes >= 360
    ? t('kbDetail.parseStale')
    : `${t('kbDetail.parsingForPrefix')}${minutes}${t('kbDetail.parsingForSuffix')}`
}

async function backfillHashes() {
  hashBackfilling.value = true
  try {
    const data = await http.post(`/knowledge-base/${kbId}/documents/backfill-hashes`, null,
      { params: { limit: 20 } }) as any
    ElMessage.success(t('kbDetail.backfillHashesDone')
      + `${data?.updated || 0}/${data?.scanned || 0}`)
    await loadDocs()
  } finally {
    hashBackfilling.value = false
  }
}

async function retryFailedDocs() {
  failedRetrying.value = true
  try {
    const data = await http.post(`/knowledge-base/${kbId}/documents/retry-failed`, null,
      { params: { limit: 20 } }) as any
    ElMessage.success(`${t('kbDetail.retryFailedSubmitted')} (${data?.triggered || 0}/${data?.scanned || 0})`)
    await loadDocs()
    pollDocs()
  } finally {
    failedRetrying.value = false
  }
}

async function retryDoc(row: any) {
  try {
    await http.post(`/knowledge-base/${kbId}/documents/${row.id}/retry`)
    ElMessage.success(t('kbDetail.retrySubmitted'))
    if (chunks.docId === row.id) chunks.docId = null
    await loadDocs()
    pollDocs()
  } catch {
    // 拦截器已提示（如达到重试上限）
  }
}

/** 预签名直传：POST 拿 URL → PUT 文件 → callback 登记（需求 3.6）。 */
async function uploadFileRequest(options: any) {
  await startUpload(options.file, null, options)
}

async function reuploadRequest(row: any, options: any) {
  await startUpload(options.file, row, options)
}

/** 单文件上传：预签名 → XHR 进度 → callback；replaceRow 非空时覆盖该文档。 */
async function startUpload(file: File, replaceRow: any, options?: any) {
  const task: any = {
    id: ++uploadSeq,
    fileName: file.name,
    percent: 0,
    status: 'uploading',
    message: '',
    documentId: replaceRow?.id || null
  }
  uploadTasks.value.push(task)
  try {
    const presign = await http.post(`/knowledge-base/${kbId}/documents`, {
      fileName: file.name,
      sizeBytes: file.size,
      contentType: file.type || 'application/octet-stream',
      documentId: replaceRow?.id || null
    }) as any
    task.percent = 1
    await putWithProgress(presign.uploadUrl, file, task)
    task.status = 'parsing'
    task.percent = 100
    const cbBody: any = { objectName: presign.objectName }
    if (replaceRow?.id) cbBody.documentId = replaceRow.id
    const effectiveFrom = uploadEffectiveRange.value?.[0]
    const effectiveTo = uploadEffectiveRange.value?.[1]
    if (effectiveFrom) cbBody.effectiveFrom = new Date(effectiveFrom).toISOString()
    if (effectiveTo) cbBody.effectiveTo = new Date(effectiveTo).toISOString()
    if (uploadCustomEnabled.value || uploadCfg.pdfPassword) {
      const parseConfig: any = {
        ocrEnabled: uploadCfg.ocrEnabled, ocrLanguages: uploadCfg.ocrLanguages,
        visionEnabled: uploadCfg.visionEnabled, visionModel: uploadCfg.visionModel
      }
      if (uploadCfg.pdfPassword) parseConfig.pdfPassword = uploadCfg.pdfPassword
      cbBody.parseConfig = parseConfig
    }
    const cb = await http.post(`/knowledge-base/${kbId}/documents/callback`, cbBody) as any
    task.status = 'done'
    task.documentId = cb?.documentId || task.documentId
    task.message = ''
    options?.onSuccess?.(cb)
    ElMessage.success(t('kbDetail.uploaded'))
    await loadDocs()
    pollDocs()
  } catch (e: any) {
    task.status = 'error'
    task.message = e?.message || String(e)
    options?.onError?.(e)
    ElMessage.error(t('kbDetail.uploadError') + (task.message || e))
  }
}

function putWithProgress(url: string, file: File, task: any): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', url)
    xhr.setRequestHeader('Content-Type', file.type || 'application/octet-stream')
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) {
        task.percent = Math.min(99, Math.round(event.loaded / event.total * 100))
      }
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) resolve()
      else reject(new Error(t('kbDetail.uploadFailed') + xhr.status))
    }
    xhr.onerror = () => reject(new Error(t('kbDetail.uploadFailed') + 'network'))
    xhr.send(file)
  })
}

function uploadTaskText(task: any) {
  if (task.status === 'uploading') return `${t('kbDetail.uploadProgress')} ${task.percent}%`
  if (task.status === 'parsing') return t('kbDetail.uploadParsing')
  if (task.status === 'done') return t('kbDetail.uploadDone')
  return t('kbDetail.uploadTaskFailed')
}

function clearFinishedUploads() {
  uploadTasks.value = uploadTasks.value.filter(
    task => task.status === 'uploading' || task.status === 'parsing')
}

async function importUrl() {
  if (!urlForm.url.trim()) { ElMessage.warning(t('kbDetail.urlRequired')); return }
  urlImporting.value = true
  try {
    const range = urlForm.effectiveRange
    await http.post(`/knowledge-base/${kbId}/documents/url`, {
      url: urlForm.url.trim(),
      effectiveFrom: range?.[0] ? new Date(range[0]).toISOString() : undefined,
      effectiveTo: range?.[1] ? new Date(range[1]).toISOString() : undefined
    })
    ElMessage.success(t('kbDetail.urlSubmitted'))
    urlVisible.value = false
    urlForm.url = ''
    urlForm.effectiveRange = null
    await loadDocs()
    pollDocs()
  } finally {
    urlImporting.value = false
  }
}

async function toggleChunks(row: any) {
  if (chunks.docId === row.id) { chunks.docId = null; return }
  const data = await http.get(`/knowledge-base/${kbId}/documents/${row.id}/chunks`,
    { params: { pageSize: 20 } }) as any
  chunks.docId = row.id
  chunks.docName = row.fileName
  chunks.list = data.list || []
  chunks.total = data.total || 0
}

async function openImages(row: any) {
  imagesDocName.value = row.fileName
  try {
    documentImages.value = (await http.get(`/knowledge-base/${kbId}/documents/${row.id}/images`) as any) || []
    imagesVisible.value = true
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || t('kbDetail.imageLoadFailed'))
  }
}

async function openChunkAcl(c: any) {
  aclChunk.value = c
  let users = c.allowedUsers
  if (typeof users === 'string') { try { users = JSON.parse(users) } catch { users = [] } }
  aclForm.users = (users || []).map((x: any) => Number(x)).filter((x: number) => !Number.isNaN(x))
  aclForm.tenantVisible = c.tenantVisible !== false
  if (!aclMembers.value.length) {
    try { aclMembers.value = (await http.get('/users') as any) || [] } catch { /* 权限不足时仍可编辑已有 ID */ }
  }
  aclVisible.value = true
}

async function saveChunkAcl() {
  if (!aclChunk.value) return
  aclSaving.value = true
  try {
    await http.put(`/knowledge-base/${kbId}/chunks/${aclChunk.value.id}/acl`, {
      allowedUsers: aclForm.users, tenantVisible: aclForm.tenantVisible
    })
    ElMessage.success(t('kbDetail.chunkAclSaved'))
    aclVisible.value = false
    await toggleChunks({ id: chunks.docId, fileName: chunks.docName })
  } finally {
    aclSaving.value = false
  }
}

function isChartChunk(c: any) {
  return typeof c?.content === 'string' && c.content.startsWith('【图表数据')
}

function openReviewEdit(c: any) {
  const content = typeof c?.content === 'string' ? c.content : ''
  const end = content.indexOf('】')
  reviewEditBody.value = content.startsWith('【图表数据') && end >= 0
    ? content.slice(end + 1).trim() : content
  reviewEditChunk.value = c
  reviewEditVisible.value = true
}

async function saveReviewEdit() {
  if (!reviewEditChunk.value) return
  const content = CHART_CORRECTED_PREFIX + reviewEditBody.value.trim()
  reviewEditSaving.value = true
  try {
    const data = await http.put(`/knowledge-base/${kbId}/chunks/${reviewEditChunk.value.id}/review`, {
      reviewed: true, content
    }) as any
    reviewEditChunk.value.content = data.content
    reviewEditChunk.value.reviewStatus = data.reviewStatus
    reviewEditChunk.value.reviewRequired = false
    reviewEditVisible.value = false
    ElMessage.success(t('kbDetail.editChartSaved'))
  } finally {
    reviewEditSaving.value = false
  }
}

function validityState(row: any) {
  const now = Date.now()
  const from = row?.effectiveFrom || row?.createdAt
  if (from && new Date(from).getTime() > now) return 'future'
  if (row?.effectiveTo && new Date(row.effectiveTo).getTime() < now) return 'expired'
  return 'valid'
}

function validityText(row: any) {
  const state = validityState(row)
  return state === 'future' ? t('kbDetail.notYetValid')
    : state === 'expired' ? t('kbDetail.expired') : t('kbDetail.validNow')
}

function validityTagType(row: any) {
  const state = validityState(row)
  return state === 'future' ? 'warning' : state === 'expired' ? 'danger' : 'success'
}

function validityRangeText(row: any) {
  const from = row?.effectiveFrom || row?.createdAt
  const to = row?.effectiveTo
  if (!from && !to) return '-'
  return `${from ? String(from).slice(0, 10) : '-'} ~ ${to ? String(to).slice(0, 10) : t('kbDetail.longTerm')}`
}

async function rebuildValidity() {
  try {
    await ElMessageBox.confirm(t('kbDetail.rebuildValidityConfirm'), t('kbDetail.rebuildValidity'),
      { type: 'warning' })
  } catch {
    return
  }
  validityRebuilding.value = true
  try {
    const data = await http.post(`/knowledge-base/${kbId}/validity/rebuild`) as any
    ElMessage.success(t('kbDetail.rebuildValidityDone') + (data?.documents || 0))
  } finally {
    validityRebuilding.value = false
  }
}

function isPdf(row: any) {
  return typeof row?.fileName === 'string' && row.fileName.toLowerCase().endsWith('.pdf')
}

function openPdfPassword(row: any) {
  pdfPasswordDoc.value = row
  pdfPasswordValue.value = ''
  pdfPasswordVisible.value = true
}

async function savePdfPassword() {
  if (!pdfPasswordDoc.value) return
  pdfPasswordSaving.value = true
  try {
    await http.post(`/knowledge-base/${kbId}/documents/${pdfPasswordDoc.value.id}/pdf-password`, {
      password: pdfPasswordValue.value
    })
    pdfPasswordVisible.value = false
    ElMessage.success(t('kbDetail.pdfPasswordSaved'))
    await loadDocs()
    pollDocs()
  } finally {
    pdfPasswordSaving.value = false
  }
}

function openValidity(row: any) {
  validityDoc.value = row
  validityFrom.value = row?.effectiveFrom ? new Date(row.effectiveFrom) : null
  validityTo.value = row?.effectiveTo ? new Date(row.effectiveTo) : null
  validityVisible.value = true
}

async function saveValidity() {
  if (!validityDoc.value) return
  validitySaving.value = true
  try {
    const body = {
      effectiveFrom: validityFrom.value ? new Date(validityFrom.value).toISOString() : null,
      effectiveTo: validityTo.value ? new Date(validityTo.value).toISOString() : null
    }
    const data = await http.put(
      `/knowledge-base/${kbId}/documents/${validityDoc.value.id}/validity`, body) as any
    validityDoc.value.effectiveFrom = data.effectiveFrom
    validityDoc.value.effectiveTo = data.effectiveTo
    validityVisible.value = false
    ElMessage.success(t('kbDetail.validitySaved'))
  } finally {
    validitySaving.value = false
  }
}

async function toggleChunkReview(c: any) {
  if (!c) return
  const reviewed = c.reviewStatus !== 'REVIEWED'
  reviewSavingId.value = c.id
  try {
    const data = await http.put(`/knowledge-base/${kbId}/chunks/${c.id}/review`, { reviewed }) as any
    c.reviewStatus = data.reviewStatus
    c.reviewRequired = !reviewed
    ElMessage.success(t('kbDetail.reviewSaved'))
  } finally {
    reviewSavingId.value = null
  }
}

function hitEffectiveTime(r: any) {
  if (r?.effective_from) return r.effective_from
  try {
    const meta = typeof r?.metadata === 'string' ? JSON.parse(r.metadata) : r?.metadata
    return meta?.effective_from || meta?.document_created_at || r?.created_at || ''
  } catch {
    return r?.created_at || ''
  }
}

function hitMeta(hit: any): Record<string, any> {
  try {
    return hit?.metadata ? JSON.parse(String(hit.metadata)) : {}
  } catch {
    return {}
  }
}

async function doSearch() {
  if (!search.query.trim()) return
  searching.value = true
  searched.value = true
  try {
    const range = search.range
    const effectiveAfter = range?.[0] ? new Date(range[0]).toISOString() : undefined
    const effectiveBefore = range?.[1] ? new Date(range[1]).toISOString() : undefined
    results.value = await http.post(`/knowledge-base/${kbId}/search`, {
      query: search.query, topK: search.topK, scoreThreshold: search.scoreThreshold,
      mode: search.mode, effectiveAfter, effectiveBefore,
      documentType: search.documentType || undefined,
      sectionType: search.sectionType || undefined,
      regulatoryCategory: search.regulatoryCategory || undefined,
      chunkLevel: search.chunkLevel,
      minRelevance: search.minRelevance
    }) as any
  } finally {
    searching.value = false
  }
}

async function openPermission() {
  try {
    const [data, ms] = await Promise.all([
      http.get(`/knowledge-base/${kbId}/permissions`) as any,
      http.get('/users') as any
    ])
    perm.visibility = data.visibility || 'TENANT'
    perm.members = {}
    for (const m of (data.members || [])) perm.members[m.userId] = m.permission || 'VIEW'
    members.value = ms || []
    permVisible.value = true
  } catch (e: any) {
    ElMessage.error(t('kbDetail.permissionLoadFailed') + (e.message || e))
  }
}

function isGranted(id: number) {
  return id in perm.members
}
function permOf(id: number) {
  return perm.members[id] || 'VIEW'
}
function toggleGrant(id: number) {
  if (id in perm.members) delete perm.members[id]
  else perm.members[id] = 'VIEW'
}
function setPerm(id: number, value: string) {
  perm.members[id] = value
}
function memberLabel(m: any) {
  return m.displayName ? `${m.displayName}（${m.username}）` : m.username
}

async function savePermission() {
  savingPerm.value = true
  try {
    const membersPayload = Object.entries(perm.members)
      .map(([userId, permission]) => ({ userId: Number(userId), permission }))
    await http.put(`/knowledge-base/${kbId}/permissions`, {
      visibility: perm.visibility, members: membersPayload
    })
    ElMessage.success(t('kbDetail.permissionSaved'))
    permVisible.value = false
    await loadDetail()
  } finally {
    savingPerm.value = false
  }
}

function visText(v: string) {
  return v === 'PRIVATE' ? t('kbDetail.visPrivate') : v === 'PARTIAL' ? t('kbDetail.visPartial') : t('kbDetail.visTenant')
}
function visTag(v: string) {
  return v === 'PRIVATE' ? 'info' : v === 'PARTIAL' ? 'warning' : 'success'
}
function statusType(s: string) {
  return s === 'DONE' ? 'success' : s === 'PARSING' ? 'warning' : s === 'FAILED' ? 'danger' : 'info'
}
function statusText(s: string) {
  return s === 'DONE' ? t('kbDetail.done') : s === 'PARSING' ? t('kbDetail.parsing') : s === 'FAILED' ? t('kbDetail.failed') : t('kbDetail.pending')
}
</script>

<style scoped>
.upload-tasks { margin: 0 4px 12px; display: flex; flex-direction: column; gap: 8px; }
.upload-task { background: #fafafa; border: 1px solid #ebeef5; border-radius: 6px; padding: 8px 10px; }
.upload-task-head { display: flex; justify-content: space-between; gap: 10px; font-size: 12px; margin-bottom: 5px; }
.upload-task-name { color: #303133; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.upload-task-status { color: #909399; white-space: nowrap; }
.upload-task-status.done { color: #67c23a; }
.upload-task-status.error { color: #f56c6c; }
.upload-cfg { margin-top: 6px; }
.upload-cfg :deep(.el-form-item) { margin-bottom: 6px; }
.upload-hint { font-size: 12px; color: #909399; margin: 0 0 10px 4px; }
.upload-validity { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.page { height: 100%; display: flex; flex-direction: column; }
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.kb-title { font-weight: 600; font-size: 16px; }
.kb-meta { color: #909399; font-size: 12px; flex: 1; }
.body { flex: 1; display: flex; gap: 16px; min-height: 0; }
.docs { flex: 1.3; background: #fff; border: 1px solid #e4e7ed; border-radius: 6px; padding: 14px; overflow-y: auto; }
.retrieval { flex: 1; background: #fff; border: 1px solid #e4e7ed; border-radius: 6px; padding: 14px; overflow-y: auto; }
.sec-title { font-weight: 600; font-size: 14px; margin-bottom: 10px; display: flex; align-items: center; gap: 8px; }
.err { color: #f56c6c; font-size: 12px; }
.warn { color: #e6a23c; font-size: 12px; display: block; margin-top: 2px; }
.retry-tip { color: #e6a23c; font-size: 12px; }
.chunks { margin-top: 14px; border-top: 1px dashed #dcdfe6; padding-top: 10px; }
.chunk { display: flex; gap: 8px; font-size: 13px; padding: 6px 0; border-bottom: 1px dashed #ebeef5; }
.idx { color: #409eff; flex-shrink: 0; }
.text { color: #606266; flex: 1; min-width: 0; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.params { display: flex; align-items: center; gap: 8px; margin: 10px 0; font-size: 13px; color: #606266; }
.hit { border: 1px solid #ebeef5; border-radius: 6px; padding: 10px; margin-bottom: 10px; }
.hit-head { display: flex; align-items: center; gap: 8px; }
.score { flex: 1; }
.pct { font-size: 12px; color: #909399; width: 48px; text-align: right; }
.hit-text { font-size: 13px; color: #303133; margin-top: 6px; white-space: pre-wrap; }
.hit-time { font-size: 11px; color: #a8abb2; margin-top: 4px; }
.validity-range { font-size: 11px; color: #a8abb2; margin-top: 2px; }
.member-table { width: 100%; }
.hint { color: #909399; font-size: 12px; margin-left: 8px; }
.meta-params { margin-top: 6px; }
.hit-meta { display: flex; align-items: center; gap: 6px; margin-top: 6px; flex-wrap: wrap; }
.chunk-meta { display: inline-flex; gap: 4px; margin-left: 6px; }
.stale-parse { color: #f56c6c; margin-left: 0; }
.perm-tip { color: #909399; font-size: 12px; line-height: 20px; padding-left: 90px; }

@media (max-width: 768px) {
  /* 让整页随 el-main 滚动，而不是固定高度里塞两栏 */
  .page { height: auto; }
  .toolbar { flex-wrap: wrap; gap: 8px; }
  .kb-title { font-size: 15px; }
  .kb-meta { flex-basis: 100%; }
  .upload-validity { flex-wrap: wrap; }
  .upload-validity .el-date-editor { width: 100% !important; }
  .body { flex-direction: column; }
  .docs,
  .retrieval { flex: none; padding: 10px; overflow: visible; }
  .params { flex-wrap: wrap; }
  .params .el-date-editor { width: 100% !important; }
  .params .hint { flex-basis: 100%; margin-left: 0; }
  .perm-tip { padding-left: 0; }
}
</style>