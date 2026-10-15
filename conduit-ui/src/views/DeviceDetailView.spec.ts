/**
 * DeviceDetailView 组件测试：信息卡、「最新值 | 历史」标签与 ?tab= 同步（router.replace）、历史标签才挂载 HistoryPanel 并传入 ?props=、
 * 点击卡片跳到历史并只选中该测点、连接参数弹窗、编辑名称与启用状态、切换到另一台设备时清掉旧面包屑名称并重新加载。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import DeviceDetailView from './DeviceDetailView.vue'
import HistoryPanel from '@/components/HistoryPanel.vue'
import LatestValuesPanel from '@/components/LatestValuesPanel.vue'
import ConnectionDialog from '@/components/ConnectionDialog.vue'
import { deviceApi } from '@/api/devices'
import { productApi } from '@/api/products'
import { thingModelApi } from '@/api/thingModel'
import type { DeviceView } from '@/api/types'
import { useCurrentPage } from '@/composables/useBreadcrumb'
import { createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/devices', () => ({
  deviceApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), resetSecret: vi.fn(), remove: vi.fn(), latest: vi.fn() },
}))
vi.mock('@/api/products', () => ({
  productApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
}))
// 被 stub 的面板模块仍会被导入：一并 mock 掉它们依赖的接口与 ECharts
vi.mock('@/api/history', () => ({ historyApi: { query: vi.fn() } }))
vi.mock('@/api/thingModel', () => ({
  thingModelApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(), exportModel: vi.fn(), importModel: vi.fn() },
}))
vi.mock('@/utils/echarts', () => ({ echarts: { init: vi.fn(), connect: vi.fn(), disconnect: vi.fn() } }))

function device(patch: Partial<DeviceView> = {}): DeviceView {
  return {
    deviceKey: 'dk1',
    name: '1号楼配电室 电表01',
    productKey: 'pk1',
    status: 'ENABLED',
    online: true,
    lastReportAt: null,
    createdAt: '2026-10-01T00:00:00Z',
    ...patch,
  }
}

async function mountAt(path: string) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createTestRouter()
  await router.push(path)
  await router.isReady()
  const replace = vi.spyOn(router, 'replace')
  const push = vi.spyOn(router, 'push')
  const wrapper = mount(DeviceDetailView, {
    props: { deviceKey: 'dk1' },
    global: { plugins: [pinia, router, ElementPlus], stubs: { HistoryPanel: true, LatestValuesPanel: true } },
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
    vi.mocked(thingModelApi.list).mockResolvedValue([])
    vi.mocked(productApi.get).mockResolvedValue({
      productKey: 'pk1',
      name: '三相电表（演示）',
      description: null,
      codec: 'standard-json',
      propertyCount: 8,
      deviceCount: 100,
      onlineCount: 100,
      createdAt: '2026-10-01T00:00:00Z',
    })
  })

  it('shows the info card and the latest values without mounting the history panel', async () => {
    const { wrapper } = await mountAt('/devices/dk1')

    const card = wrapper.find('.info-card')
    expect(card.find('h2').text()).toBe('1号楼配电室 电表01')
    expect(card.text()).toContain('三相电表（演示）')
    expect(card.text()).toContain('在线')
    expect(card.text()).toContain('启用')
    expect(activeTabLabel(wrapper)).toBe('最新值')
    expect(wrapper.findComponent(LatestValuesPanel).props('deviceKey')).toBe('dk1')
    expect(wrapper.findComponent(HistoryPanel).exists()).toBe(false)
    wrapper.unmount()
  })

  it('opens the history tab from the query and passes the device, product and ?props=', async () => {
    const { wrapper } = await mountAt('/devices/dk1?tab=history&props=Ua,Ub')

    expect(activeTabLabel(wrapper)).toBe('历史')
    const panel = wrapper.findComponent(HistoryPanel)
    expect(panel.props('deviceKey')).toBe('dk1')
    expect(panel.props('productKey')).toBe('pk1')
    expect(panel.props('initialProps')).toEqual(['Ua', 'Ub'])
    expect(wrapper.findComponent(LatestValuesPanel).exists()).toBe(false)
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

  it('opens the history of a clicked card with only that property selected', async () => {
    const { wrapper, router, replace } = await mountAt('/devices/dk1')

    wrapper.findComponent(LatestValuesPanel).vm.$emit('open-history', 'Ua')
    await settle()

    expect(replace).toHaveBeenCalledWith({ query: { tab: 'history', props: 'Ua' } })
    expect(router.currentRoute.value.query).toEqual({ tab: 'history', props: 'Ua' })
    expect(wrapper.findComponent(HistoryPanel).props('initialProps')).toEqual(['Ua'])
    wrapper.unmount()
  })

  it('opens the connection dialog from the info card', async () => {
    const { wrapper } = await mountAt('/devices/dk1')

    await wrapper.findAll('.info-card button').find((b) => b.text() === '连接参数')!.trigger('click')

    expect(wrapper.findComponent(ConnectionDialog).props('modelValue')).toBe(true)
    wrapper.unmount()
  })

  it('edits the name and the enabled state', async () => {
    vi.mocked(deviceApi.update).mockResolvedValue(device({ name: '1号楼配电室 电表99', status: 'DISABLED' }))
    const { wrapper } = await mountAt('/devices/dk1')
    const vm = wrapper.vm as unknown as {
      editVisible: boolean
      editForm: { name: string; enabled: boolean }
      saveEdit(): Promise<void>
    }

    await wrapper.findAll('.info-card button').find((b) => b.text() === '编辑')!.trigger('click')
    expect(vm.editVisible).toBe(true)
    expect(vm.editForm).toEqual({ name: '1号楼配电室 电表01', enabled: true })

    vm.editForm.name = ' 1号楼配电室 电表99 '
    vm.editForm.enabled = false
    await vm.saveEdit()
    await settle()

    expect(deviceApi.update).toHaveBeenCalledWith('dk1', '1号楼配电室 电表99', 'DISABLED')
    expect(vm.editVisible).toBe(false)
    expect(wrapper.find('.info-card h2').text()).toBe('1号楼配电室 电表99')
    expect(wrapper.find('.info-card').text()).toContain('禁用')
    wrapper.unmount()
  })

  it('shows a result page when the device cannot be loaded', async () => {
    vi.mocked(deviceApi.get).mockRejectedValue(new Error('设备不存在: dk1'))
    const { wrapper } = await mountAt('/devices/dk1')

    expect(wrapper.text()).toContain('无法加载设备')
    wrapper.unmount()
  })

  it('clears the breadcrumb name and reloads when the device changes while the component is reused', async () => {
    const { wrapper } = await mountAt('/devices/dk1')
    const { breadcrumb } = (wrapper.vm.$ as unknown as { appContext: { app: { runWithContext<T>(fn: () => T): T } } }).appContext.app.runWithContext(
      () => useCurrentPage(),
    )
    expect(breadcrumb.value.at(-1)?.label).toBe('1号楼配电室 电表01')

    // 设备 B 的详情还没返回：面包屑不能还显示设备 A 的名称
    let resolveB: (d: DeviceView) => void = () => {}
    vi.mocked(deviceApi.get).mockReturnValue(new Promise<DeviceView>((r) => (resolveB = r)))
    await wrapper.setProps({ deviceKey: 'dk2' })
    await settle()

    expect(deviceApi.get).toHaveBeenLastCalledWith('dk2')
    expect(breadcrumb.value.map((b) => b.label)).not.toContain('1号楼配电室 电表01')
    expect(wrapper.find('.info-card').exists()).toBe(false)

    resolveB(device({ deviceKey: 'dk2', name: '2号楼配电室 电表02' }))
    await settle()
    expect(breadcrumb.value.at(-1)?.label).toBe('2号楼配电室 电表02')
    expect(wrapper.find('.info-card h2').text()).toBe('2号楼配电室 电表02')
    wrapper.unmount()
  })
})
