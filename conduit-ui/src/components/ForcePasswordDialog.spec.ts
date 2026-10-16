/**
 * ForcePasswordDialog 组件测试：按会话标记打开且不能关闭；接口要求改密码时打开；新密码不合规或两次不一致时不请求；
 * 修改成功后关闭；接口错误显示在对话框内；可以退出登录。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import ElementPlus, { ElDialog } from 'element-plus'
import ForcePasswordDialog from './ForcePasswordDialog.vue'
import { authApi } from '@/api/auth'
import { meApi } from '@/api/me'
import { useAuthStore } from '@/stores/auth'
import type { UserView } from '@/api/types'
import { createTestPinia, createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/auth', () => ({ authApi: { login: vi.fn(), refresh: vi.fn(), logout: vi.fn(), exitTenant: vi.fn() } }))
vi.mock('@/api/me', () => ({ meApi: { update: vi.fn(), changePassword: vi.fn() } }))
vi.mock('@/api/platform', () => ({ platformApi: { enter: vi.fn() } }))

type Vm = {
  form: { oldPassword: string; newPassword: string; confirm: string }
  error: string
  submit(): Promise<void>
  logout(): Promise<void>
}

async function mountDialog(user: Partial<UserView> = {}) {
  const pinia = createTestPinia(user)
  const router = createTestRouter()
  await router.push('/home')
  await router.isReady()
  const wrapper = mount(ForcePasswordDialog, { global: { plugins: [pinia, router, ElementPlus] }, attachTo: document.body })
  await settle()
  return { wrapper, router, vm: wrapper.vm as unknown as Vm }
}

describe('ForcePasswordDialog', () => {
  beforeEach(() => vi.clearAllMocks())

  it('opens for a user who must change the password and cannot be closed', async () => {
    const { wrapper } = await mountDialog({ mustChangePassword: true })

    const dialog = wrapper.findComponent(ElDialog)
    expect(dialog.props('modelValue')).toBe(true)
    expect(dialog.props('showClose')).toBe(false)
    expect(dialog.props('closeOnClickModal')).toBe(false)
    expect(dialog.props('closeOnPressEscape')).toBe(false)
    wrapper.unmount()
  })

  it('stays closed otherwise and opens when an endpoint asks for a password change', async () => {
    const { wrapper } = await mountDialog()
    expect(wrapper.findComponent(ElDialog).props('modelValue')).toBe(false)

    useAuthStore().passwordChangeRequired = true
    await nextTick()

    expect(wrapper.findComponent(ElDialog).props('modelValue')).toBe(true)
    wrapper.unmount()
  })

  it('checks the new password locally before calling the server', async () => {
    const { wrapper, vm } = await mountDialog({ mustChangePassword: true })

    vm.form.oldPassword = 'Init-pass-2026'
    vm.form.newPassword = 'short1'
    vm.form.confirm = 'short1'
    await vm.submit()
    expect(vm.error).toBe('密码须为 8～64 位，且同时包含字母和数字')

    vm.form.newPassword = 'Active-pass-2026'
    vm.form.confirm = 'Active-pass-2027'
    await vm.submit()
    expect(vm.error).toBe('两次输入的新密码不一致')
    expect(meApi.changePassword).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('changes the password and closes', async () => {
    vi.mocked(meApi.changePassword).mockResolvedValue({
      accessToken: 'fresh',
      expiresIn: 900,
      user: {
        username: 'admin',
        displayName: null,
        role: 'TENANT_ADMIN',
        tenantCode: 'default',
        tenantName: '默认租户',
        mustChangePassword: false,
        impersonatedBy: null,
      },
    })
    const { wrapper, vm } = await mountDialog({ mustChangePassword: true })

    vm.form.oldPassword = 'Init-pass-2026'
    vm.form.newPassword = 'Active-pass-2026'
    vm.form.confirm = 'Active-pass-2026'
    await vm.submit()
    await settle()

    expect(meApi.changePassword).toHaveBeenCalledWith({ oldPassword: 'Init-pass-2026', newPassword: 'Active-pass-2026' })
    expect(useAuthStore().token).toBe('fresh')
    expect(wrapper.findComponent(ElDialog).props('modelValue')).toBe(false)
    wrapper.unmount()
  })

  it('shows the server error inside the dialog', async () => {
    vi.mocked(meApi.changePassword).mockRejectedValue(new Error('原密码错误'))
    const { wrapper, vm } = await mountDialog({ mustChangePassword: true })

    vm.form.oldPassword = 'Wrong-pass-1'
    vm.form.newPassword = 'Active-pass-2026'
    vm.form.confirm = 'Active-pass-2026'
    await vm.submit()

    expect(vm.error).toBe('原密码错误')
    expect(wrapper.findComponent(ElDialog).props('modelValue')).toBe(true)
    wrapper.unmount()
  })

  it('logs out', async () => {
    vi.mocked(authApi.logout).mockResolvedValue(undefined)
    const { wrapper, router, vm } = await mountDialog({ mustChangePassword: true })

    await vm.logout()
    await settle()

    expect(useAuthStore().token).toBeNull()
    expect(router.currentRoute.value.name).toBe('login')
    wrapper.unmount()
  })
})
