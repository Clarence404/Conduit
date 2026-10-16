<script setup lang="ts">
/**
 * 租户用户抽屉（平台管理员）：列出某租户的全部用户（不分页，按角色、用户名排序），角色、状态与「待改密码」用标签显示；
 * 租户管理员行有「重置密码」，经 ResetPasswordDialog 设置新的初始密码（对方下次登录须修改）。打开或切换租户时清空旧列表并重新加载，请求带序号，只采用最后一次的响应。
 */
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { platformApi } from '@/api/platform'
import { problemMessage } from '@/api/errors'
import type { TenantUserView, TenantView } from '@/api/types'
import { createRequestSeq } from '@/utils/requestSeq'
import { formatDateTime } from '@/utils/format'
import { roleLabel, statusLabel } from '@/utils/permissions'
import ResetPasswordDialog from './ResetPasswordDialog.vue'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ tenant: TenantView | null }>()

const seq = createRequestSeq()
const users = ref<TenantUserView[]>([])
const loading = ref(false)
const failed = ref(false)
const resetVisible = ref(false)
const resetting = ref<TenantUserView | null>(null)

/**
 * 加载当前租户的用户；失败时清空并提示。只有最后发出的请求能写入结果与加载状态，
 * 切换租户后先发后到的旧响应会被丢弃。
 */
async function load() {
  if (!props.tenant) return
  const id = seq.next()
  loading.value = true
  failed.value = false
  try {
    const result = await platformApi.tenantUsers(props.tenant.code)
    if (seq.isLatest(id)) users.value = result
  } catch (e) {
    if (seq.isLatest(id)) {
      users.value = []
      failed.value = true
      ElMessage.error(problemMessage(e))
    }
  } finally {
    if (seq.isLatest(id)) loading.value = false
  }
}

// 打开或切换租户时：先清空上一个租户的用户（避免加载期间显示别的租户的数据），再重新加载
watch(
  () => [visible.value, props.tenant?.code] as const,
  ([open]) => {
    if (open) {
      users.value = []
      void load()
    }
  },
  { immediate: true },
)

/** 打开重置密码对话框 */
function openReset(user: TenantUserView) {
  resetting.value = user
  resetVisible.value = true
}

/** 重置所选租户管理员的密码（由 ResetPasswordDialog 调用；失败时异常交给对话框显示），成功后刷新列表 */
async function savePassword(password: string) {
  if (!props.tenant || !resetting.value) return
  await platformApi.resetAdminPassword(props.tenant.code, resetting.value.username, password)
  await load()
}
</script>

<template>
  <el-drawer v-model="visible" :title="tenant ? `用户 · ${tenant.name}` : '用户'" size="min(680px, 100vw)">
    <el-table v-loading="loading" :data="users">
      <el-table-column label="用户名" min-width="160">
        <template #default="{ row }">
          {{ row.username }}
          <div v-if="row.displayName" class="cd-muted">{{ row.displayName }}</div>
        </template>
      </el-table-column>
      <el-table-column label="角色" width="110">
        <template #default="{ row }">
          <el-tag :type="row.role === 'TENANT_ADMIN' ? 'primary' : 'info'" size="small">{{ roleLabel(row.role) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">{{ statusLabel(row.status) }}</el-tag>
          <el-tag v-if="row.mustChangePassword" type="warning" size="small" class="flag">待改密码</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button v-if="row.role === 'TENANT_ADMIN'" link type="primary" @click="openReset(row)">重置密码</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :description="failed ? '加载失败，点击重试' : '该租户还没有用户'">
          <el-button v-if="failed" type="primary" @click="load">重试</el-button>
        </el-empty>
      </template>
    </el-table>
    <ResetPasswordDialog v-model="resetVisible" :username="resetting?.username ?? ''" :save="savePassword" />
  </el-drawer>
</template>

<style scoped>
.flag {
  margin-left: var(--cd-space-1);
}
</style>
