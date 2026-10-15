/** MetricCard 组件测试：数值 + 单位 + 趋势线、状态量文字标签不画趋势线、点数不足不画、点击与回车发出 select */
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import MetricCard from './MetricCard.vue'
import Sparkline from './Sparkline.vue'
import type { MetricCardModel } from '@/utils/metricCards'

vi.mock('@/utils/echarts', () => ({
  echarts: { init: vi.fn(() => ({ setOption: vi.fn(), dispose: vi.fn(), resize: vi.fn() })) },
}))

function card(patch: Partial<MetricCardModel> = {}): MetricCardModel {
  return { identifier: 'Ua', title: 'A 相电压', valueText: '220.1', unit: 'V', state: false, ts: Date.now() - 5000, ...patch }
}

function mountCard(c: MetricCardModel, points: [number, number][] | null) {
  return mount(MetricCard, { props: { card: c, points }, global: { plugins: [ElementPlus] } })
}

describe('MetricCard', () => {
  it('shows the number with its unit, the identifier, the sparkline and the relative time', () => {
    const wrapper = mountCard(card(), [
      [1, 220],
      [2, 221],
    ])

    expect(wrapper.find('.title').text()).toBe('A 相电压')
    expect(wrapper.find('.identifier').text()).toBe('Ua')
    expect(wrapper.find('.number').text()).toBe('220.1')
    expect(wrapper.find('.unit').text()).toBe('V')
    expect(wrapper.findComponent(Sparkline).exists()).toBe(true)
    expect(wrapper.text()).toContain('刚刚')
  })

  it('shows a state as a text label without a sparkline', () => {
    const wrapper = mountCard(card({ identifier: 'st', title: '运行状态', valueText: '运行', unit: null, state: true }), null)

    expect(wrapper.find('.state-tag').text()).toBe('运行')
    expect(wrapper.find('.number').exists()).toBe(false)
    expect(wrapper.findComponent(Sparkline).exists()).toBe(false)
  })

  it('does not draw a sparkline with fewer than two points', () => {
    expect(mountCard(card(), []).findComponent(Sparkline).exists()).toBe(false)
    expect(mountCard(card(), [[1, 220]]).findComponent(Sparkline).exists()).toBe(false)
  })

  it('emits select with the identifier on click and on Enter', async () => {
    const wrapper = mountCard(card(), null)

    await wrapper.trigger('click')
    await wrapper.trigger('keydown', { key: 'Enter' })

    expect(wrapper.emitted('select')).toEqual([['Ua'], ['Ua']])
  })
})
