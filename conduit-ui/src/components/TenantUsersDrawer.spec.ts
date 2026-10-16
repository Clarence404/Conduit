/** TenantUsersDrawer 组件测试：打开时加载该租户的用户；角色、状态与待改密码标签；只有租户管理员行有「重置密码」；重置后重新加载 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import TenantUsersDrawer from './TenantUsersDrawer.vue'
import { platformApi } from '@/api/platform'
import type { TenantUserView, TenantView } from '@/api/types'
import { settle } from '@/test/viewTestUtils'

vi.mock('@/api/platform', () => ({ platformApi: { tenantUsers: vi.fn(), resetAdminPassword: vi.fn() } }))

const tenant: TenantView = {
  code: 'east',
  name: '验收租户（华东）',
  status: 'ACTIVE',
  adminCount: 1,
  userCount: 2,
  productCount: 0,
  deviceCount: 0,
  onlineCount: 0,
  createdAt: '2026-10-16T00:00:00Z',
}

const users: TenantUserView[] = [
  { username: 'east-admin', displayName: '华东管理员', role: 'TENANT_ADMIN', status: 'ACTIVE', mustChangePassword: true, createdAt: '2026-10-16T00:00:00Z' },
  { username: 'east-viewer', displayName: null, role: 'TENANT_VIEWER', status: 'DISABLED', mustChangePassword: false, createdAt: '2026-10-16T00:00:00Z' },
]

async function mountDrawer() {
  const wrapper = mount(TenantUsersDrawer, {
    props: { modelValue: true, tenant },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
  await settle()
  return wrapper
}

describe('TenantUsersDrawer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(platformApi.tenantUsers).mockResolvedValue(users)
    vi.mocked(platformApi.resetAdminPassword).mockResolvedValue(undefined)
  })

  it('renders as a dialog (el-drawer of Element Plus 2.11.1 opens with zero width)', async () => {
    const wrapper = await mountDrawer()
    expect(wrapper.findComponent({ name: 'ElDialog' }).exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'ElDrawer' }).exists()).toBe(false)
    wrapper.unmount()
  })

  it('lists the tenant users with role and status tags', async () => {
    const wrapper = await mountDrawer()

    expect(platformApi.tenantUsers).toHaveBeenCalledWith('east')
    const text = wrapper.find('.el-table').text()
    expect(text).toContain('华东管理员')
    expect(text).toContain('租户管理员')
    expect(text).toContain('只读用户')
    expect(text).toContain('禁用')
    expect(text).toContain('待改密码')
    wrapper.unmount()
  })

  it('offers a password reset only for tenant admins and reloads after it', async () => {
    const wrapper = await mountDrawer()

    const resetButtons = wrapper.findAll('.el-table button').filter((b) => b.text() === '重置密码')
    expect(resetButtons).toHaveLength(1)

    const vm = wrapper.vm as unknown as { openReset(u: TenantUserView): void; savePassword(p: string): Promise<void> }
    vm.openReset(users[0])
    await vm.savePassword('Reset-pass-9')

    expect(platformApi.resetAdminPassword).toHaveBeenCalledWith('east', 'east-admin', 'Reset-pass-9')
    expect(platformApi.tenantUsers).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('clears the old list on a tenant switch and ignores a late response of the previous tenant', async () => {
    const west = { ...tenant, code: 'west', name: '验收租户（华西）' }
    const westUsers: TenantUserView[] = [{ ...users[1], username: 'west-viewer' }]
    let resolveEast!: (v: TenantUserView[]) => void
    vi.mocked(platformApi.tenantUsers).mockImplementation((code: string) =>
      code === 'east' ? new Promise<TenantUserView[]>((r) => (resolveEast = r)) : Promise.resolve(westUsers),
    )
    const wrapper = mount(TenantUsersDrawer, {
      props: { modelValue: true, tenant },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()

    await wrapper.setProps({ tenant: west })
    await settle()
    resolveEast(users)
    await settle()

    const text = wrapper.find('.el-table').text()
    expect(text).toContain('west-viewer')
    expect(text).not.toContain('east-admin')
    wrapper.unmount()
  })

  it('shows no users of the previous tenant while the next tenant is loading', async () => {
    const wrapper = await mountDrawer()
    expect(wrapper.find('.el-table').text()).toContain('east-admin')
    vi.mocked(platformApi.tenantUsers).mockReturnValue(new Promise(() => {}))

    await wrapper.setProps({ tenant: { ...tenant, code: 'west', name: '验收租户（华西）' } })
    await settle()

    expect(wrapper.find('.el-table').text()).not.toContain('east-admin')
    wrapper.unmount()
  })
})
