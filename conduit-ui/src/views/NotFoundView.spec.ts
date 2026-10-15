/** NotFoundView 组件测试：未知地址显示「页面不存在」，「回到首页」跳转到根路径 */
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import NotFoundView from './NotFoundView.vue'
import { createTestRouter, settle } from '@/test/viewTestUtils'

describe('NotFoundView', () => {
  it('explains the page does not exist and goes home', async () => {
    const router = createTestRouter()
    await router.push('/no/such/page')
    await router.isReady()
    const push = vi.spyOn(router, 'push').mockResolvedValue(undefined)
    const wrapper = mount(NotFoundView, { global: { plugins: [router, ElementPlus] } })

    expect(router.currentRoute.value.name).toBe('not-found')
    expect(wrapper.text()).toContain('页面不存在')
    await wrapper.findAll('button').find((b) => b.text() === '回到首页')!.trigger('click')
    await settle()

    expect(push).toHaveBeenCalledWith('/')
    wrapper.unmount()
  })
})
