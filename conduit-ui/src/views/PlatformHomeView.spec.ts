/** PlatformHomeView 组件测试：4 个指标卡的数值与副文、在线相关为 null 时显示「—」、点击租户卡与租户行进入租户管理（带筛选）、每 30 秒刷新 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import PlatformHomeView from './PlatformHomeView.vue'
import { platformApi } from '@/api/platform'
import type { PlatformOverview } from '@/api/types'
import { createTestPinia, createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/platform', () => ({ platformApi: { overview: vi.fn() } }))

function overview(patch: Partial<PlatformOverview> = {}): PlatformOverview {
  return {
    tenantCount: 3,
    activeTenantCount: 2,
    disabledTenantCount: 1,
    userCount: 12,
    productCount: 4,
    deviceCount: 200,
    onlineCount: 100,
    topTenants: [
      { code: 'east', name: '验收租户（华东）', deviceCount: 150, onlineCount: 80 },
      { code: 'default', name: 'Default', deviceCount: 50, onlineCount: 20 },
    ],
    ...patch,
  }
}

async function mountView() {
  const pinia = createTestPinia({ username: 'sysadmin', role: 'PLATFORM_ADMIN', tenantCode: null, tenantName: null })
  const router = createTestRouter()
  await router.push('/home')
  await router.isReady()
  const wrapper = mount(PlatformHomeView, { global: { plugins: [pinia, router, ElementPlus] }, attachTo: document.body })
  await settle()
  return { wrapper, router }
}

const tile = (wrapper: Awaited<ReturnType<typeof mountView>>['wrapper'], name: string) =>
  wrapper.find(`[data-test="tile-${name}"]`).text()

describe('PlatformHomeView', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false })
    vi.clearAllMocks()
    vi.mocked(platformApi.overview).mockResolvedValue(overview())
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders the four tiles', async () => {
    const { wrapper } = await mountView()

    expect(tile(wrapper, 'tenants')).toContain('3')
    expect(tile(wrapper, 'tenants')).toContain('启用 2 · 停用 1')
    expect(tile(wrapper, 'users')).toContain('12')
    expect(tile(wrapper, 'devices')).toContain('200')
    expect(tile(wrapper, 'devices')).toContain('共 4 个产品')
    expect(tile(wrapper, 'online')).toContain('50%')
    expect(tile(wrapper, 'online')).toContain('在线 100 / 设备 200')
    expect(wrapper.find('.top-tenants').text()).toContain('80 / 150')
    wrapper.unmount()
  })

  it('shows — when the online state is unknown', async () => {
    vi.mocked(platformApi.overview).mockResolvedValue(
      overview({ onlineCount: null, topTenants: [{ code: 'east', name: '验收租户（华东）', deviceCount: 150, onlineCount: null }] }),
    )
    const { wrapper } = await mountView()

    expect(tile(wrapper, 'online')).toContain('—')
    expect(tile(wrapper, 'online')).toContain('在线状态暂不可用')
    expect(wrapper.find('.top-tenants').text()).toContain('— / 150')
    wrapper.unmount()
  })

  it('opens the tenant list, filtered when a top tenant is clicked', async () => {
    const { wrapper, router } = await mountView()

    await wrapper.find('.top-tenants .row').trigger('click')
    await settle()
    expect(router.currentRoute.value.name).toBe('tenants')
    expect(router.currentRoute.value.query).toEqual({ keyword: 'east' })

    await router.push('/home')
    await wrapper.find('[data-test="tile-tenants"]').trigger('click')
    await settle()
    expect(router.currentRoute.value.fullPath).toBe('/tenants')
    wrapper.unmount()
  })

  it('refreshes every 30 seconds and stops on unmount', async () => {
    const { wrapper } = await mountView()
    expect(platformApi.overview).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(30_000)
    expect(platformApi.overview).toHaveBeenCalledTimes(2)

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(90_000)
    expect(platformApi.overview).toHaveBeenCalledTimes(2)
  })
})
