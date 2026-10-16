<script setup lang="ts">
/**
 * 新建租户对话框：分「租户信息」（编码、名称）与「首个管理员」（用户名、显示名称、初始密码）两块，
 * 提交前按账号规则校验，错误显示在对话框内。成功后发出 created（新租户）并关闭；首个管理员首次登录须修改密码。
 */
import { reactive, ref, watch } from 'vue'
import { platformApi } from '@/api/platform'
import { problemMessage } from '@/api/errors'
import type { TenantView } from '@/api/types'
import { passwordProblem, tenantCodeProblem, usernameProblem } from '@/utils/accountRules'

const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ created: [tenant: TenantView] }>()

const form = reactive({ code: '', name: '', username: '', displayName: '', password: '' })
const error = ref('')
const saving = ref(false)

// 打开与关闭时都重置表单与错误提示，避免初始密码残留
watch(visible, () => {
  Object.assign(form, { code: '', name: '', username: '', displayName: '', password: '' })
  error.value = ''
})

/** 租户名称的错误：为空或超过 64 个字符 */
function nameProblem(name: string): string | null {
  if (!name) return '请输入租户名称'
  return name.length > 64 ? '租户名称最多 64 个字符' : null
}

/** 校验表单，返回第一条错误；全部合法时为 null */
function validate(): string | null {
  return (
    tenantCodeProblem(form.code.trim()) ??
    nameProblem(form.name.trim()) ??
    usernameProblem(form.username.trim()) ??
    passwordProblem(form.password)
  )
}

/** 校验并创建租户与首个管理员；保存中忽略重复点击 */
async function submit() {
  if (saving.value) return
  error.value = validate() ?? ''
  if (error.value) return
  saving.value = true
  try {
    const tenant = await platformApi.createTenant({
      code: form.code.trim(),
      name: form.name.trim(),
      admin: { username: form.username.trim(), displayName: form.displayName.trim() || null, password: form.password },
    })
    visible.value = false
    emit('created', tenant)
  } catch (e) {
    error.value = problemMessage(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="新建租户" width="min(520px, 92vw)" :close-on-click-modal="false">
    <el-form label-width="96px" @submit.prevent="submit">
      <el-alert v-if="error" class="error" type="error" :title="error" show-icon :closable="false" />
      <el-divider content-position="left">租户信息</el-divider>
      <el-form-item label="编码" required>
        <el-input v-model="form.code" maxlength="32" placeholder="如 east-1，小写字母开头，创建后不可修改" />
      </el-form-item>
      <el-form-item label="名称" required>
        <el-input v-model="form.name" maxlength="64" placeholder="如 验收租户（华东）" />
      </el-form-item>
      <el-divider content-position="left">首个管理员</el-divider>
      <el-form-item label="用户名" required>
        <el-input v-model="form.username" maxlength="32" autocomplete="off" />
      </el-form-item>
      <el-form-item label="显示名称">
        <el-input v-model="form.displayName" maxlength="64" />
      </el-form-item>
      <el-form-item label="初始密码" required>
        <el-input v-model="form.password" type="password" show-password autocomplete="new-password" placeholder="8～64 位，同时包含字母和数字" />
        <div class="cd-muted hint">管理员首次登录时需要修改密码。</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">创建</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.error {
  margin-bottom: var(--cd-space-3);
}
.hint {
  margin-top: var(--cd-space-1);
  font-size: 12px;
}
</style>
