<template>
  <div class="knowledge-container">
    <!-- 统计 -->
    <a-row :gutter="16" class="stat-row">
      <a-col :xs="12" :md="6">
        <a-card :bordered="false" class="stat-card">
          <a-statistic :title="t('knowledge.statDocs')" :value="stats.docCount" />
        </a-card>
      </a-col>
      <a-col :xs="12" :md="6">
        <a-card :bordered="false" class="stat-card">
          <a-statistic :title="t('knowledge.statChunks')" :value="stats.chunkCount" />
        </a-card>
      </a-col>
      <a-col :xs="12" :md="6">
        <a-card :bordered="false" class="stat-card">
          <a-statistic :title="t('knowledge.statSuccess')" :value="stats.successCount" :value-style="{ color: '#3f8600' }" />
        </a-card>
      </a-col>
      <a-col :xs="12" :md="6">
        <a-card :bordered="false" class="stat-card">
          <a-statistic :title="t('knowledge.statFailed')" :value="stats.failedCount" :value-style="{ color: stats.failedCount > 0 ? '#cf1322' : undefined }" />
        </a-card>
      </a-col>
    </a-row>

    <!-- 筛选 -->
    <a-card class="search-card">
      <a-form layout="inline">
        <a-form-item :label="t('knowledge.category')">
          <a-select
            v-model:value="searchForm.category"
            :placeholder="t('common.all')"
            style="width: 160px"
            allow-clear
            @change="debouncedSearch"
          >
            <a-select-option v-for="c in categories" :key="c" :value="c">{{ c }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item :label="t('knowledge.status')">
          <a-select
            v-model:value="searchForm.status"
            :placeholder="t('common.all')"
            style="width: 140px"
            allow-clear
            @change="debouncedSearch"
          >
            <a-select-option value="success">{{ t('knowledge.statusSuccess') }}</a-select-option>
            <a-select-option value="processing">{{ t('knowledge.statusProcessing') }}</a-select-option>
            <a-select-option value="failed">{{ t('knowledge.statusFailed') }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item :label="t('knowledge.scope')">
          <a-select
            v-model:value="searchForm.scope"
            :placeholder="t('common.all')"
            style="width: 140px"
            allow-clear
            @change="debouncedSearch"
          >
            <a-select-option value="private">{{ t('knowledge.scopePrivate') }}</a-select-option>
            <a-select-option value="shared">{{ t('knowledge.scopeShared') }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item :label="t('knowledge.keyword')">
          <a-input
            v-model:value="searchForm.keyword"
            :placeholder="t('knowledge.keywordPlaceholder')"
            allow-clear
            style="width: 200px"
            @pressEnter="debouncedSearch"
          />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" @click="debouncedSearch">
            <template #icon><SearchOutlined /></template>
            {{ t('common.search') }}
          </a-button>
        </a-form-item>
      </a-form>
    </a-card>

    <!-- 列表 -->
    <a-card :title="t('knowledge.docList')" :bordered="false" style="margin-top: 16px">
      <template #extra>
        <a-button v-permission="'system:knowledge:api:upload'" type="primary" @click="openUpload">
          <template #icon><UploadOutlined /></template>
          {{ t('knowledge.upload') }}
        </a-button>
      </template>

      <a-table
        class="ellipsis-table"
        :columns="columns"
        :data-source="dataSource"
        :loading="loading"
        :pagination="pagination"
        :scroll="{ x: 1100 }"
        row-key="id"
        @change="handleTableChange"
      >
        <template #emptyText>
          <TableEmptyState :error="loadError" @retry="retryLoad" />
        </template>

        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'title'">
            <a-tooltip :title="record.title" placement="leftTop">
              <span class="ellipsis-text">{{ record.title }}</span>
            </a-tooltip>
          </template>

          <template v-else-if="column.key === 'category'">
            <a-tag v-if="record.category">{{ record.category }}</a-tag>
            <span v-else class="muted">{{ t('knowledge.uncategorized') }}</span>
          </template>

          <template v-else-if="column.key === 'scope'">
            <a-tag :color="record.scope === 'shared' ? 'blue' : 'default'">
              {{ record.scope === 'shared' ? t('knowledge.scopeShared') : t('knowledge.scopePrivate') }}
            </a-tag>
          </template>

          <template v-else-if="column.key === 'status'">
            <a-tooltip :title="record.errorMsg || ''">
              <a-tag :color="statusColor(record.status)">{{ statusText(record.status) }}</a-tag>
            </a-tooltip>
          </template>

          <template v-else-if="column.key === 'charCount'">
            {{ record.charCount ?? 0 }}
          </template>

          <template v-else-if="column.key === 'chunkCount'">
            {{ record.chunkCount ?? 0 }}
          </template>

          <template v-else-if="column.key === 'action'">
            <a-space>
              <a-button type="link" size="small" @click="showDetail(record)">
                {{ t('common.detail') }}
              </a-button>
              <a-popconfirm
                :title="t('knowledge.deleteConfirm')"
                :ok-text="t('common.confirm')"
                :cancel-text="t('common.cancel')"
                @confirm="handleDelete(record)"
              >
                <a-button v-permission="'system:knowledge:api:delete'" type="link" size="small" danger>
                  {{ t('common.delete') }}
                </a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- 上传弹窗 -->
    <a-modal
      v-model:open="uploadVisible"
      :title="t('knowledge.uploadTitle')"
      :confirm-loading="ingesting"
      :ok-text="t('knowledge.startIngest')"
      :ok-button-props="{ disabled: !selectedFile }"
      @ok="handleIngest"
    >
      <a-alert type="info" show-icon :message="t('knowledge.uploadTip')" style="margin-bottom: 16px" />

      <a-form layout="vertical">
        <a-form-item :label="t('knowledge.selectFile')" required>
          <a-upload
            :file-list="fileList"
            :max-count="1"
            :accept="fileValidators.knowledge.accept"
            :before-upload="beforeUpload"
            @remove="onRemoveFile"
          >
            <a-button>
              <template #icon><UploadOutlined /></template>
              {{ t('knowledge.chooseFile') }}
            </a-button>
          </a-upload>
          <div class="muted small">{{ t('knowledge.fileTip') }}</div>
        </a-form-item>

        <a-form-item :label="t('knowledge.category')">
          <a-input
            v-model:value="uploadForm.category"
            :placeholder="t('knowledge.categoryPlaceholder')"
            allow-clear
          />
        </a-form-item>

        <a-form-item :label="t('knowledge.scope')">
          <a-radio-group v-model:value="uploadForm.scope">
            <a-radio value="private">{{ t('knowledge.scopePrivate') }}</a-radio>
            <a-radio v-permission="'system:knowledge:manage'" value="shared">
              {{ t('knowledge.scopeShared') }}
            </a-radio>
          </a-radio-group>
          <div class="muted small">{{ t('knowledge.scopeTip') }}</div>
        </a-form-item>

        <a-form-item :label="t('knowledge.sourceUrl')">
          <a-input v-model:value="uploadForm.url" placeholder="https://" allow-clear />
        </a-form-item>

        <a-form-item :label="t('knowledge.publishDate')">
          <a-date-picker
            v-model:value="uploadForm.publishDate"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 详情弹窗（分块目录，不含正文） -->
    <a-modal
      v-model:open="detailVisible"
      :title="t('knowledge.detailTitle')"
      :footer="null"
      width="720px"
    >
      <a-descriptions :column="2" bordered size="small">
        <a-descriptions-item :label="t('knowledge.columnTitle')" :span="2">
          {{ detailDoc?.title }}
        </a-descriptions-item>
        <a-descriptions-item :label="t('knowledge.category')">
          {{ detailDoc?.category || t('knowledge.uncategorized') }}
        </a-descriptions-item>
        <a-descriptions-item :label="t('knowledge.scope')">
          {{ detailDoc?.scope === 'shared' ? t('knowledge.scopeShared') : t('knowledge.scopePrivate') }}
        </a-descriptions-item>
        <a-descriptions-item :label="t('knowledge.status')">
          {{ detailDoc ? statusText(detailDoc.status) : '' }}
        </a-descriptions-item>
        <a-descriptions-item :label="t('knowledge.charCountLabel')">
          {{ detailDoc?.charCount ?? 0 }}
        </a-descriptions-item>
        <a-descriptions-item v-if="detailDoc?.url" :label="t('knowledge.sourceUrl')" :span="2">
          <a :href="detailDoc.url" target="_blank" rel="noreferrer">{{ detailDoc.url }}</a>
        </a-descriptions-item>
        <a-descriptions-item v-if="detailDoc?.errorMsg" :label="t('knowledge.errorMsg')" :span="2">
          <span style="color: #cf1322">{{ detailDoc.errorMsg }}</span>
        </a-descriptions-item>
      </a-descriptions>

      <a-divider orientation="left">{{ t('knowledge.chunkList') }}</a-divider>
      <a-table
        :columns="chunkColumns"
        :data-source="detailChunks"
        :pagination="false"
        size="small"
        row-key="id"
      />
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { message } from 'ant-design-vue'
import { SearchOutlined, UploadOutlined } from '@ant-design/icons-vue'
import type { TableColumnsType, UploadFile } from 'ant-design-vue'
import type {
  KnowledgeChunk,
  KnowledgeDoc,
  KnowledgeQueryParams,
  KnowledgeScope,
  KnowledgeStats,
} from '@/types/knowledge'
import {
  createKnowledgeDoc,
  deleteKnowledgeDoc,
  queryKnowledgeCategories,
  queryKnowledgeDetail,
  queryKnowledgeDocs,
  queryKnowledgeStats,
} from '@/services/knowledge'
import { useTable } from '@/composables/useTable'
import TableEmptyState from '@/components/TableEmptyState.vue'
import { fileValidators } from '@/utils/fileValidators'

const { t } = useI18n()

// ---------------- 统计 ----------------
const stats = ref<KnowledgeStats>({
  docCount: 0,
  successCount: 0,
  failedCount: 0,
  processingCount: 0,
  chunkCount: 0,
  charCount: 0,
})

async function loadStats() {
  try {
    const res = await queryKnowledgeStats()
    if (res.data) {
      stats.value = res.data
    }
  } catch {
    // 统计失败不影响列表，保持旧值即可
  }
}

// ---------------- 归类下拉 ----------------
const categories = ref<string[]>([])

async function loadCategories() {
  try {
    const res = await queryKnowledgeCategories()
    categories.value = res.data || []
  } catch {
    categories.value = []
  }
}

// ---------------- 列表 ----------------
const searchForm = reactive<KnowledgeQueryParams>({
  category: undefined,
  status: '',
  scope: '',
  keyword: '',
})

const {
  loading,
  data: dataSource,
  loadData,
  loadError,
  pagination,
  handleTableChange,
} = useTable<KnowledgeDoc, KnowledgeQueryParams>(queryKnowledgeDocs, () => searchForm)

function retryLoad() {
  loadData(queryKnowledgeDocs)
}

/** 搜索防抖：输入停顿后再请求，避免每敲一个字打一次后端 */
let searchTimer: ReturnType<typeof setTimeout> | null = null
function debouncedSearch() {
  if (searchTimer) {
    clearTimeout(searchTimer)
  }
  searchTimer = setTimeout(() => {
    loadData(queryKnowledgeDocs)
  }, 300)
}

const columns = computed<TableColumnsType>(() => [
  { title: t('knowledge.columnTitle'), dataIndex: 'title', key: 'title', width: 260 },
  { title: t('knowledge.category'), dataIndex: 'category', key: 'category', width: 120, align: 'center' },
  { title: t('knowledge.scope'), dataIndex: 'scope', key: 'scope', width: 100, align: 'center' },
  { title: t('knowledge.status'), dataIndex: 'status', key: 'status', width: 100, align: 'center' },
  { title: t('knowledge.charCountLabel'), dataIndex: 'charCount', key: 'charCount', width: 90, align: 'center' },
  { title: t('knowledge.chunkCountLabel'), dataIndex: 'chunkCount', key: 'chunkCount', width: 80, align: 'center' },
  { title: t('common.createTime'), dataIndex: 'createTime', key: 'createTime', width: 170, align: 'center' },
  { title: t('table.action'), key: 'action', width: 140, align: 'center', fixed: 'right' },
])

const chunkColumns: TableColumnsType = [
  { title: t('knowledge.chunkIndex'), dataIndex: 'chunkIndex', width: 80, align: 'center' },
  { title: t('knowledge.chunkHeading'), dataIndex: 'heading' },
  { title: t('knowledge.charCountLabel'), dataIndex: 'charCount', width: 100, align: 'center' },
]

function statusText(status: string) {
  if (status === 'success') return t('knowledge.statusSuccess')
  if (status === 'failed') return t('knowledge.statusFailed')
  return t('knowledge.statusProcessing')
}

function statusColor(status: string) {
  if (status === 'success') return 'green'
  if (status === 'failed') return 'red'
  return 'blue'
}

// ---------------- 上传（两步式） ----------------
const uploadVisible = ref(false)
const ingesting = ref(false)
const selectedFile = ref<File | null>(null)
const fileList = ref<UploadFile[]>([])

const uploadForm = reactive<{
  category: string
  scope: KnowledgeScope
  url: string
  publishDate: string | undefined
}>({
  category: '',
  scope: 'private',
  url: '',
  publishDate: undefined,
})

function openUpload() {
  uploadForm.category = ''
  uploadForm.scope = 'private'
  uploadForm.url = ''
  uploadForm.publishDate = undefined
  selectedFile.value = null
  fileList.value = []
  uploadVisible.value = true
}

/**
 * 只收下文件交给后面的「开始入库」，不在这里发请求：入库前要等用户确认归类与可见性。
 *
 * 格式与大小都走 `fileValidators.knowledge`，与 `:accept` 共用同一份扩展名清单 ——
 * 不在视图里写字面量，避免"选择器放行了、校验这一步又拦掉"。
 */
function beforeUpload(file: File) {
  const result = fileValidators.knowledge.validate(file)
  if (result !== true) {
    // 校验器只返回 i18n key，翻译与展示留给调用方（与音频/图片上传一致）
    message.error(t(result))
    return false
  }
  selectedFile.value = file
  fileList.value = [
    { uid: file.name, name: file.name, status: 'done', size: file.size } as UploadFile,
  ]
  return false
}

function onRemoveFile() {
  selectedFile.value = null
  fileList.value = []
}

async function handleIngest() {
  if (!selectedFile.value) {
    return
  }
  ingesting.value = true
  try {
    // 一个请求搞定：文件 + 元信息一起 multipart 提交，后端校验 → 解析 → 入库
    const created = await createKnowledgeDoc({
      file: selectedFile.value,
      category: uploadForm.category || undefined,
      scope: uploadForm.scope,
      url: uploadForm.url || undefined,
      publishDate: uploadForm.publishDate,
    })
    if (created.code === 200) {
      message.success(
        t('knowledge.ingestSuccess', {
          chunks: created.data?.chunkCount ?? 0,
          chars: created.data?.charCount ?? 0,
        }),
      )
      uploadVisible.value = false
      loadData(queryKnowledgeDocs)
      loadStats()
      loadCategories()
    } else {
      message.error(created.message || t('knowledge.ingestFailed'))
    }
  } catch (e) {
    // 传输层错误由 request.ts 拦截器统一提示，这里只兜底
    message.error(t('knowledge.ingestFailed'))
  } finally {
    ingesting.value = false
  }
}

// ---------------- 详情 ----------------
const detailVisible = ref(false)
const detailDoc = ref<KnowledgeDoc | null>(null)
const detailChunks = ref<KnowledgeChunk[]>([])

async function showDetail(record: KnowledgeDoc) {
  try {
    const res = await queryKnowledgeDetail(record.id)
    if (res.code === 200 && res.data) {
      detailDoc.value = res.data.doc
      detailChunks.value = res.data.chunks || []
      detailVisible.value = true
    } else {
      message.error(res.message || t('common.loadDataFailed'))
    }
  } catch {
    message.error(t('common.loadDataFailed'))
  }
}

// ---------------- 删除 ----------------
async function handleDelete(record: KnowledgeDoc) {
  try {
    const res = await deleteKnowledgeDoc(record.id)
    if (res.code === 200) {
      message.success(t('knowledge.deleteSuccess'))
      loadData(queryKnowledgeDocs)
      loadStats()
    } else {
      message.error(res.message || t('knowledge.deleteFailed'))
    }
  } catch {
    message.error(t('knowledge.deleteFailed'))
  }
}

// ---------------- 初始化 ----------------
onMounted(() => {
  loadData(queryKnowledgeDocs)
  loadStats()
  loadCategories()
})
</script>

<style scoped lang="scss">
.knowledge-container {
  padding: 0;
}

.stat-row {
  margin-bottom: 16px;
}

.stat-card {
  border-radius: 8px;
}

.search-card {
  border-radius: 8px;
}

.ellipsis-table :deep(.ellipsis-text) {
  display: inline-block;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.muted {
  color: var(--ant-color-text-tertiary);
}

.small {
  font-size: 12px;
  margin-top: 4px;
}
</style>
