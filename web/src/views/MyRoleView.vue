<template>
  <div class="my-role-page">
    <a-card :bordered="false" class="hero-card">
      <div class="hero-content">
        <div class="hero-text">
          <h2 class="hero-title">{{ t('myRole.title') }}</h2>
          <p class="hero-desc">{{ t('myRole.subtitle') }}</p>
        </div>
        <a-button type="primary" size="large" class="create-button" @click="openCreate">
          <template #icon><PlusOutlined /></template>
          {{ t('myRole.create') }}
        </a-button>
      </div>
    </a-card>

    <a-spin :spinning="loading">
      <div v-if="roles.length" class="role-grid">
        <a-card v-for="role in roles" :key="role.roleId" class="role-card" :bordered="false">
          <div class="role-head">
            <div class="role-badge">
              <SoundOutlined />
            </div>
            <div class="role-heading">
              <div class="role-name" :title="role.roleName">{{ role.roleName }}</div>
              <a-tag color="blue" class="voice-tag">{{ voiceLabel(role.voiceName) }}</a-tag>
            </div>
          </div>

          <a-typography-paragraph
            class="role-desc"
            :content="role.roleDesc || t('myRole.noPrompt')"
            :ellipsis="{ rows: 3 }"
          />

          <div class="role-actions">
            <a-button type="text" @click="openEdit(role)">
              <template #icon><EditOutlined /></template>
              {{ t('common.edit') }}
            </a-button>
            <a-button type="text" danger @click="handleDelete(role)">
              <template #icon><DeleteOutlined /></template>
              {{ t('common.delete') }}
            </a-button>
          </div>
        </a-card>
      </div>

      <div v-else-if="!loading" class="empty-state">
        <a-empty :description="t('myRole.empty')">
          <a-button type="primary" @click="openCreate">
            <template #icon><PlusOutlined /></template>
            {{ t('myRole.createFirst') }}
          </a-button>
        </a-empty>
        <p class="empty-tip">{{ t('myRole.emptyTip') }}</p>
      </div>
    </a-spin>

    <a-modal
      v-model:open="modalVisible"
      :title="formData.roleId ? t('myRole.edit') : t('myRole.create')"
      :confirm-loading="saving"
      :mask-closable="false"
      width="560px"
      @cancel="closeModal"
    >
      <template #footer>
        <a-button @click="closeModal">{{ t('common.cancel') }}</a-button>
        <a-button type="primary" :loading="saving" @click="submitForm">{{ t('common.save') }}</a-button>
      </template>

      <a-alert
        v-if="legacyVoiceHint"
        type="warning"
        show-icon
        class="legacy-alert"
        :message="t('myRole.legacyVoiceHint')"
      />

      <a-form ref="formRef" :model="formData" :rules="rules" layout="vertical">
        <a-form-item :label="t('myRole.name')" name="roleName">
          <a-input
            v-model:value="formData.roleName"
            :placeholder="t('myRole.namePlaceholder')"
            :maxlength="50"
            show-count
          />
        </a-form-item>

        <a-form-item :label="t('myRole.prompt')" name="roleDesc">
          <a-textarea
            v-model:value="formData.roleDesc"
            :placeholder="t('myRole.promptPlaceholder')"
            :rows="6"
            :maxlength="4000"
            show-count
          />
        </a-form-item>

        <a-form-item :label="t('myRole.voice')" name="voiceName">
          <a-select v-model:value="formData.voiceName" :placeholder="t('myRole.voicePlaceholder')">
            <a-select-option value="">{{ defaultVoiceText }}</a-select-option>
            <a-select-opt-group
              v-for="group in voiceOptions.groups"
              :key="group.provider"
              :label="group.providerLabel"
            >
              <a-select-option v-for="voice in group.voices" :key="voice.value" :value="voice.value">
                {{ voice.label }}
              </a-select-option>
            </a-select-opt-group>
          </a-select>
          <div class="form-tip">{{ t('myRole.voiceTip') }}</div>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { message } from 'ant-design-vue'
import { DeleteOutlined, EditOutlined, PlusOutlined, SoundOutlined } from '@ant-design/icons-vue'
import type { FormInstance } from 'ant-design-vue'
import type { Rule } from 'ant-design-vue/es/form'
import type { MyRole, MyRoleFormData, VoiceOptions } from '@/types/role'
import { addMyRole, deleteMyRole, queryMyRoles, updateMyRole } from '@/services/role'
import { getVoiceOptions } from '@/services/config'
import { useConfirm } from '@/composables/useConfirm'

const { t } = useI18n()
const { confirmDelete } = useConfirm()

const roles = ref<MyRole[]>([])
const loading = ref(false)
const saving = ref(false)
const modalVisible = ref(false)
const legacyVoiceHint = ref(false)
const formRef = ref<FormInstance>()
const voiceOptions = ref<VoiceOptions>({ groups: [] })

const formData = reactive<MyRoleFormData>({
  roleName: '',
  roleDesc: '',
  voiceName: '',
})

const rules = computed<Record<string, Rule[]>>(() => ({
  roleName: [
    { required: true, message: t('myRole.nameRequired'), trigger: 'blur' },
    { max: 50, message: t('myRole.nameTooLong'), trigger: 'blur' },
  ],
  roleDesc: [{ max: 4000, message: t('myRole.promptTooLong'), trigger: 'blur' }],
}))

