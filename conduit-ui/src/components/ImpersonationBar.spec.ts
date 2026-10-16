/** ImpersonationBar 组件测试：未代入时不显示；代入时显示租户名称，「退出」换回平台管理员并回到租户管理 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ImpersonationBar from './ImpersonationBar.vue'
import { authApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import type { UserView } from '@/api/types'
import { createTestPinia, createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/auth', () => ({ authApi: { login: vi.fn(), refresh: vi.fn(), logout: vi.fn(), exitTenant: vi.fn() } }))
vi.mock('@/api/me', () => ({ meApi: { update: vi.fn(), changePassword: vi.fn() } }))
vi.mock('@/api/platform', () => ({ platformApi: { enter: vi.fn() } }))

async function mountBar(user: Partial<UserView>) {
  const pinia = createTestPinia(user)
  const router = createTestRouter()
  await router.push('/products')
  await router.isReady()
  const wrapper = mount(ImpersonationBar, { global: { plugins: [pinia, router, ElementPlus] } })
  await settle()
  return { wrapper, router }
}

describe('ImpersonationBar', () => {
  beforeEach(() => vi.clearAllMocks())

  it('is hidden outside an impersonation', async () => {
    const { wrapper } = await mountBar({})

    expect(wrapper.find('.impersonation-bar').exists()).toBe(false)
  })

  it('names the tenant and exits back to the tenant list', async () => {
    vi.mocked(authApi.exitTenant).mockResolvedValue({
      accessToken: 'own',
      expiresIn: 900,
      user: {
        username: 'sysadmin',
        displayName: null,
        role: 'PLATFORM_ADMIN',
        tenantCode: null,
        tenantName: null,
        mustChangePassword: false,
        impersonatedBy: null,
      },
    })
    const { wrapper, router } = await mountBar({ username: 'sysadmin', impersonatedBy: 'sysadmin', tenantName: '验收租户（华东）' })

    expect(wrapper.find('.impersonation-bar').text()).toContain('正在以平台管理员身份操作租户：验收租户（华东）')
    await wrapper.find('.impersonation-bar button').trigger('click')
    await settle()

    expect(authApi.exitTenant).toHaveBeenCalledOnce()
    expect(useAuthStore().user?.role).toBe('PLATFORM_ADMIN')
    expect(router.currentRoute.value.name).toBe('tenants')
  })
})
