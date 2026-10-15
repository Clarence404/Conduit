/** ProductsView 组件测试：首屏请求第 1 页 10 条、「设备（在线/总数）」列、空状态引导新建、首次加载失败显示「加载失败」并可重试、行内按钮跳转到详情的标签 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import ProductsView from './ProductsView.vue'
import { productApi } from '@/api/products'
import { codecApi } from '@/api/codecs'
import type { ProductView } from '@/api/types'
import { createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/products', () => ({
  productApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
}))
vi.mock('@/api/codecs', () => ({ codecApi: { list: vi.fn() } }))

function product(patch: Partial<ProductView> = {}): ProductView {
  return {
    productKey: 'pk1',
    name: '三相电表（演示）',
    description: null,
    codec: 'standard-json',
    propertyCount: 7,
    deviceCount: 3,
    onlineCount: 2,
    createdAt: '2026-10-01T00:00:00Z',
    ...patch,
  }
}

describe('ProductsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(productApi.list).mockResolvedValue({
      items: [product(), product({ productKey: 'pk2', name: '水表（演示）', deviceCount: 5, onlineCount: null })],
      total: 2,
      page: 1,
      size: 10,
    })
    vi.mocked(codecApi.list).mockResolvedValue([])
  })

  async function mountView() {
    const router = createTestRouter()
    await router.push('/products')
    await router.isReady()
    const wrapper = mount(ProductsView, { global: { plugins: [router, ElementPlus] }, attachTo: document.body })
    await settle()
    return { wrapper, router }
  }

  it('requests the first page with 10 rows', async () => {
    const { wrapper } = await mountView()

    expect(productApi.list).toHaveBeenCalledWith(1, 10)
    wrapper.unmount()
  })

  it('shows online / total devices, with — when the online count is unknown', async () => {
    const { wrapper } = await mountView()

    const links = wrapper.findAll('.device-count')
    expect(links.map((l) => l.text())).toEqual(['2 / 3', '— / 5'])
    wrapper.unmount()
  })

  it('opens the devices tab of the product from the device count', async () => {
    const { wrapper, router } = await mountView()

    await wrapper.find('.device-count').trigger('click')
    await settle()

    expect(router.currentRoute.value.name).toBe('product-detail')
    expect(router.currentRoute.value.query).toEqual({ tab: 'devices' })
    wrapper.unmount()
  })

  it('guides to create the first product when the list is empty', async () => {
    vi.mocked(productApi.list).mockResolvedValue({ items: [], total: 0, page: 1, size: 10 })
    const { wrapper } = await mountView()

    const empty = wrapper.find('.el-empty')
    expect(empty.text()).toContain('还没有产品')
    await empty.findAll('button').find((b) => b.text() === '新建产品')!.trigger('click')

    expect((wrapper.vm as unknown as { dialogVisible: boolean }).dialogVisible).toBe(true)
    wrapper.unmount()
  })

  it('shows a retryable failure state instead of the create guide when the first load fails', async () => {
    vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: () => {} }) as never)
    vi.mocked(productApi.list).mockRejectedValueOnce(new Error('服务不可用'))
    const { wrapper } = await mountView()

    const empty = wrapper.find('.el-empty')
    expect(empty.text()).toContain('加载失败')
    expect(empty.text()).not.toContain('还没有产品')

    await empty.findAll('button').find((b) => b.text() === '重试')!.trigger('click')
    await settle()

    expect(productApi.list).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.el-table').text()).toContain('三相电表（演示）')
    wrapper.unmount()
  })

  it('opens the thing-model tab of the product from the 物模型 button', async () => {
    const { wrapper, router } = await mountView()

    const button = wrapper.findAll('button').find((b) => b.text() === '物模型')
    expect(button).toBeDefined()
    await button!.trigger('click')
    await settle()

    expect(router.currentRoute.value.name).toBe('product-detail')
    expect(router.currentRoute.value.params.productKey).toBe('pk1')
    expect(router.currentRoute.value.query).toEqual({ tab: 'model' })
    wrapper.unmount()
  })

  it('opens the basic tab from the 详情 button', async () => {
    const { wrapper, router } = await mountView()

    await wrapper.findAll('button').find((b) => b.text() === '详情')!.trigger('click')
    await settle()

    expect(router.currentRoute.value.name).toBe('product-detail')
    expect(router.currentRoute.value.query).toEqual({})
    wrapper.unmount()
  })
})
