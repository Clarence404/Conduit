<script setup lang="ts">
/**
 * 代入提示条：平台管理员进入租户后固定显示在顶栏下方，提示「正在以平台管理员身份操作租户：XX」；
 * 「退出」换回平台管理员自己的令牌并回到租户管理。未代入时不渲染。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { WarningFilled } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { usePermission } from '@/composables/usePermission'
import { problemMessage } from '@/api/errors'

const auth = useAuthStore()
const router = useRouter()
const { isImpersonating } = usePermission()
const exiting = ref(false)

/** 退出租户：成功后回到租户管理；失败时提示接口错误并留在当前页面。 */
async function exit() {
  if (exiting.value) return
  exiting.value = true
  try {
    await auth.exitTenant()
    await router.replace({ name: 'tenants' })
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    exiting.value = false
  }
}
</script>

<template>
  <div v-if="isImpersonating" class="impersonation-bar" role="status">
    <el-icon class="icon"><WarningFilled /></el-icon>
    <span class="text">
      正在以平台管理员身份操作租户：<strong>{{ auth.user?.tenantName ?? auth.user?.tenantCode }}</strong>
    </span>
    <el-button size="small" type="warning" :loading="exiting" @click="exit">退出</el-button>
  </div>
</template>

<style scoped>
.impersonation-bar {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: var(--cd-space-2);
  padding: var(--cd-space-2) var(--cd-space-4);
  color: var(--el-text-color-primary);
  background: var(--el-color-warning-light-9);
  border-bottom: 1px solid var(--el-color-warning-light-5);
}
.icon {
  color: var(--el-color-warning);
}
.text {
  flex: 1;
  min-width: 0;
}
</style>
