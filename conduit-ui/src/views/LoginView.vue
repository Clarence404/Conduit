<script setup lang="ts">
/**
 * 登录页：左侧深色品牌区（Logo、「Conduit 设备接入平台」、一句说明、底部装饰折线），右侧登录表单；窄屏时上下排列。
 * 登录成功后跳到 redirect 参数指向的站内页面（经 safeRedirect 校验，防止开放重定向），没有则去首页。
 * 输入校验与接口错误显示在表单内。
 */

import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { DataLine, Lock, User } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { problemMessage } from '@/api/errors'
import { safeRedirect } from '@/utils/redirect'

const form = reactive({ username: '', password: '' })
const loading = ref(false)
const error = ref('')
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

/** 提交登录：用户名或密码为空时只在表单内提示、不请求；失败时在表单内显示服务端给出的原因。 */
async function submit() {
  error.value = ''
  if (!form.username || !form.password) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    await router.replace(safeRedirect(route.query.redirect))
  } catch (e) {
    error.value = problemMessage(e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <section class="brand">
      <div class="logo">
        <el-icon :size="26"><DataLine /></el-icon>
        <span>Conduit</span>
      </div>
      <div class="pitch">
        <h1>Conduit 设备接入平台</h1>
        <p>设备经 MQTT 接入，数据按物模型校验后入库；在这里管理产品与设备，查看最新值与历史曲线。</p>
      </div>
      <svg class="decor" viewBox="0 0 400 120" preserveAspectRatio="none" aria-hidden="true">
        <polygon class="area" points="0,120 0,90 40,72 80,80 120,44 160,58 200,30 240,62 280,38 320,52 360,20 400,34 400,120" />
        <polyline points="0,90 40,72 80,80 120,44 160,58 200,30 240,62 280,38 320,52 360,20 400,34" />
      </svg>
    </section>

    <section class="form-side">
      <div class="form-box">
        <h2>登录</h2>
        <p class="hint">使用平台账号登录</p>
        <el-form label-position="top" @submit.prevent="submit">
          <el-alert v-if="error" class="login-error" type="error" :title="error" show-icon :closable="false" />
          <el-form-item label="用户名">
            <el-input v-model="form.username" autocomplete="username" :prefix-icon="User" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              autocomplete="current-password"
              :prefix-icon="Lock"
            />
          </el-form-item>
          <el-button type="primary" native-type="submit" :loading="loading" class="submit">登录</el-button>
        </el-form>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login-page {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  min-height: 100vh;
  background: var(--cd-page-bg);
}
.brand {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: var(--cd-space-5);
  padding: 48px;
  overflow: hidden;
  background: var(--cd-brand-bg);
  color: var(--cd-brand-text);
}
.logo {
  display: flex;
  align-items: center;
  gap: var(--cd-space-2);
  font-size: 20px;
  font-weight: 600;
}
.logo .el-icon {
  color: var(--el-color-primary);
}
.pitch h1 {
  margin: 0 0 var(--cd-space-3);
  font-size: 28px;
}
.pitch p {
  max-width: 420px;
  margin: 0;
  color: var(--cd-brand-muted);
  line-height: 1.7;
}
.decor {
  width: 100%;
  height: 120px;
}
.decor polyline {
  fill: none;
  stroke: var(--el-color-primary);
  stroke-width: 2;
}
.decor .area {
  fill: var(--el-color-primary);
  opacity: 0.12;
}
.form-side {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--cd-space-5);
  background: var(--cd-panel-bg);
}
.form-box {
  width: 100%;
  max-width: 360px;
}
.form-box h2 {
  margin: 0;
  color: var(--el-text-color-primary);
}
.hint {
  margin: var(--cd-space-1) 0 var(--cd-space-5);
  color: var(--cd-text-muted);
}
.login-error {
  margin-bottom: var(--cd-space-4);
}
.submit {
  width: 100%;
}
@media (max-width: 819px) {
  .login-page {
    grid-template-columns: 1fr;
  }
  .brand {
    padding: var(--cd-space-5);
  }
  .decor {
    height: 60px;
  }
}
</style>
