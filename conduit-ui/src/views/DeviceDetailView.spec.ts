/** DeviceDetailView 组件测试：「最新值 | 历史」标签与 ?tab= 同步（router.replace），历史标签才挂载 HistoryPanel */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import DeviceDetailView from './DeviceDetailView.vue'
import HistoryPanel from '@/components/HistoryPanel.vue'
import { deviceApi } from '@/api/devices'
import { productApi } from '@/api/products'
import type { DeviceView } from '@/api/types'
import { createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/devices', () => ({
  deviceApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), resetSecret: vi.fn(), remove: vi.fn(), latest: vi.fn() },
}))
vi.mock('@/api/products', () => ({
  productApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
}))
// HistoryPanel 被 stub，但其模块仍会被导入：一并 mock 掉它依赖的接口与 ECharts
vi.mock('@/api/history', () => ({ historyApi: { query: vi.fn() } }))
vi.mock('@/api/thingModel', () => ({
  thingModelApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(), exportModel: vi.fn(), importModel: vi.fn() },
}))
vi.mock('@/utils/echarts', () => ({ echarts: { init: vi.fn(), connect: vi.fn() } }))

function device(): DeviceView {
  return {
    deviceKey: 'dk1',
    name: '1号楼配电室 电表01',
    productKey: 'pk1',
    status: 'ENABLED',
    online: true,
    lastReportAt: null,
    createdAt: '2026-10-01T00:00:00Z',
  }
}

async function mountAt(path: string) {
  const router = createTestRouter()
  await router.push(path)
  await router.isReady()
  const replace = vi.spyOn(router, 'replace')
  const push = vi.spyOn(router, 'push')
  const wrapper = mount(DeviceDetailView, {
    props: { deviceKey: 'dk1' },
    global: { plugins: [router, ElementPlus], stubs: { HistoryPanel: true } },
    attachTo: document.body,
  })
  await settle()
  return { wrapper, router, replace, push }
}

function activeTabLabel(wrapper: ReturnType<typeof mount>) {
  return wrapper.find('.el-tabs__item.is-active').text()
}

describe('DeviceDetailView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(deviceApi.get).mockResolvedValue(device())
    vi.mocked(deviceApi.latest).mockResolvedValue({ deviceKey: 'dk1', values: [] })
    vi.mocked(productApi.get).mockResolvedValue({
      productKey: 'pk1',
      name: '三相电表（演示）',
      description: null,
      codec: 'standard-json',
      propertyCount: 8,
      createdAt: '2026-10-01T00:00:00Z',
    })
  })

  it('shows latest values by default without mounting the history panel', async () => {
    const { wrapper } = await mountAt('/devices/dk1')

    expect(activeTabLabel(wrapper)).toBe('最新值')
    expect(wrapper.findComponent(HistoryPanel).exists()).toBe(false)
    wrapper.unmount()
  })

  it('opens the history tab from ?tab=history and passes the device and product keys', async () => {
    const { wrapper } = await mountAt('/devices/dk1?tab=history')

    expect(activeTabLabel(wrapper)).toBe('历史')
    const panel = wrapper.findComponent(HistoryPanel)
    expect(panel.exists()).toBe(true)
    expect(panel.props('deviceKey')).toBe('dk1')
    expect(panel.props('productKey')).toBe('pk1')
    wrapper.unmount()
  })

  it('writes the clicked tab to the query with replace, not push', async () => {
    const { wrapper, router, replace, push } = await mountAt('/devices/dk1')

    await wrapper.find('#tab-history').trigger('click')
    await settle()

    expect(replace).toHaveBeenCalledWith({ query: { tab: 'history' } })
    expect(push).not.toHaveBeenCalled()
    expect(router.currentRoute.value.query).toEqual({ tab: 'history' })
    expect(wrapper.findComponent(HistoryPanel).exists()).toBe(true)

    await wrapper.find('#tab-latest').trigger('click')
    await settle()
    expect(router.currentRoute.value.query).toEqual({})
    expect(wrapper.findComponent(HistoryPanel).exists()).toBe(false)
    wrapper.unmount()
  })
})
