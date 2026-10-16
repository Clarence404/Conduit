/** TenantCreateDialog 组件测试：编码、名称、用户名、密码按规则校验后才请求；成功后发出 created 并关闭；接口错误显示在对话框内 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import TenantCreateDialog from './TenantCreateDialog.vue'
import { platformApi } from '@/api/platform'
import type { TenantView } from '@/api/types'

vi.mock('@/api/platform', () => ({ platformApi: { createTenant: vi.fn() } }))

type Vm = {
  form: { code: string; name: string; username: string; displayName: string; password: string }
  error: string
  submit(): Promise<void>
}

const created: TenantView = {
  code: 'east',
  name: '验收租户（华东）',
  status: 'ACTIVE',
  adminCount: 1,
  userCount: 1,
  productCount: 0,
  deviceCount: 0,
  onlineCount: 0,
  createdAt: '2026-10-16T00:00:00Z',
}

function mountDialog() {
  const wrapper = mount(TenantCreateDialog, {
    props: { modelValue: true },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
  return { wrapper, vm: wrapper.vm as unknown as Vm }
}

function fill(vm: Vm) {
  vm.form.code = 'east'
  vm.form.name = ' 验收租户（华东） '
  vm.form.username = 'east-admin'
  vm.form.displayName = ''
  vm.form.password = 'Init-pass-2026'
}

describe('TenantCreateDialog', () => {
  beforeEach(() => vi.clearAllMocks())

  it('validates the fields before calling the server', async () => {
    const { wrapper, vm } = mountDialog()

    fill(vm)
    vm.form.code = 'East_1'
    await vm.submit()
    expect(vm.error).toContain('租户编码须为 2～32 位')

    fill(vm)
    vm.form.name = '  '
    await vm.submit()
    expect(vm.error).toBe('请输入租户名称')

    fill(vm)
    vm.form.username = '1abc'
    await vm.submit()
    expect(vm.error).toContain('用户名须为 3～32 位')

    fill(vm)
    vm.form.password = 'weak'
    await vm.submit()
    expect(vm.error).toBe('密码须为 8～64 位，且同时包含字母和数字')
    expect(platformApi.createTenant).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('creates the tenant with its first admin and closes', async () => {
    vi.mocked(platformApi.createTenant).mockResolvedValue(created)
    const { wrapper, vm } = mountDialog()

    fill(vm)
    await vm.submit()

    expect(platformApi.createTenant).toHaveBeenCalledWith({
      code: 'east',
      name: '验收租户（华东）',
      admin: { username: 'east-admin', displayName: null, password: 'Init-pass-2026' },
    })
    expect(wrapper.emitted('created')).toEqual([[created]])
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
    wrapper.unmount()
  })

  it('shows the server error', async () => {
    vi.mocked(platformApi.createTenant).mockRejectedValue(new Error('租户编码已存在: east'))
    const { wrapper, vm } = mountDialog()

    fill(vm)
    await vm.submit()

    expect(vm.error).toBe('租户编码已存在: east')
    expect(wrapper.emitted('created')).toBeUndefined()
    wrapper.unmount()
  })
})
