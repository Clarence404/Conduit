/** accessRedirect 单元测试：平台管理员访问租户页面带到租户管理；租户身份访问平台页面带到首页；只读用户不能进入用户管理；其余放行 */
import { describe, expect, it } from 'vitest'
import type { UserView } from '@/api/types'
import { ROUTE_META } from './meta'
import { accessRedirect } from './access'

function user(patch: Partial<UserView>): UserView {
  return {
    username: 'u',
    displayName: null,
    role: 'TENANT_ADMIN',
    tenantCode: 'east',
    tenantName: '验收租户（华东）',
    mustChangePassword: false,
    impersonatedBy: null,
    ...patch,
  }
}

const platform = user({ role: 'PLATFORM_ADMIN', tenantCode: null, tenantName: null })
const admin = user({})
const viewer = user({ role: 'TENANT_VIEWER' })
const impersonating = user({ impersonatedBy: 'sysadmin' })

describe('accessRedirect', () => {
  it('sends the platform admin from tenant pages to the tenant list', () => {
    expect(accessRedirect({ meta: ROUTE_META.products }, platform)).toEqual({ name: 'tenants' })
    expect(accessRedirect({ meta: ROUTE_META['device-detail'] }, platform)).toEqual({ name: 'tenants' })
    expect(accessRedirect({ meta: ROUTE_META.users }, platform)).toEqual({ name: 'tenants' })
    expect(accessRedirect({ meta: ROUTE_META.tenants }, platform)).toBeNull()
    expect(accessRedirect({ meta: ROUTE_META.home }, platform)).toBeNull()
  })

  it('sends tenant identities from platform pages to home', () => {
    expect(accessRedirect({ meta: ROUTE_META.tenants }, admin)).toEqual({ name: 'home' })
    expect(accessRedirect({ meta: ROUTE_META.tenants }, viewer)).toEqual({ name: 'home' })
    expect(accessRedirect({ meta: ROUTE_META.tenants }, impersonating)).toEqual({ name: 'home' })
  })

  it('keeps user management for those who may manage users', () => {
    expect(accessRedirect({ meta: ROUTE_META.users }, admin)).toBeNull()
    expect(accessRedirect({ meta: ROUTE_META.users }, impersonating)).toBeNull()
    expect(accessRedirect({ meta: ROUTE_META.users }, viewer)).toEqual({ name: 'home' })
    expect(accessRedirect({ meta: ROUTE_META.products }, viewer)).toBeNull()
  })
})
