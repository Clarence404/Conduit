/** FilterBar 组件测试：默认插槽放筛选控件；提交（回车或「查询」）发出 search，「重置」发出 reset */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import FilterBar from './FilterBar.vue'

describe('FilterBar', () => {
  it('emits search on submit and reset from the reset button', async () => {
    const wrapper = mount(FilterBar, {
      global: { plugins: [ElementPlus] },
      slots: { default: '<input class="keyword" />' },
    })

    expect(wrapper.find('input.keyword').exists()).toBe(true)
    await wrapper.find('form').trigger('submit')
    expect(wrapper.emitted('search')).toHaveLength(1)

    await wrapper.findAll('button').find((b) => b.text() === '重置')!.trigger('click')
    expect(wrapper.emitted('reset')).toHaveLength(1)
  })
})