const voiceLabelMap = computed(() => {
  const map = new Map<string, string>()
  voiceOptions.value.groups.forEach((group) => {
    group.voices.forEach((voice) => map.set(voice.value, voice.label))
  })
  return map
})

const defaultVoiceText = computed(() => {
  const label = voiceOptions.value.defaultVoiceName
    ? voiceLabelMap.value.get(voiceOptions.value.defaultVoiceName) || voiceOptions.value.defaultVoiceName
    : ''
  return label ? t('myRole.followDefaultWithName', { name: label }) : t('myRole.followDefault')
})

function voiceLabel(voiceName?: string) {
  if (!voiceName) return t('myRole.followDefault')
  return voiceLabelMap.value.get(voiceName) || voiceName
}

async function loadRoles() {
  loading.value = true
  try {
    const res = await queryMyRoles()
    roles.value = res.data || []
  } catch {
    // 拦截器已提示；这里保留现有列表，避免一次网络抖动把页面清空
  } finally {
    loading.value = false
  }
}

async function loadVoiceOptions() {
  try {
    const res = await getVoiceOptions()
    voiceOptions.value = res.data || { groups: [] }
  } catch {
    voiceOptions.value = { groups: [] }
  }
}

function resetForm() {
  formData.roleId = undefined
  formData.roleName = ''
  formData.roleDesc = ''
  formData.voiceName = ''
  legacyVoiceHint.value = false
}

function openCreate() {
  resetForm()
  modalVisible.value = true
}

function openEdit(role: MyRole) {
  resetForm()
  formData.roleId = role.roleId
  formData.roleName = role.roleName
  formData.roleDesc = role.roleDesc || ''
  const isFeatured = !!role.voiceName && voiceLabelMap.value.has(role.voiceName)
  formData.voiceName = isFeatured ? role.voiceName : ''
  legacyVoiceHint.value = !!role.voiceName && !isFeatured
  modalVisible.value = true
}

function closeModal() {
  modalVisible.value = false
}

async function submitForm() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    const payload: MyRoleFormData = {
      roleName: formData.roleName.trim(),
      roleDesc: formData.roleDesc || '',
      voiceName: formData.voiceName || '',
    }
    if (formData.roleId) {
      await updateMyRole({ ...payload, roleId: formData.roleId })
      message.success(t('myRole.updateSuccess'))
    } else {
      await addMyRole(payload)
      message.success(t('myRole.createSuccess'))
    }
    modalVisible.value = false
    await loadRoles()
  } catch {
    // 后端业务提示由请求拦截器统一弹出，保留弹窗让用户修改
  } finally {
    saving.value = false
  }
}

function handleDelete(role: MyRole) {
  confirmDelete(
    async () => {
      await deleteMyRole(role.roleId)
      message.success(t('myRole.deleteSuccess'))
      await loadRoles()
    },
    {
      title: t('myRole.deleteTitle'),
      content: t('myRole.deleteContent', { name: role.roleName }),
    }
  )
}

onMounted(async () => {
  await Promise.all([loadRoles(), loadVoiceOptions()])
})
</script>

<style scoped lang="scss">
.my-role-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.hero-card {
  background: linear-gradient(135deg, var(--ant-color-primary-bg) 0%, var(--ant-color-bg-container) 70%);
}

.hero-content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
}

.hero-title {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
}

.hero-text {
  flex: 1 1 320px;
  min-width: 0;
}

.hero-desc {
  margin: 8px 0 0;
  color: var(--ant-color-text-secondary);
}

.create-button {
  flex-shrink: 0;
}

.role-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.role-card {
  display: flex;
  flex-direction: column;
  height: 100%;
  border: 1px solid var(--ant-color-border-secondary);
  transition: box-shadow 0.2s ease, transform 0.2s ease;
}

.role-card :deep(.ant-card-body) {
  display: flex;
  flex: 1;
  flex-direction: column;
}

.role-card:hover {
  box-shadow: var(--ant-box-shadow-secondary);
  transform: translateY(-2px);
}

.role-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.role-badge {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: var(--ant-color-primary-bg);
  color: var(--ant-color-primary);
  font-size: 20px;
  flex-shrink: 0;
}

.role-heading {
  min-width: 0;
}

.role-name {
  font-size: 16px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.voice-tag {
  margin-top: 4px;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
}

.role-desc {
  margin: 14px 0 18px;
  min-height: 66px;
  color: var(--ant-color-text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}

.role-actions {
  display: flex;
  gap: 4px;
  margin-top: auto;
  padding-top: 10px;
  border-top: 1px solid var(--ant-color-border-secondary);
}

.empty-state {
  padding: 56px 16px;
  text-align: center;
  background: var(--ant-color-bg-container);
  border: 1px dashed var(--ant-color-border);
  border-radius: var(--ant-border-radius-lg);
}

.empty-tip {
  margin: 12px 0 0;
  color: var(--ant-color-text-tertiary);
  font-size: 12px;
}

.legacy-alert {
  margin-bottom: 16px;
}

.form-tip {
  margin-top: 6px;
  color: var(--ant-color-text-tertiary);
  font-size: 12px;
}

@media (max-width: 576px) {
  .hero-text {
    flex-basis: 100%;
    width: 100%;
  }

  .hero-content {
    align-items: flex-start;
  }

  .create-button {
    width: 100%;
  }

  .role-grid {
    grid-template-columns: 1fr;
  }
}
</style>
