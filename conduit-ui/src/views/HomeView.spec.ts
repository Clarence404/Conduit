/** HomeView 组件测试：4 个指标卡的数值与副文、在线相关字段为 null 时显示「—」、两个列表跳转、每 30 秒刷新 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import HomeView from './HomeView.vue'
import { overviewApi } from '@/api/overview'
import type { OverviewView } from '@/api/types'
import { createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/overview', () => ({ overviewApi: { get: vi.fn() } }))

const NOW = Date.UTC(2026, 9, 15, 8, 0, 0)

function overview(patch: Partial<OverviewView> = {}): OverviewView {
  return {
    productCount: 2,
    propertyCount: 15,
    deviceCount: 200,
    enabledCount: 198,
    disabledCount: 2,
    onlineCount: 100,
    deadLastHour: { total: 4, byReason: { FIELD_REJECTED: 3, PROCESSING_ERROR: 1 } },
    recentDevices: [
      { deviceKey: 'dk1', name: '1号楼配电室 电表01', productKey: 'pk1', productName: '三相电表（演示）', lastReportAt: NOW - 5000 },
    ],
    products: [
      { productKey: 'pk1', name: '三相电表（演示）', deviceCount: 200, onlineCount: 100 },
      { productKey: 'pk2', name: '水表（演示）', deviceCount: 0, onlineCount: 0 },
    ],
    ...patch,
  }
}

async function mountView() {
  const router = createTestRouter()
  await router.push('/home')
  await router.isReady()
  const wrapper = mount(HomeView, { global: { plugins: [router, ElementPlus] }, attachTo: document.body })
  await settle()
  return { wrapper, router }
}

const tile = (wrapper: Awaited<ReturnType<typeof mountView>>['wrapper'], name: string) =>
  wrapper.find(`[data-test="tile-${name}"]`).text()

describe('HomeView', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] })
    vi.setSystemTime(NOW)
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false })
    vi.clearAllMocks()
    vi.mocked(overviewApi.get).mockResolvedValue(overview())
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders the four tiles with their counts and summaries', async () => {
    const { wrapper } = await mountView()

    expect(tile(wrapper, 'products')).toContain('2')
    expect(tile(wrapper, 'products')).toContain('共定义 15 个属性')
    expect(tile(wrapper, 'devices')).toContain('200')
    expect(tile(wrapper, 'devices')).toContain('已启用 198 · 已禁用 2')
    expect(tile(wrapper, 'online')).toContain('100')
    expect(tile(wrapper, 'online')).toContain('在线率 50%')
    expect(tile(wrapper, 'dead')).toContain('4')
    expect(tile(wrapper, 'dead')).toContain('测点被剔除 3 · 处理出错 1')
    wrapper.unmount()
  })

  it('shows — for unknown presence fields and 无 for zero dead letters', async () => {
    vi.mocked(overviewApi.get).mockResolvedValue(
      overview({
        onlineCount: null,
        deadLastHour: null,
        recentDevices: [],
        products: [{ productKey: 'pk1', name: '三相电表（演示）', deviceCount: 200, onlineCount: null }],
      }),
    )
    const { wrapper } = await mountView()

    expect(tile(wrapper, 'online')).toContain('—')
    expect(tile(wrapper, 'online')).toContain('在线状态暂不可用')
    expect(tile(wrapper, 'dead')).toContain('—')
    expect(tile(wrapper, 'dead')).toContain('暂不可用')
    expect(wrapper.find('.by-product').text()).toContain('— / 200')
    expect(wrapper.find('.recent').text()).toContain('暂无上报')
    wrapper.unmount()

    vi.mocked(overviewApi.get).mockResolvedValue(overview({ deadLastHour: { total: 0, byReason: {} } }))
    const second = await mountView()
    expect(tile(second.wrapper, 'dead')).toContain('无')
    second.wrapper.unmount()
  })

  it('opens a recently reported device and a product from the lists', async () => {
    const { wrapper, router } = await mountView()

    await wrapper.find('.recent .row').trigger('click')
    await settle()
    expect(router.currentRoute.value.name).toBe('device-detail')
    expect(router.currentRoute.value.params.deviceKey).toBe('dk1')

    await wrapper.find('.by-product .row').trigger('click')
    await settle()
    expect(router.currentRoute.value.name).toBe('product-detail')
    expect(router.currentRoute.value.params.productKey).toBe('pk1')
    wrapper.unmount()
  })

  it('refreshes every 30 seconds and stops on unmount', async () => {
    const { wrapper } = await mountView()
    expect(overviewApi.get).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(30_000)
    expect(overviewApi.get).toHaveBeenCalledTimes(2)

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(90_000)
    expect(overviewApi.get).toHaveBeenCalledTimes(2)
  })

  it('links the devices tile to /devices and the online tile to /devices?online=true', async () => {
    const { wrapper, router } = await mountView()

    await wrapper.find('[data-test="tile-devices"]').trigger('click')
    await settle()
    expect(router.currentRoute.value.fullPath).toBe('/devices')

    await router.push('/home')
    await wrapper.find('[data-test="tile-online"]').trigger('click')
    await settle()
    expect(router.currentRoute.value.fullPath).toBe('/devices?online=true')
    wrapper.unmount()
  })

  it('does not make the online tile a link when the online count is unknown', async () => {
    vi.mocked(overviewApi.get).mockResolvedValue(overview({ onlineCount: null }))
    const { wrapper, router } = await mountView()

    const online = wrapper.find('[data-test="tile-online"]')
    expect(online.attributes('role')).toBeUndefined()
    await online.trigger('click')
    await settle()
    expect(router.currentRoute.value.fullPath).toBe('/home')
    wrapper.unmount()
  })
})
