<script setup lang="ts">
/**
 * 新建/编辑用户对话框：新建时填写用户名、显示名称、角色与初始密码（对方首次登录须修改）；编辑时只改显示名称与角色，
 * 编辑自己时角色锁定（editingSelf）。提交前按账号规则校验，错误显示在对话框内；成功后发出 saved（保存后的用户）并关闭。
 */
import { computed, reactive, ref, watch } from 'vue'
import { userApi } from '@/api/users'
import { problemMessage } from '@/api/errors'
import type { TenantRole, TenantUserView } from '@/api/types'
import { passwordProblem, usernameProblem } from '@/utils/accountRules'

const visible = defineModel<boolean>({ required: true })
const props = withDefaults(defineProps<{ user: TenantUserView | null; editingSelf?: boolean }>(), { editingSelf: false })
const emit = defineEmits<{ saved: [user: TenantUserView] }>()

const form = reactive({ username: '', displayName: '', role: 'TENANT_VIEWER' as TenantRole, password: '' })
const error = ref('')
const saving = ref(false)
/** 是否在编辑已有用户（否则为新建） */
const editing = computed(() => props.user !== null)

// 每次打开都按当前要编辑的用户重置表单；关闭时清掉密码与错误提示，避免残留
watch(
  visible,
  (open) => {
    if (!open) {
      form.password = ''
      error.value = ''
      return
    }
    form.username = props.user?.username ?? ''
    form.displayName = props.user?.displayName ?? ''
    form.role = props.user?.role ?? 'TENANT_VIEWER'
    form.password = ''
    error.value = ''
  },
  { immediate: true },
)

/** 校验表单，返回第一条错误；编辑时只校验显示名称长度 */
function validate(): string | null {
  if (form.displayName.trim().length > 64) return '显示名称最多 64 个字符'
  if (editing.value) return null
  return usernameProblem(form.username.trim()) ?? passwordProblem(form.password)
}

/** 校验并保存（新建或编辑）；保存中忽略重复点击 */
async function submit() {
  if (saving.value) return
  error.value = validate() ?? ''
  if (error.value) return
  saving.value = true
  const displayName = form.displayName.trim() || null
  try {
    const saved = props.user
      ? await userApi.update(props.user.username, { displayName, role: form.role })
      : await userApi.create({ username: form.username.trim(), displayName, role: form.role, password: form.password })
    visible.value = false
    emit('saved', saved)
  } catch (e) {
    error.value = problemMessage(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="editing ? '编辑用户' : '新建用户'" width="min(480px, 92vw)" :close-on-click-modal="false">
    <el-form label-width="96px" @submit.prevent="submit">
      <el-alert v-if="error" class="error" type="error" :title="error" show-icon :closable="false" />
      <el-form-item label="用户名" :required="!editing">
        <el-input v-model="form.username" :disabled="editing" maxlength="32" autocomplete="off" />
      </el-form-item>
      <el-form-item label="显示名称">
        <el-input v-model="form.displayName" maxlength="64" placeholder="不填则显示用户名" />
      </el-form-item>
      <el-form-item label="角色" required>
        <el-radio-group v-model="form.role" :disabled="editingSelf">
          <el-radio value="TENANT_ADMIN">租户管理员</el-radio>
          <el-radio value="TENANT_VIEWER">只读用户</el-radio>
        </el-radio-group>
        <div v-if="editingSelf" class="cd-muted hint">不能修改自己的角色。</div>
      </el-form-item>
      <el-form-item v-if="!editing" label="初始密码" required>
        <el-input v-model="form.password" type="password" show-password autocomplete="new-password" placeholder="8～64 位，同时包含字母和数字" />
        <div class="cd-muted hint">用户首次登录时需要修改密码。</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.error {
  margin-bottom: var(--cd-space-3);
}
.hint {
  width: 100%;
  margin-top: var(--cd-space-1);
  font-size: 12px;
}
</style>
