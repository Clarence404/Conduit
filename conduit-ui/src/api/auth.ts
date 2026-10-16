/**
 * 认证相关 REST 调用：登录、用 HttpOnly refresh Cookie 刷新 access token、登出、平台管理员退出代入的租户。
 * 登录、刷新与退出租户返回 TokenResponse，由 stores/auth 写入内存；登录、刷新、登出不附带 Authorization，也不触发 401 自动刷新。
 */

import { http } from './client'
import type { TokenResponse } from './types'

export const authApi = {
  login: (username: string, password: string) =>
    http.post<TokenResponse>('/api/v1/auth/login', { username, password }).then((r) => r.data),
  refresh: () => http.post<TokenResponse>('/api/v1/auth/refresh').then((r) => r.data),
  logout: () => http.post('/api/v1/auth/logout').then(() => undefined),
  /** 退出代入的租户，换回平台管理员自己的令牌 */
  exitTenant: () => http.post<TokenResponse>('/api/v1/auth/exit-tenant').then((r) => r.data),
}
