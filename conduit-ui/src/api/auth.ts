/**
 * 认证相关 REST 调用：登录、用 HttpOnly refresh Cookie 刷新 access token、登出。
 * 登录与刷新返回 TokenResponse，由 stores/auth 写入内存；这些 /api/v1/auth/ 请求不附带 Authorization，也不触发 401 自动刷新。
 */

import { http } from './client'
import type { TokenResponse } from './types'

export const authApi = {
  login: (username: string, password: string) =>
    http.post<TokenResponse>('/api/v1/auth/login', { username, password }).then((r) => r.data),
  refresh: () => http.post<TokenResponse>('/api/v1/auth/refresh').then((r) => r.data),
  logout: () => http.post('/api/v1/auth/logout').then(() => undefined),
}
