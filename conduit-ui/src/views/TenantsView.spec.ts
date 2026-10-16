/**
 * TenantsView 组件测试：首屏第 1 页 10 条并带上 query 中的关键字、状态标签与计数、默认租户不显示停用与删除、停用租户不能进入、
 * 确认后进入租户并回到首页、停用后刷新、筛选与重置、改名、删除、空列表引导新建。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import TenantsView from './TenantsView.vue'
import { platformApi } from '@/api/platform'
import type { TenantView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { createTestPinia, createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/platform', () => ({
  platformApi: {
    overview: vi.fn(),
    listTenants: vi.fn(),
    createTenant: vi.fn(),
    updateTenant: vi.fn(),
    disableTenant: vi.fn(),
    enableTenant: vi.fn(),
    removeTenant: vi.fn(),
    tenantUsers: vi.fn(),
    resetAdminPassword: vi.fn(),
    enter: vi.fn(),
  },
}))
vi.mock('@/api/auth', () => ({ authApi: { login: vi.fn(), refresh: vi.fn(), logout: vi.fn(), exitTenant: vi.fn() } }))
vi.mock('@/api/me', () => ({ meApi: { update: vi.fn(), changePassword: vi.fn() } }))
// 确认框直接确认
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return { ...actual, ElMessageBox: { ...actual.ElMessageBox, confirm: vi.fn().mockResolvedValue('confirm') } }
})

function tenant(patch: Partial<TenantView> = {}): TenantView {
  return {
    code: 'east',
    name: '验收租户（华东）',
    status: 'ACTIVE',
    adminCount: 1,
    userCount: 2,
    productCount: 1,
    deviceCount: 5,
    onlineCount: null,
    createdAt: '2026-10-16T00:00:00Z',
    ...patch,
  }
}

type Vm = {
  filters: { keyword: string; status: string }
  editName: string
  createVisible: boolean
  search(): void
  reset(): void
  openEdit(row: TenantView): void
  saveEdit(): Promise<void>
}

async function mountView(url = '/tenants') {
  const pinia = createTestPinia({ username: 'sysadmin', role: 'PLATFORM_ADMIN', tenantCode: null, tenantName: null })
  const router = createTestRouter()
  await router.push(url)
  await router.isReady()
  const wrapper = mount(TenantsView, { global: { plugins: [pinia, router, ElementPlus] }, attachTo: document.body })
  await settle()
  return { wrapper, router, vm: wrapper.vm as unknown as Vm }
}

function rowButtons(wrapper: Awaited<ReturnType<typeof mountView>>['wrapper'], index: number) {
  return wrapper.findAll('.el-table__body tr')[index].findAll('button')
}

describe('TenantsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(platformApi.listTenants).mockResolvedValue({
      items: [
        tenant(),
        tenant({ code: 'south', name: '验收租户（华南）', status: 'DISABLED', onlineCount: 0 }),
        tenant({ code: 'default', name: 'Default', userCount: 1 }),
      ],
      total: 3,
      page: 1,
      size: 10,
    })
  })

  it('requests the first page with 10 rows and the keyword from the query', async () => {
    const { wrapper } = await mountView('/tenants?keyword=east')

    expect(platformApi.listTenants).toHaveBeenCalledWith({ keyword: 'east', status: undefined, page: 1, size: 10 })
    wrapper.unmount()
  })

  it('shows status tags and counts', async () => {
    const { wrapper } = await mountView()

    const text = wrapper.find('.el-table').text()
    expect(text).toContain('启用')
    expect(text).toContain('停用')
    expect(text).toContain('2（1）')
    expect(wrapper.findAll('.device-count').map((c) => c.text())).toEqual(['— / 5', '0 / 5', '— / 5'])
    wrapper.unmount()
  })

  it('hides disable and delete for the default tenant and blocks entering a disabled tenant', async () => {
    const { wrapper } = await mountView()

    expect(rowButtons(wrapper, 2).map((b) => b.text())).toEqual(['进入', '编辑', '用户'])
    const enterSouth = rowButtons(wrapper, 1).find((b) => b.text() === '进入')!
    expect(enterSouth.attributes('disabled')).toBeDefined()
    expect(rowButtons(wrapper, 1).map((b) => b.text())).toContain('启用')
    wrapper.unmount()
  })

  it('enters a tenant after confirming and goes home', async () => {
    vi.mocked(platformApi.enter).mockResolvedValue({
      accessToken: 'imp',
      expiresIn: 900,
      user: {
        username: 'sysadmin',
        displayName: null,
        role: 'TENANT_ADMIN',
        tenantCode: 'east',
        tenantName: '验收租户（华东）',
        mustChangePassword: false,
        impersonatedBy: 'sysadmin',
      },
    })
    const { wrapper, router } = await mountView()

    await rowButtons(wrapper, 0).find((b) => b.text() === '进入')!.trigger('click')
    await settle()

    expect(vi.mocked(ElMessageBox.confirm).mock.calls[0][0]).toBe(
      '将以租户管理员权限操作租户「验收租户（华东）」的数据，所有修改都会被记录',
    )
    expect(platformApi.enter).toHaveBeenCalledWith('east')
    expect(useAuthStore().user?.impersonatedBy).toBe('sysadmin')
    expect(router.currentRoute.value.name).toBe('home')
    wrapper.unmount()
  })

  it('disables a tenant after confirming and reloads', async () => {
    vi.mocked(platformApi.disableTenant).mockResolvedValue(tenant({ status: 'DISABLED' }))
    const { wrapper } = await mountView()

    await rowButtons(wrapper, 0).find((b) => b.text() === '停用')!.trigger('click')
    await settle()

    expect(platformApi.disableTenant).toHaveBeenCalledWith('east')
    expect(platformApi.listTenants).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('filters by status and resets', async () => {
    const { wrapper, vm } = await mountView()

    vm.filters.status = 'DISABLED'
    vm.search()
    await settle()
    expect(platformApi.listTenants).toHaveBeenLastCalledWith({ keyword: undefined, status: 'DISABLED', page: 1, size: 10 })

    vm.reset()
    await settle()
    expect(platformApi.listTenants).toHaveBeenLastCalledWith({ keyword: undefined, status: undefined, page: 1, size: 10 })
    wrapper.unmount()
  })

  it('renames a tenant', async () => {
    vi.mocked(platformApi.updateTenant).mockResolvedValue(tenant({ name: '验收租户（华东二区）' }))
    const { wrapper, vm } = await mountView()

    vm.openEdit(tenant())
    vm.editName = ' 验收租户（华东二区） '
    await vm.saveEdit()

    expect(platformApi.updateTenant).toHaveBeenCalledWith('east', { name: '验收租户（华东二区）' })
    wrapper.unmount()
  })

  it('deletes a tenant after confirming', async () => {
    vi.mocked(platformApi.removeTenant).mockResolvedValue(undefined)
    const { wrapper } = await mountView()

    await rowButtons(wrapper, 0).find((b) => b.text() === '删除')!.trigger('click')
    await settle()

    expect(platformApi.removeTenant).toHaveBeenCalledWith('east')
    wrapper.unmount()
  })

  it('guides to create the first tenant when the list is empty', async () => {
    vi.mocked(platformApi.listTenants).mockResolvedValue({ items: [], total: 0, page: 1, size: 10 })
    const { wrapper, vm } = await mountView()

    const empty = wrapper.find('.el-empty')
    expect(empty.text()).toContain('还没有租户')
    await empty.findAll('button').find((b) => b.text() === '新建租户')!.trigger('click')

    expect(vm.createVisible).toBe(true)
    wrapper.unmount()
  })
})
