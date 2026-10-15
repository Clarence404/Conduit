/**
 * DevicesView 组件测试：首屏第 1 页 10 条、筛选栏查询与重置、在线圆点与启用标签、操作列启用/禁用、
 * 翻页/空态/新建默认产品只用已应用的筛选条件、「刷新」保持当前页、首次加载失败显示「加载失败」并可重试。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import DevicesView from './DevicesView.vue'
import { deviceApi } from '@/api/devices'
import { productApi } from '@/api/products'
import type { DeviceView } from '@/api/types'
import { createTestRouter, settle } from '@/test/viewTestUtils'

const NOW = Date.UTC(2026, 9, 15, 8, 0, 0)

vi.mock('@/api/devices', () => ({
  deviceApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), resetSecret: vi.fn(), remove: vi.fn(), latest: vi.fn() },
}))
vi.mock('@/api/products', () => ({
  productApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
}))

function device(patch: Partial<DeviceView> = {}): DeviceView {
  return {
    deviceKey: 'dk1',
    name: '1号楼配电室 电表01',
    productKey: 'pk1',
    status: 'ENABLED',
    online: true,
    lastReportAt: NOW - 120_000,
    createdAt: '2026-10-01T00:00:00Z',
    ...patch,
  }
}

async function mountView() {
  const router = createTestRouter()
  await router.push('/devices')
  await router.isReady()
  const wrapper = mount(DevicesView, { global: { plugins: [router, ElementPlus] }, attachTo: document.body })
  await settle()
  return { wrapper, router }
}

describe('DevicesView', () => {
  beforeEach(() => {
    // 只固定 Date：「2 分钟前」依赖当前时间，不能随运行时刻漂移；其余计时器保持真实
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(NOW)
    vi.clearAllMocks()
    vi.mocked(productApi.list).mockResolvedValue({
      items: [
        {
          productKey: 'pk1',
          name: '三相电表（演示）',
          description: null,
          codec: 'standard-json',
          propertyCount: 8,
          deviceCount: 1,
          onlineCount: 1,
          createdAt: '2026-10-01T00:00:00Z',
        },
      ],
      total: 1,
      page: 1,
      size: 100,
    })
    vi.mocked(deviceApi.list).mockResolvedValue({ items: [device()], total: 1, page: 1, size: 10 })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('loads the first page with 10 rows and no filters', async () => {
    const { wrapper } = await mountView()

    expect(deviceApi.list).toHaveBeenCalledWith({ productKey: undefined, keyword: undefined, page: 1, size: 10 })
    wrapper.unmount()
  })

  it('searches with the keyword and resets the filters', async () => {
    const { wrapper } = await mountView()

    await wrapper.find('.filter-bar .keyword input').setValue(' 电表01 ')
    await wrapper.find('.filter-bar').trigger('submit')
    await settle()
    expect(deviceApi.list).toHaveBeenLastCalledWith({ productKey: undefined, keyword: '电表01', page: 1, size: 10 })

    await wrapper.findAll('.filter-bar button').find((b) => b.text() === '重置')!.trigger('click')
    await settle()
    expect(deviceApi.list).toHaveBeenLastCalledWith({ productKey: undefined, keyword: undefined, page: 1, size: 10 })
    wrapper.unmount()
  })

  it('shows the online dot, the enabled tag, the product name and the relative report time', async () => {
    const { wrapper } = await mountView()

    const text = wrapper.find('.el-table').text()
    expect(text).toContain('在线')
    expect(text).toContain('启用')
    expect(text).toContain('三相电表（演示）')
    expect(text).toContain('2 分钟前')
    wrapper.unmount()
  })

  it('disables a device from the action column', async () => {
    vi.mocked(deviceApi.update).mockResolvedValue(device({ status: 'DISABLED' }))
    const { wrapper } = await mountView()

    await wrapper.findAll('.el-table button').find((b) => b.text() === '禁用')!.trigger('click')
    await settle()

    expect(deviceApi.update).toHaveBeenCalledWith('dk1', '1号楼配电室 电表01', 'DISABLED')
    expect(wrapper.find('.el-table').text()).toContain('禁用')
    expect(wrapper.findAll('.el-table button').some((b) => b.text() === '启用')).toBe(true)
    wrapper.unmount()
  })
  it('keeps using the applied filters, not unsubmitted input, for paging and the create default', async () => {
    const { wrapper } = await mountView()
    const vm = wrapper.vm as unknown as {
      filters: { productKey: string; keyword: string }
      filtered: boolean
      createForm: { productKey: string }
      openCreate(): void
    }

    // 已应用：电表01；之后又在输入框里改了关键字和产品但没有点查询
    await wrapper.find('.filter-bar .keyword input').setValue('电表01')
    await wrapper.find('.filter-bar').trigger('submit')
    await settle()
    await wrapper.find('.filter-bar .keyword input').setValue('别的')
    vm.filters.productKey = 'pk-other'

    expect(vm.filtered).toBe(true)
    vm.openCreate()
    expect(vm.createForm.productKey).toBe('pk1')

    wrapper.findComponent({ name: 'ElPagination' }).vm.$emit('current-change', 2)
    await settle()
    expect(deviceApi.list).toHaveBeenLastCalledWith({ productKey: undefined, keyword: '电表01', page: 1, size: 10 })
    wrapper.unmount()
  })

  it('does not treat unsubmitted input as a filter', async () => {
    const { wrapper } = await mountView()
    const vm = wrapper.vm as unknown as {
      filters: { productKey: string; keyword: string }
      filtered: boolean
      createForm: { productKey: string }
      openCreate(): void
    }

    await wrapper.find('.filter-bar .keyword input').setValue('还没查询')
    vm.filters.productKey = 'pk-other'

    expect(vm.filtered).toBe(false)
    vm.openCreate()
    expect(vm.createForm.productKey).toBe('pk1')
    wrapper.unmount()
  })

  it('refreshes the current page without going back to page 1', async () => {
    vi.mocked(deviceApi.list).mockResolvedValue({ items: [device()], total: 35, page: 3, size: 10 })
    const { wrapper } = await mountView()
    const vm = wrapper.vm as unknown as { page: number }
    vm.page = 3
    await settle()

    await wrapper.findAll('button').find((b) => b.text() === '刷新')!.trigger('click')
    await settle()

    expect(deviceApi.list).toHaveBeenLastCalledWith({ productKey: undefined, keyword: undefined, page: 3, size: 10 })
    wrapper.unmount()
  })

  it('shows a retryable failure state instead of the create guide when the first load fails', async () => {
    vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: () => {} }) as never)
    vi.mocked(deviceApi.list).mockRejectedValueOnce(new Error('服务不可用'))
    const { wrapper } = await mountView()

    const empty = wrapper.find('.el-empty')
    expect(empty.text()).toContain('加载失败')
    expect(empty.text()).not.toContain('还没有设备')

    await empty.findAll('button').find((b) => b.text() === '重试')!.trigger('click')
    await settle()

    expect(deviceApi.list).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.el-table').text()).toContain('1号楼配电室 电表01')
    wrapper.unmount()
  })
})
