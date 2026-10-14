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
    async logout() {
      try {
        await authApi.logout()
      } finally {
        this.clear()
      }
    },
    clear() {
      this.token = null
      this.user = null
    },
  },
})
