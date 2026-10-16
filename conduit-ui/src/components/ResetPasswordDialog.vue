<script setup lang="ts">
/**
 * 重置密码对话框：输入两次新的初始密码，按密码规则校验后调用 save（由父组件决定调用哪个接口：平台重置租户管理员、租户管理员重置本租户用户）；
 * 成功后提示「对方下次登录需修改密码」并关闭，失败时把原因显示在对话框内。
 */
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { problemMessage } from '@/api/errors'
import { passwordProblem } from '@/utils/accountRules'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ username: string; save: (password: string) => Promise<void> }>()

const form = reactive({ password: '', confirm: '' })
const error = ref('')
const saving = ref(false)

watch(visible, (open) => {
  if (open) {
    form.password = ''
    form.confirm = ''
    error.value = ''
  }
})

/** 校验并保存：密码合规且两次一致才调用 save */
async function submit() {
  if (saving.value) return
  error.value = passwordProblem(form.password) ?? (form.password !== form.confirm ? '两次输入的密码不一致' : '')
  if (error.value) return
  saving.value = true
  try {
    await props.save(form.password)
    visible.value = false
    ElMessage.success(`已重置 ${props.username} 的密码，对方下次登录需修改密码`)
  } catch (e) {
    error.value = problemMessage(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="`重置密码 · ${username}`" width="min(420px, 92vw)" :close-on-click-modal="false" append-to-body>
    <el-form label-width="96px" @submit.prevent="submit">
      <el-alert v-if="error" class="error" type="error" :title="error" show-icon :closable="false" />
      <el-form-item label="新密码" required>
        <el-input v-model="form.password" type="password" show-password autocomplete="new-password" placeholder="8～64 位，同时包含字母和数字" />
      </el-form-item>
      <el-form-item label="确认密码" required>
        <el-input v-model="form.confirm" type="password" show-password autocomplete="new-password" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">重置</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.error {
  margin-bottom: var(--cd-space-3);
}
</style>
