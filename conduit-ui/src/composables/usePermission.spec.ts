/** usePermission 测试：随 auth store 中的当前用户变化（租户管理员 → 只读用户 → 平台管理员 → 代入）实时更新 */
import { describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import type { UserView } from '@/api/types'
import { usePermission } from './usePermission'

const base: UserView = {
  username: 'u',
  displayName: null,
  role: 'TENANT_ADMIN',
  tenantCode: 'east',
  tenantName: '验收租户（华东）',
  mustChangePassword: false,
  impersonatedBy: null,
}

describe('usePermission', () => {
  it('follows the current user', () => {
    setActivePinia(createPinia())
    const auth = useAuthStore()
    auth.user = base
    const { isPlatformAdmin, isImpersonating, canWrite, canManageUsers } = usePermission()

    expect(canWrite.value).toBe(true)
    expect(canManageUsers.value).toBe(true)

    auth.user = { ...base, role: 'TENANT_VIEWER' }
    expect(canWrite.value).toBe(false)

    auth.user = { ...base, role: 'PLATFORM_ADMIN', tenantCode: null, tenantName: null }
    expect(isPlatformAdmin.value).toBe(true)
    expect(canWrite.value).toBe(false)

    auth.user = { ...base, impersonatedBy: 'sysadmin' }
    expect(isImpersonating.value).toBe(true)
    expect(isPlatformAdmin.value).toBe(false)
    expect(canWrite.value).toBe(true)
  })
})
