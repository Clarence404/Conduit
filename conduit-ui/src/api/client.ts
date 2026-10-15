/**
 * 应用全局共用的 Axios 实例：把 createHttp 与 Pinia 认证 store、路由接在一起。
 * 取/存 access token 走 auth store；刷新失败（登录失效）时清空会话并跳转登录页，用 redirect 参数记住当前地址。
 * 单独成文件是为了让 http.ts 不依赖 store 和路由，便于单测。
 */

import { createHttp } from './http'
import { useAuthStore } from '@/stores/auth'
import router from '@/router'

export const http = createHttp({
  getToken: () => useAuthStore().token,
  setToken: (token) => {
    useAuthStore().token = token
  },
  onAuthFailed: () => {
    useAuthStore().clear()
    void router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
  },
})
