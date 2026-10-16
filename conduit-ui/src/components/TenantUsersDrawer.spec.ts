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
})
