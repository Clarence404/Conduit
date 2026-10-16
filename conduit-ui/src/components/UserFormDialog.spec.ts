/** UserFormDialog 组件测试：新建前按规则校验并提交、编辑只改显示名称与角色、编辑自己时角色锁定、接口错误显示在对话框内 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import ElementPlus, { ElRadioGroup } from 'element-plus'
import UserFormDialog from './UserFormDialog.vue'
import { userApi } from '@/api/users'
import type { TenantUserView } from '@/api/types'

vi.mock('@/api/users', () => ({ userApi: { create: vi.fn(), update: vi.fn() } }))

type Vm = {
  form: { username: string; displayName: string; role: string; password: string }
  error: string
  submit(): Promise<void>
}

const viewer: TenantUserView = {
  username: 'east-viewer',
  displayName: '华东运维 张工',
  role: 'TENANT_VIEWER',
  status: 'ACTIVE',
  mustChangePassword: false,
  createdAt: '2026-10-16T00:00:00Z',
}

function mountDialog(user: TenantUserView | null, editingSelf = false) {
  const wrapper = mount(UserFormDialog, {
    props: { modelValue: true, user, editingSelf },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
  return { wrapper, vm: wrapper.vm as unknown as Vm }
}

describe('UserFormDialog', () => {
  beforeEach(() => vi.clearAllMocks())

  it('validates and creates a user', async () => {
    vi.mocked(userApi.create).mockResolvedValue(viewer)
    const { wrapper, vm } = mountDialog(null)

    vm.form.username = '1abc'
    vm.form.password = 'Viewer-pass-1'
    await vm.submit()
    expect(vm.error).toContain('用户名须为 3～32 位')

    vm.form.username = 'east-viewer'
    vm.form.password = 'weak'
    await vm.submit()
    expect(vm.error).toBe('密码须为 8～64 位，且同时包含字母和数字')
    expect(userApi.create).not.toHaveBeenCalled()

    vm.form.password = 'Viewer-pass-1'
    vm.form.displayName = ' 华东运维 张工 '
    vm.form.role = 'TENANT_VIEWER'
    await vm.submit()

    expect(userApi.create).toHaveBeenCalledWith({
      username: 'east-viewer',
      displayName: '华东运维 张工',
      role: 'TENANT_VIEWER',
      password: 'Viewer-pass-1',
    })
    expect(wrapper.emitted('saved')).toEqual([[viewer]])
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
    wrapper.unmount()
  })

  it('edits the display name and the role', async () => {
    vi.mocked(userApi.update).mockResolvedValue({ ...viewer, displayName: null, role: 'TENANT_ADMIN' })
    const { wrapper, vm } = mountDialog(viewer)

    expect(vm.form.displayName).toBe('华东运维 张工')
    vm.form.displayName = '  '
    vm.form.role = 'TENANT_ADMIN'
    await vm.submit()

    expect(userApi.update).toHaveBeenCalledWith('east-viewer', { displayName: null, role: 'TENANT_ADMIN' })
    wrapper.unmount()
  })

  it('locks the role when editing oneself', async () => {
    const { wrapper } = mountDialog({ ...viewer, role: 'TENANT_ADMIN' }, true)
    await nextTick()

    expect(wrapper.findComponent(ElRadioGroup).props('disabled')).toBe(true)
    wrapper.unmount()
  })

  it('shows the server error', async () => {
    vi.mocked(userApi.create).mockRejectedValue(new Error('用户名已存在: east-viewer'))
    const { wrapper, vm } = mountDialog(null)

    vm.form.username = 'east-viewer'
    vm.form.password = 'Viewer-pass-1'
    await vm.submit()

    expect(vm.error).toBe('用户名已存在: east-viewer')
    expect(wrapper.emitted('saved')).toBeUndefined()
    wrapper.unmount()
  })
})
