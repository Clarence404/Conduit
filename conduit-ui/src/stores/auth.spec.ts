/** auth store 单元测试：登录写入会话、需改密码判断、修改密码与进入/退出租户换用新会话、修改显示名称、恢复失败时清空 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth'
import { authApi } from '@/api/auth'
import { meApi } from '@/api/me'
import { platformApi } from '@/api/platform'
import type { TokenResponse, UserView } from '@/api/types'

vi.mock('@/api/auth', () => ({ authApi: { login: vi.fn(), refresh: vi.fn(), logout: vi.fn(), exitTenant: vi.fn() } }))
vi.mock('@/api/me', () => ({ meApi: { update: vi.fn(), changePassword: vi.fn() } }))
vi.mock('@/api/platform', () => ({ platformApi: { enter: vi.fn() } }))

function user(patch: Partial<UserView> = {}): UserView {
  return {
    username: 'east-admin',
    displayName: null,
    role: 'TENANT_ADMIN',
    tenantCode: 'east',
    tenantName: '验收租户（华东）',
    mustChangePassword: false,
    impersonatedBy: null,
    ...patch,
  }
}

function session(token: string, patch: Partial<UserView> = {}): TokenResponse {
  return { accessToken: token, expiresIn: 900, user: user(patch) }
}

describe('useAuthStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('stores the session on login and flags a required password change', async () => {
    vi.mocked(authApi.login).mockResolvedValue(session('t1', { mustChangePassword: true }))
    const auth = useAuthStore()

    await auth.login('east-admin', 'Init-pass-2026')

    expect(auth.token).toBe('t1')
    expect(auth.needsPasswordChange).toBe(true)
  })

  it('flags a password change reported by an endpoint', () => {
    const auth = useAuthStore()
    auth.setSession(session('t1'))
    expect(auth.needsPasswordChange).toBe(false)

    auth.passwordChangeRequired = true

    expect(auth.needsPasswordChange).toBe(true)
  })

  it('changes the password and swaps in the new session', async () => {
    vi.mocked(meApi.changePassword).mockResolvedValue(session('t2'))
    const auth = useAuthStore()
    auth.setSession(session('t1', { mustChangePassword: true }))
    auth.passwordChangeRequired = true

    await auth.changePassword('Init-pass-2026', 'Active-pass-2026')

    expect(meApi.changePassword).toHaveBeenCalledWith({ oldPassword: 'Init-pass-2026', newPassword: 'Active-pass-2026' })
    expect(auth.token).toBe('t2')
    expect(auth.needsPasswordChange).toBe(false)
  })

  it('enters and exits a tenant', async () => {
    vi.mocked(platformApi.enter).mockResolvedValue(session('imp', { username: 'sysadmin', impersonatedBy: 'sysadmin' }))
    vi.mocked(authApi.exitTenant).mockResolvedValue(
      session('own', { username: 'sysadmin', role: 'PLATFORM_ADMIN', tenantCode: null, tenantName: null }),
    )
    const auth = useAuthStore()

    await auth.enterTenant('east')
    expect(platformApi.enter).toHaveBeenCalledWith('east')
    expect(auth.user?.impersonatedBy).toBe('sysadmin')

    await auth.exitTenant()
    expect(auth.token).toBe('own')
    expect(auth.user?.role).toBe('PLATFORM_ADMIN')
  })

  it('updates the display name from the server response', async () => {
    vi.mocked(meApi.update).mockResolvedValue(user({ displayName: '华东运维 张工' }))
    const auth = useAuthStore()
    auth.setSession(session('t1'))

    await auth.updateProfile('华东运维 张工')

    expect(meApi.update).toHaveBeenCalledWith({ displayName: '华东运维 张工' })
    expect(auth.user?.displayName).toBe('华东运维 张工')
  })

  it('clears everything when the cookie cannot restore the session', async () => {
    vi.mocked(authApi.refresh).mockRejectedValue(new Error('401'))
    const auth = useAuthStore()
    auth.setSession(session('t1'))
    auth.passwordChangeRequired = true

    expect(await auth.restore()).toBe(false)
    expect(auth.token).toBeNull()
    expect(auth.user).toBeNull()
    expect(auth.passwordChangeRequired).toBe(false)
  })
})
