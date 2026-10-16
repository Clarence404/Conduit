<script setup lang="ts">
/**
 * 强制修改密码对话框：用户带「需改密码」标记（首次登录、密码被重置）或任一接口返回 PASSWORD_CHANGE_REQUIRED 时弹出，不能关闭。
 * 新密码先按本地规则校验（8～64 位，同时包含字母和数字，两次一致）；修改成功后换用新令牌并自动关闭。也可以退出登录。
 */
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { problemMessage } from '@/api/errors'
import { passwordProblem } from '@/utils/accountRules'

const auth = useAuthStore()
const router = useRouter()
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const error = ref('')
const saving = ref(false)
const visible = computed(() => auth.needsPasswordChange)

// 对话框关闭（改密成功、退出登录）时清空密码与错误提示
watch(visible, (open) => {
  if (!open) {
    form.oldPassword = ''
    form.newPassword = ''
    form.confirm = ''
    error.value = ''
  }
})

/** 校验并提交：原密码必填、新密码合规、两次一致；失败时把原因显示在对话框内。 */
async function submit() {
  if (saving.value) return
  error.value = ''
  if (!form.oldPassword) {
    error.value = '请输入原密码'
    return
  }
  const problem = passwordProblem(form.newPassword)
  if (problem) {
    error.value = problem
    return
  }
  if (form.newPassword !== form.confirm) {
    error.value = '两次输入的新密码不一致'
    return
  }
  saving.value = true
  try {
    await auth.changePassword(form.oldPassword, form.newPassword)
    form.oldPassword = ''
    form.newPassword = ''
    form.confirm = ''
    ElMessage.success('密码已修改')
  } catch (e) {
    error.value = problemMessage(e)
  } finally {
    saving.value = false
  }
}

/** 退出登录：无论请求是否成功都回到登录页。 */
async function logout() {
  try {
    await auth.logout()
  } catch {
    // 登出请求失败也要回到登录页
  } finally {
    await router.replace({ name: 'login' })
  }
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="请修改密码"
    width="min(420px, 92vw)"
    :show-close="false"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    append-to-body
  >
    <p class="cd-muted hint">首次登录或密码被重置后，需要先设置新密码才能继续使用。</p>
    <el-form label-position="top" @submit.prevent="submit">
      <el-alert v-if="error" class="error" type="error" :title="error" show-icon :closable="false" />
      <el-form-item label="原密码">
        <el-input v-model="form.oldPassword" type="password" show-password autocomplete="current-password" />
      </el-form-item>
      <el-form-item label="新密码">
        <el-input
          v-model="form.newPassword"
          type="password"
          show-password
          autocomplete="new-password"
          placeholder="8～64 位，同时包含字母和数字"
        />
      </el-form-item>
      <el-form-item label="确认新密码">
        <el-input v-model="form.confirm" type="password" show-password autocomplete="new-password" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="logout">退出登录</el-button>
      <el-button type="primary" :loading="saving" @click="submit">修改密码</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: 0 0 var(--cd-space-3);
}
.error {
  margin-bottom: var(--cd-space-3);
}
</style>
