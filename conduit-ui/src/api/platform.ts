/**
 * 平台管理员的 REST 调用（/api/v1/platform/**）：平台概况；租户的列表、新建、改名、停用/启用、删除；某租户的用户；
 * 重置租户管理员密码；进入租户（返回代入令牌，由 stores/auth 写入会话）。租户编码与用户名经 encodeURIComponent 拼进路径。
 */

import { http } from './client'
import type {
  CreateTenantRequest,
  PageView,
  PlatformOverview,
  TenantQuery,
  TenantUserView,
  TenantView,
  TokenResponse,
  UpdateTenantRequest,
} from './types'

const tenantPath = (code: string) => `/api/v1/platform/tenants/${encodeURIComponent(code)}`

export const platformApi = {
  overview: () => http.get<PlatformOverview>('/api/v1/platform/overview').then((r) => r.data),
  listTenants: (query: TenantQuery) =>
    http.get<PageView<TenantView>>('/api/v1/platform/tenants', { params: query }).then((r) => r.data),
  createTenant: (req: CreateTenantRequest) =>
    http.post<TenantView>('/api/v1/platform/tenants', req).then((r) => r.data),
  updateTenant: (code: string, req: UpdateTenantRequest) =>
    http.put<TenantView>(tenantPath(code), req).then((r) => r.data),
  disableTenant: (code: string) => http.post<TenantView>(`${tenantPath(code)}/disable`).then((r) => r.data),
  enableTenant: (code: string) => http.post<TenantView>(`${tenantPath(code)}/enable`).then((r) => r.data),
  removeTenant: (code: string) => http.delete(tenantPath(code)).then(() => undefined),
  tenantUsers: (code: string) => http.get<TenantUserView[]>(`${tenantPath(code)}/users`).then((r) => r.data),
  resetAdminPassword: (code: string, username: string, password: string) =>
    http
      .post(`${tenantPath(code)}/users/${encodeURIComponent(username)}/reset-password`, { password })
      .then(() => undefined),
  enter: (code: string) => http.post<TokenResponse>(`${tenantPath(code)}/enter`).then((r) => r.data),
}
