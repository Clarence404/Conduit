/** 认证状态（Pinia）：access token 只放内存，页面刷新后靠 HttpOnly refresh Cookie 恢复。 */

import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'
import type { UserView } from '@/api/types'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: null as string | null,
    user: null as UserView | null,
  }),
  getters: {
    isAuthenticated: (state) => state.token !== null,
  },
  actions: {
    /** 用户名密码登录，成功后保存 token 与用户信息；失败时抛出接口错误，由调用方提示。 */
    async login(username: string, password: string) {
      const res = await authApi.login(username, password)
      this.token = res.accessToken
      this.user = res.user
    },
    /** 页面刷新后内存里的 token 没了，用 HttpOnly refresh Cookie 恢复会话。 */
    async restore(): Promise<boolean> {
      try {
        const res = await authApi.refresh()
        this.token = res.accessToken
        this.user = res.user
        return true
      } catch {
        this.clear()
        return false
      }
    },
    /** 登出：通知服务端作废 refresh Cookie。无论请求成败都清空本地会话，失败异常仍会抛出。 */
    async logout() {
      try {
        await authApi.logout()
      } finally {
        this.clear()
      }
    },
    /** 清空本地会话（token 与用户），不请求服务端；登录失效或登出时使用。 */
    clear() {
      this.token = null
      this.user = null
    },
  },
})
