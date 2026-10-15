/** OnlineDot 组件测试：在线绿点「在线」、离线灰点「离线」 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import OnlineDot from './OnlineDot.vue'

describe('OnlineDot', () => {
  it('renders the online and offline states', () => {
    const on = mount(OnlineDot, { props: { online: true } })
    const off = mount(OnlineDot, { props: { online: false } })

    expect(on.text()).toBe('在线')
    expect(on.classes()).toContain('is-online')
    expect(off.text()).toBe('离线')
    expect(off.classes()).not.toContain('is-online')
  })
})
