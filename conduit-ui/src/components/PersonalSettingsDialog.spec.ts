/** PersonalSettingsDialog 组件测试：修改与清除显示名称、本地校验后修改密码、代入期间提示不能修改密码 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import PersonalSettingsDialog from './PersonalSettingsDialog.vue'
import { meApi } from '@/api/me'
import { useAuthStore } from '@/stores/auth'
import type { UserView } from '@/api/types'
import { createTestPinia, settle } from '@/test/viewTestUtils'

vi.mock('@/api/auth', () => ({ authApi: { login: vi.fn(), refresh: vi.fn(), logout: vi.fn(), exitTenant: vi.fn() } }))
vi.mock('@/api/me', () => ({ meApi: { update: vi.fn(), changePassword: vi.fn() } }))
vi.mock('@/api/platform', () => ({ platformApi: { enter: vi.fn() } }))

type Vm = {
  displayName: string
  pw: { oldPassword: string; newPassword: string; confirm: string }
  error: string
  saveProfile(): Promise<void>
  savePassword(): Promise<void>
}

const me: UserView = {
  username: 'east-admin',
  displayName: '华东运维',
  role: 'TENANT_ADMIN',
  tenantCode: 'east',
  tenantName: '验收租户（华东）',
  mustChangePassword: false,
  impersonatedBy: null,
}

async function mountDialog(user: Partial<UserView> = {}) {
  const pinia = createTestPinia({ ...me, ...user })
  const wrapper = mount(PersonalSettingsDialog, {
    props: { modelValue: true },
    global: { plugins: [pinia, ElementPlus] },
    attachTo: document.body,
  })
  await settle()
  return { wrapper, vm: wrapper.vm as unknown as Vm }
}

describe('PersonalSettingsDialog', () => {
  beforeEach(() => vi.clearAllMocks())

  it('starts with the current display name and saves a new one', async () => {
    vi.mocked(meApi.update).mockResolvedValue({ ...me, displayName: '华东运维 张工' })
    const { wrapper, vm } = await mountDialog()

    expect(vm.displayName).toBe('华东运维')
    vm.displayName = '  华东运维 张工 '
    await vm.saveProfile()

    expect(meApi.update).toHaveBeenCalledWith({ displayName: '华东运维 张工' })
    expect(useAuthStore().user?.displayName).toBe('华东运维 张工')
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
    wrapper.unmount()
  })

  it('clears the display name with a blank value', async () => {
    vi.mocked(meApi.update).mockResolvedValue({ ...me, displayName: null })
    const { wrapper, vm } = await mountDialog()

    vm.displayName = '   '
    await vm.saveProfile()

    expect(meApi.update).toHaveBeenCalledWith({ displayName: null })
    wrapper.unmount()
  })

  it('changes the password after local checks', async () => {
    vi.mocked(meApi.changePassword).mockResolvedValue({ accessToken: 'fresh', expiresIn: 900, user: me })
    const { wrapper, vm } = await mountDialog()

    vm.pw.oldPassword = 'Active-pass-2026'
    vm.pw.newPassword = 'Newer-pass-2026'
    vm.pw.confirm = 'Other-pass-2026'
    await vm.savePassword()
    expect(vm.error).toBe('两次输入的新密码不一致')
    expect(meApi.changePassword).not.toHaveBeenCalled()

    vm.pw.confirm = 'Newer-pass-2026'
    await vm.savePassword()

    expect(meApi.changePassword).toHaveBeenCalledWith({ oldPassword: 'Active-pass-2026', newPassword: 'Newer-pass-2026' })
    expect(useAuthStore().token).toBe('fresh')
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
    wrapper.unmount()
  })

  it('tells an impersonating platform admin to exit before changing the password', async () => {
    const { wrapper } = await mountDialog({ username: 'sysadmin', impersonatedBy: 'sysadmin' })

    expect(document.body.textContent).toContain('代入租户期间不能修改密码')
    wrapper.unmount()
  })
})
