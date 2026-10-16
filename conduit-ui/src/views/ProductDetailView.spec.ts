/**
 * ProductDetailView 组件测试：标签与 ?tab= 同步（router.replace）、页头显示产品 Key 与设备计数、
 * 属性变化后只刷新计数不覆盖未保存的基本信息、属性前端分页（每页 10 条）与删除后页码修正、设备标签的在线圆点、
 * 属性或设备首次加载失败时显示「加载失败」而不是引导新增。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElPagination, ElTable } from 'element-plus'
import ProductDetailView from './ProductDetailView.vue'
import PropertyDialog from '@/components/PropertyDialog.vue'
import { productApi } from '@/api/products'
import { codecApi } from '@/api/codecs'
import { thingModelApi } from '@/api/thingModel'
import { deviceApi } from '@/api/devices'
import type { ProductView, PropertyView, UserView } from '@/api/types'
import { createTestPinia, createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/products', () => ({
  productApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
}))
vi.mock('@/api/codecs', () => ({ codecApi: { list: vi.fn() } }))
vi.mock('@/api/thingModel', () => ({
  thingModelApi: {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
    exportModel: vi.fn(),
    importModel: vi.fn(),
  },
}))
vi.mock('@/api/devices', () => ({
  deviceApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), resetSecret: vi.fn(), remove: vi.fn(), latest: vi.fn() },
}))
// 删除前的确认框直接确认
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return { ...actual, ElMessageBox: { ...actual.ElMessageBox, confirm: vi.fn().mockResolvedValue('confirm') } }
})

function product(patch: Partial<ProductView> = {}): ProductView {
  return {
    productKey: 'pk1',
    name: '三相电表（演示）',
    description: '原描述',
    codec: 'standard-json',
    propertyCount: 12,
    deviceCount: 5,
    onlineCount: 4,
    createdAt: '2026-10-01T00:00:00Z',
    ...patch,
  }
}

function properties(count: number): PropertyView[] {
  return Array.from({ length: count }, (_, i) => ({
    identifier: `P${String(i + 1).padStart(2, '0')}`,
    name: `属性${i + 1}`,
    dataType: 'DOUBLE' as const,
    unit: null,
    minValue: null,
    maxValue: null,
    decimals: null,
    enumItems: null,
    description: null,
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
  }))
}

async function mountAt(path: string, stubs: Record<string, boolean> = {}, user: Partial<UserView> = {}) {
  const router = createTestRouter()
  await router.push(path)
  await router.isReady()
  const replace = vi.spyOn(router, 'replace')
  const push = vi.spyOn(router, 'push')
  const wrapper = mount(ProductDetailView, {
    props: { productKey: 'pk1' },
    global: { plugins: [createTestPinia(user), router, ElementPlus], stubs },
    attachTo: document.body,
  })
  await settle()
  return { wrapper, router, replace, push }
}

function activeTabLabel(wrapper: ReturnType<typeof mount>) {
  return wrapper.find('.el-tabs__item.is-active').text()
}

describe('ProductDetailView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(productApi.get).mockResolvedValue(product())
    vi.mocked(codecApi.list).mockResolvedValue([{ name: 'standard-json', displayName: '标准 JSON' }])
    vi.mocked(thingModelApi.list).mockResolvedValue(properties(12))
    vi.mocked(thingModelApi.remove).mockResolvedValue(undefined)
    vi.mocked(deviceApi.list).mockResolvedValue({
      items: [
        {
          deviceKey: 'dk1',
          name: '1号楼配电室 电表01',
          productKey: 'pk1',
          status: 'ENABLED',
          online: true,
          lastReportAt: null,
          createdAt: '2026-10-01T00:00:00Z',
        },
      ],
      total: 1,
      page: 1,
      size: 10,
    })
  })

  describe('tab and query sync', () => {
    it('opens the tab named in ?tab=', async () => {
      const { wrapper } = await mountAt('/products/pk1?tab=model')

      expect(activeTabLabel(wrapper)).toBe('物模型')
      wrapper.unmount()
    })

    it('defaults to basic for a missing or unknown tab', async () => {
      const { wrapper } = await mountAt('/products/pk1?tab=nope')

      expect(activeTabLabel(wrapper)).toBe('基本信息')
      wrapper.unmount()
    })

    it('writes the clicked tab to the query with replace, not push', async () => {
      const { wrapper, router, replace, push } = await mountAt('/products/pk1?tab=model')

      await wrapper.find('#tab-devices').trigger('click')
      await settle()

      expect(replace).toHaveBeenCalledWith({ query: { tab: 'devices' } })
      expect(push).not.toHaveBeenCalled()
      expect(router.currentRoute.value.query).toEqual({ tab: 'devices' })
      expect(activeTabLabel(wrapper)).toBe('设备')
      expect(deviceApi.list).toHaveBeenCalledWith({ productKey: 'pk1', page: 1, size: 10 })

      await wrapper.find('#tab-basic').trigger('click')
      await settle()
      expect(router.currentRoute.value.query).toEqual({})
      wrapper.unmount()
    })

    it('follows query changes made elsewhere', async () => {
      const { wrapper, router } = await mountAt('/products/pk1')

      await router.replace({ query: { tab: 'model' } })
      await settle()

      expect(activeTabLabel(wrapper)).toBe('物模型')
      wrapper.unmount()
    })
  })

  it('shows the product name, key and device counts in the page header', async () => {
    const { wrapper } = await mountAt('/products/pk1')

    const header = wrapper.find('.page-header')
    expect(header.find('h2').text()).toBe('三相电表（演示）')
    expect(header.text()).toContain('pk1')
    expect(header.text()).toContain('设备 5（在线 4）')
    wrapper.unmount()
  })

  it('shows the online dot in the devices tab', async () => {
    const { wrapper } = await mountAt('/products/pk1?tab=devices')

    expect(wrapper.find('.online-dot').text()).toBe('在线')
    wrapper.unmount()
  })

  it('shows a retryable failure state in the property table when its first load fails', async () => {
    vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: () => {} }) as never)
    vi.mocked(thingModelApi.list).mockRejectedValueOnce(new Error('服务不可用'))
    const { wrapper } = await mountAt('/products/pk1?tab=model')

    const empty = wrapper.findAll('.el-empty').find((e) => e.text().includes('加载失败'))
    expect(empty).toBeDefined()
    expect(wrapper.text()).not.toContain('暂无属性')

    await empty!.findAll('button').find((b) => b.text() === '重试')!.trigger('click')
    await settle()
    expect(thingModelApi.list).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain('加载失败')
    wrapper.unmount()
  })

  it('shows a retryable failure state in the device table when its first load fails', async () => {
    vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: () => {} }) as never)
    vi.mocked(deviceApi.list).mockRejectedValueOnce(new Error('服务不可用'))
    const { wrapper } = await mountAt('/products/pk1?tab=devices')

    const empty = wrapper.findAll('.el-empty').find((e) => e.text().includes('加载失败'))
    expect(empty).toBeDefined()
    expect(wrapper.text()).not.toContain('该产品下暂无设备')

    await empty!.findAll('button').find((b) => b.text() === '重试')!.trigger('click')
    await settle()
    expect(deviceApi.list).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.online-dot').text()).toBe('在线')
    wrapper.unmount()
  })

  it('keeps unsaved basic-info edits and refreshes only the counts after a property change', async () => {
    const { wrapper } = await mountAt('/products/pk1')
    const nameInput = wrapper.find('.basic input')
    await nameInput.setValue('改了还没保存')

    vi.mocked(productApi.get).mockResolvedValue(product({ name: '服务端名称', description: '服务端描述', propertyCount: 13 }))
    vi.mocked(thingModelApi.list).mockResolvedValue(properties(13))
    wrapper.findComponent(PropertyDialog).vm.$emit('saved')
    await settle()

    expect(thingModelApi.list).toHaveBeenCalledTimes(2)
    expect((wrapper.find('.basic input').element as HTMLInputElement).value).toBe('改了还没保存')
    expect((wrapper.find('.basic textarea').element as HTMLTextAreaElement).value).toBe('原描述')
    const vm = wrapper.vm as unknown as { product: ProductView }
    expect(vm.product.propertyCount).toBe(13)
    expect(vm.product.name).toBe('三相电表（演示）')
    wrapper.unmount()
  })

  it('pages properties by 10 and moves back to the last page when a delete shrinks the list', async () => {
    // 分页器用空壳替换：Element 自带的越界修正不能替我们的逻辑兜底
    const { wrapper } = await mountAt('/products/pk1?tab=model', { ElPagination: true })
    const table = () => wrapper.findAllComponents(ElTable)[0]
    const pager = () => wrapper.findAllComponents(ElPagination)[0]

    expect(pager().props('total')).toBe(12)
    expect(table().props('data')).toHaveLength(10)

    pager().vm.$emit('update:current-page', 2)
    pager().vm.$emit('current-change', 2)
    await settle()
    expect(table().props('data')).toHaveLength(2)

    // 删掉第 2 页剩下的属性之一后服务端只剩 10 条，第 2 页不再存在
    vi.mocked(thingModelApi.list).mockResolvedValue(properties(10))
    const remove = table().findAll('button').find((b) => b.text() === '删除')
    await remove!.trigger('click')
    await settle()

    expect(thingModelApi.remove).toHaveBeenCalledWith('pk1', 'P11')
    expect(pager().props('total')).toBe(10)
    expect(pager().props('currentPage')).toBe(1)
    expect((wrapper.vm as unknown as { propertyPage: number }).propertyPage).toBe(1)
    expect(table().props('data')).toHaveLength(10)
    wrapper.unmount()
  })

  it('shows a viewer the product read-only', async () => {
    const { wrapper } = await mountAt('/products/pk1?tab=model', {}, { role: 'TENANT_VIEWER' })

    const labels = wrapper.findAll('button').map((b) => b.text())
    expect(labels).toContain('导出')
    for (const hidden of ['导入', '新增属性', '编辑', '删除', '保存']) {
      expect(labels).not.toContain(hidden)
    }
    expect(wrapper.find('.basic input').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
})
