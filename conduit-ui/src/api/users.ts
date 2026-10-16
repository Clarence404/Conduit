/**
 * 租户用户管理 REST 调用（/api/v1/users，当前租户）：分页查询、新建、编辑、启用/禁用、重置密码、删除。
 * 用户名经 encodeURIComponent 拼进路径。
 */

import { http } from './client'
import type { CreateUserRequest, PageView, TenantUserView, UpdateUserRequest, UserQuery } from './types'

const userPath = (username: string) => `/api/v1/users/${encodeURIComponent(username)}`

export const userApi = {
  list: (query: UserQuery) =>
    http.get<PageView<TenantUserView>>('/api/v1/users', { params: query }).then((r) => r.data),
  create: (req: CreateUserRequest) => http.post<TenantUserView>('/api/v1/users', req).then((r) => r.data),
  update: (username: string, req: UpdateUserRequest) =>
    http.put<TenantUserView>(userPath(username), req).then((r) => r.data),
  disable: (username: string) => http.post<TenantUserView>(`${userPath(username)}/disable`).then((r) => r.data),
  enable: (username: string) => http.post<TenantUserView>(`${userPath(username)}/enable`).then((r) => r.data),
  resetPassword: (username: string, password: string) =>
    http.post(`${userPath(username)}/reset-password`, { password }).then(() => undefined),
  remove: (username: string) => http.delete(userPath(username)).then(() => undefined),
}
