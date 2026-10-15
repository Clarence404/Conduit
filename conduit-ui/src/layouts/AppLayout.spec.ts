/** AppLayout 组件测试：菜单分组与按路由归属高亮、禁用的 V0.2-C 菜单、折叠并保存偏好、侧栏风格类名、面包屑、租户名称与退出登录 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import AppLayout from './AppLayout.vue'
import { authApi } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { UI_STORAGE_KEY, useUiStore } from '@/stores/ui'
import { createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/auth', () => ({ authApi: { login: vi.fn(), refresh: vi.fn(), logout: vi.fn() } }))

async function mountAt(path: string) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.token = 'token'
  auth.user = { username: 'admin', role: 'ADMIN', tenantName: '默认租户' }
  const router = createTestRouter()
  await router.push(path)
  await router.isReady()
  const wrapper = mount(AppLayout, { global: { plugins: [pinia, router, ElementPlus] }, attachTo: document.body })
  await settle()
  return { wrapper, router }
}

describe('AppLayout', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    vi.mocked(authApi.logout).mockResolvedValue(undefined)
  })

  it('groups the menu and highlights 设备 on a device detail page', async () => {
    const { wrapper } = await mountAt('/devices/dk1')

    expect(wrapper.text()).toContain('设备接入')
    expect(wrapper.text()).toContain('系统管理')
    expect(wrapper.find('.el-menu-item.is-active').text()).toBe('设备')
    wrapper.unmount()
  })

  it('highlights 产品 on a product detail page', async () => {
    const { wrapper } = await mountAt('/products/pk1')

    expect(wrapper.find('.el-menu-item.is-active').text()).toBe('产品')
    wrapper.unmount()
  })

  it('shows 用户 and 租户 disabled with the V0.2-C note', async () => {
    const { wrapper } = await mountAt('/devices')

    const disabled = wrapper.findAll('.el-menu-item.is-disabled')
    expect(disabled).toHaveLength(2)
    expect(disabled[0].text()).toContain('用户')
    expect(disabled[1].text()).toContain('租户')
    disabled.forEach((d) => expect(d.text()).toContain('V0.2-C'))
    wrapper.unmount()
  })

  it('collapses from the toggle and remembers it', async () => {
    const { wrapper } = await mountAt('/devices')

    expect(wrapper.find('.el-menu').classes()).not.toContain('el-menu--collapse')
    await wrapper.find('.collapse-toggle').trigger('click')
    await settle()

    expect(wrapper.find('.el-menu').classes()).toContain('el-menu--collapse')
    expect(JSON.parse(localStorage.getItem(UI_STORAGE_KEY)!).sidebarCollapsed).toBe(true)
    wrapper.unmount()
  })

  it('uses the dark sidebar by default and follows the sidebar style preference', async () => {
    const { wrapper } = await mountAt('/devices')

    expect(wrapper.find('.sidebar').classes()).toContain('cd-sidebar--dark')
    useUiStore().setSidebarStyle('light')
    await nextTick()

    expect(wrapper.find('.sidebar').classes()).toContain('cd-sidebar--light')
    wrapper.unmount()
  })

  it('shows the breadcrumb from the route meta, the tenant name and the avatar initial', async () => {
    const { wrapper } = await mountAt('/devices/dk1')

    const crumbs = wrapper.findAll('.breadcrumb .el-breadcrumb__inner').map((c) => c.text())
    expect(crumbs).toEqual(['设备接入', '设备'])
    expect(wrapper.find('.tenant').text()).toBe('默认租户')
    expect(wrapper.find('.el-avatar').text()).toBe('A')
    wrapper.unmount()
  })

  it('logs out from the user menu and returns to the login page', async () => {
    const { wrapper, router } = await mountAt('/devices')

    ;(wrapper.vm as unknown as { onCommand(command: string): void }).onCommand('logout')
    await settle()

    expect(authApi.logout).toHaveBeenCalledOnce()
    expect(useAuthStore().user).toBeNull()
    expect(router.currentRoute.value.name).toBe('login')
    wrapper.unmount()
  })

  it('shows the 概览 group with 首页 highlighted on the home page', async () => {
    const { wrapper } = await mountAt('/home')

    expect(wrapper.text()).toContain('概览')
    expect(wrapper.find('.el-menu-item.is-active').text()).toBe('首页')
    expect(wrapper.findAll('.breadcrumb .el-breadcrumb__inner').map((c) => c.text())).toEqual(['概览', '首页'])
    wrapper.unmount()
  })
})
