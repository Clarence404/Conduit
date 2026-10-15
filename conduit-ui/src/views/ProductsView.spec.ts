import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ProductsView from './ProductsView.vue'
import { productApi } from '@/api/products'
import { codecApi } from '@/api/codecs'
import { createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/products', () => ({
  productApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
}))
vi.mock('@/api/codecs', () => ({ codecApi: { list: vi.fn() } }))

describe('ProductsView', () => {
  beforeEach(() => {
    vi.mocked(productApi.list).mockResolvedValue({
      items: [
        {
          productKey: 'pk1',
          name: '三相电表（演示）',
          description: null,
          codec: 'standard-json',
          propertyCount: 7,
          createdAt: '2026-10-01T00:00:00Z',
        },
      ],
      total: 1,
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
