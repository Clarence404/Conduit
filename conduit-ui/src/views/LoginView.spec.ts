/** LoginView 组件测试：登录成功跳到 redirect 或首页；接口错误与空输入的提示显示在表单内 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import LoginView from './LoginView.vue'
import { authApi } from '@/api/auth'
import { createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/auth', () => ({ authApi: { login: vi.fn(), refresh: vi.fn(), logout: vi.fn() } }))

async function mountAt(path: string) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createTestRouter()
  await router.push(path)
  await router.isReady()
  const wrapper = mount(LoginView, { global: { plugins: [pinia, router, ElementPlus] }, attachTo: document.body })
  await settle()
  return { wrapper, router }
}

async function submit(wrapper: Awaited<ReturnType<typeof mountAt>>['wrapper'], username: string, password: string) {
  const inputs = wrapper.findAll('input')
  await inputs[0].setValue(username)
  await inputs[1].setValue(password)
  await wrapper.find('form').trigger('submit')
  await settle()
}

describe('LoginView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(authApi.login).mockResolvedValue({
      accessToken: 'token',
      expiresIn: 900,
      user: {
        username: 'admin',
        displayName: null,
        role: 'TENANT_ADMIN',
        tenantCode: 'default',
        tenantName: 'Default',
        mustChangePassword: false,
        impersonatedBy: null,
      },
    })
  })

  it('shows the brand panel and logs in to the redirect target', async () => {
    const { wrapper, router } = await mountAt('/login?redirect=/devices/dk1')

    expect(wrapper.find('.brand').text()).toContain('Conduit 设备接入平台')
    expect(wrapper.find('.brand svg.conduit-logo').attributes('data-tone')).toBe('dark-bg')
    await submit(wrapper, 'admin', 'secret-pass')

    expect(authApi.login).toHaveBeenCalledWith('admin', 'secret-pass')
    expect(router.currentRoute.value.fullPath).toBe('/devices/dk1')
    wrapper.unmount()
  })

  it('goes to the home page without a redirect', async () => {
    const { wrapper, router } = await mountAt('/login')

    await submit(wrapper, 'admin', 'secret-pass')

    expect(router.currentRoute.value.name).toBe('home')
    wrapper.unmount()
  })

  it('shows the server error inside the form', async () => {
    vi.mocked(authApi.login).mockRejectedValue(new Error('用户名或密码错误'))
    const { wrapper, router } = await mountAt('/login')

    await submit(wrapper, 'admin', 'wrong')

    expect(wrapper.find('.login-error').text()).toContain('用户名或密码错误')
    expect(router.currentRoute.value.name).toBe('login')
    wrapper.unmount()
  })

  it('asks for both fields without calling the server', async () => {
    const { wrapper } = await mountAt('/login')

    await submit(wrapper, 'admin', '')

    expect(wrapper.find('.login-error').text()).toContain('请输入用户名和密码')
    expect(authApi.login).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
