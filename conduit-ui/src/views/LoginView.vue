<script setup lang="ts">
/** 登录页。登录成功后跳到地址栏 redirect 参数指向的页面（经 safeRedirect 校验，防止开放重定向），没有则去设备列表。 */

import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { problemMessage } from '@/api/errors'
import { safeRedirect } from '@/utils/redirect'

const form = reactive({ username: '', password: '' })
const loading = ref(false)
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

/** 提交登录：用户名或密码为空时只提示不请求；失败时提示服务端给出的原因。 */
async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    const redirect = safeRedirect(route.query.redirect)
    await router.replace(redirect)
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <h1>Conduit</h1>
      <p class="subtitle">IoT 数据接入平台</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading" class="submit">登录</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-bg-color-page);
}
.login-card {
  width: 360px;
}
h1 {
  margin: 0;
}
.subtitle {
  margin: 4px 0 20px;
  color: var(--el-text-color-secondary);
}
.submit {
  width: 100%;
}
</style>
