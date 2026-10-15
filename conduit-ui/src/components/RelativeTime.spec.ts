/** RelativeTime 组件测试：相对时间文案、悬停显示完整时间、每分钟自动更新、空值 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import RelativeTime from './RelativeTime.vue'
import { formatDateTime } from '@/utils/format'

const NOW = new Date(2026, 9, 15, 8, 0, 0).getTime()

describe('RelativeTime', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(NOW)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows the relative text, the full time on hover and updates every minute', async () => {
    const value = NOW - 3 * 60_000
    const wrapper = mount(RelativeTime, { props: { value } })

    expect(wrapper.text()).toBe('3 分钟前')
    expect(wrapper.attributes('title')).toBe(formatDateTime(value))

    await vi.advanceTimersByTimeAsync(60_000)
    expect(wrapper.text()).toBe('4 分钟前')
    wrapper.unmount()
  })

  it('shows — without a title for an empty value', () => {
    const wrapper = mount(RelativeTime, { props: { value: null } })

    expect(wrapper.text()).toBe('—')
    expect(wrapper.attributes('title')).toBeUndefined()
    wrapper.unmount()
  })
})
