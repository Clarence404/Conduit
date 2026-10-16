/**
 * 认证状态（Pinia）：access token 只放内存，页面刷新后靠 HttpOnly refresh Cookie 恢复。
 * 同时保存当前用户（有效角色、所属租户、代入者、是否需改密码）与「接口要求先改密码」标记，
 * 并提供进入/退出租户、修改密码、修改显示名称；这些操作返回的新会话都经 setSession 写入。
 */

import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'
import { meApi } from '@/api/me'
import { platformApi } from '@/api/platform'
import type { TokenResponse, UserView } from '@/api/types'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: null as string | null,
    user: null as UserView | null,
    /** 某个接口返回了 PASSWORD_CHANGE_REQUIRED（令牌带「需改密码」标记，即使 user 里还没体现） */
    passwordChangeRequired: false,
  }),
  getters: {
    isAuthenticated: (state) => state.token !== null,
    /** 是否需要弹出强制修改密码对话框：已登录，且用户带标记或接口要求先改密码 */
    needsPasswordChange: (state) =>
      state.token !== null && (state.passwordChangeRequired || state.user?.mustChangePassword === true),
  },
  actions: {
    /** 写入一次签发的会话（token 与用户），并清除「接口要求先改密码」标记。 */
    setSession(res: TokenResponse) {
      this.token = res.accessToken
      this.user = res.user
      this.passwordChangeRequired = false
    },
    /** 用户名密码登录，成功后保存会话；失败时抛出接口错误，由调用方提示。 */
    async login(username: string, password: string) {
      this.setSession(await authApi.login(username, password))
    },
    /** 页面刷新后内存里的 token 没了，用 HttpOnly refresh Cookie 恢复会话；失败时清空并返回 false。 */
    async restore(): Promise<boolean> {
      try {
        this.setSession(await authApi.refresh())
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
    /** 平台管理员进入租户：换成代入令牌；失败时抛出接口错误，会话不变。 */
    async enterTenant(code: string) {
      this.setSession(await platformApi.enter(code))
    },
    /** 退出代入的租户，换回平台管理员自己的令牌。 */
    async exitTenant() {
      this.setSession(await authApi.exitTenant())
    },
    /** 修改自己的密码：成功后换用新令牌（不再带需改密码标记）；失败时抛出接口错误。 */
    async changePassword(oldPassword: string, newPassword: string) {
      this.setSession(await meApi.changePassword({ oldPassword, newPassword }))
    },
    /** 修改自己的显示名称（null 即清除），用服务端返回的用户更新会话。 */
    async updateProfile(displayName: string | null) {
      this.user = await meApi.update({ displayName })
    },
    /** 清空本地会话（token、用户与改密码标记），不请求服务端；登录失效或登出时使用。 */
    clear() {
      this.token = null
      this.user = null
      this.passwordChangeRequired = false
    },
  },
})
