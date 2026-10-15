/** PageHeader 组件测试：标题、副标题（属性或插槽）与右侧操作插槽 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import PageHeader from './PageHeader.vue'

describe('PageHeader', () => {
  it('renders the title, the subtitle and the actions', () => {
    const wrapper = mount(PageHeader, {
      props: { title: '产品', subtitle: '一类设备的模板' },
      slots: { actions: '<button>新建产品</button>' },
    })

    expect(wrapper.find('h2').text()).toBe('产品')
    expect(wrapper.find('.subtitle').text()).toBe('一类设备的模板')
    expect(wrapper.find('.actions button').text()).toBe('新建产品')
  })

  it('omits the subtitle and the actions when not given, and accepts a subtitle slot', () => {
    expect(mount(PageHeader, { props: { title: '设备' } }).find('.subtitle').exists()).toBe(false)
    expect(mount(PageHeader, { props: { title: '设备' } }).find('.actions').exists()).toBe(false)

    const slotted = mount(PageHeader, { props: { title: '设备' }, slots: { subtitle: '<code>pk1</code>' } })
    expect(slotted.find('.subtitle code').text()).toBe('pk1')
  })
})
