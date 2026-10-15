/** Sparkline 组件测试：按当前主题创建实例、数据变化时重绘、主题切换时按新主题重建、卸载时销毁 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import Sparkline from './Sparkline.vue'
import { echarts } from '@/utils/echarts'
import { applyTheme } from '@/composables/useTheme'

/** 代替 ECharts 实例的假对象 */
interface FakeChart {
  setOption: ReturnType<typeof vi.fn>
  dispose: ReturnType<typeof vi.fn>
  resize: ReturnType<typeof vi.fn>
}

const fake = vi.hoisted(() => ({ charts: [] as FakeChart[] }))

vi.mock('@/utils/echarts', () => ({
  echarts: {
    init: vi.fn(() => {
      const chart: FakeChart = { setOption: vi.fn(), dispose: vi.fn(), resize: vi.fn() }
      fake.charts.push(chart)
      return chart
    }),
  },
}))

describe('Sparkline', () => {
  beforeEach(() => {
    fake.charts.length = 0
    vi.clearAllMocks()
  })

  afterEach(() => {
    applyTheme(false)
  })

  it('creates the chart with the current theme, redraws on new points and disposes on unmount', async () => {
    const wrapper = mount(Sparkline, { props: { points: [[1, 2], [3, 4]] } })

    expect(echarts.init).toHaveBeenCalledTimes(1)
    expect(vi.mocked(echarts.init).mock.calls[0][1]).toBeUndefined()
    expect(fake.charts[0].setOption).toHaveBeenCalledTimes(1)

    await wrapper.setProps({ points: [[1, 2], [3, 5]] })
    expect(fake.charts[0].setOption).toHaveBeenCalledTimes(2)

    wrapper.unmount()
    expect(fake.charts[0].dispose).toHaveBeenCalled()
  })

  it('rebuilds the chart with the dark theme when the theme changes', async () => {
    const wrapper = mount(Sparkline, { props: { points: [[1, 2], [3, 4]] } })

    applyTheme(true)
    await nextTick()

    expect(fake.charts[0].dispose).toHaveBeenCalled()
    expect(vi.mocked(echarts.init).mock.calls[1][1]).toBe('dark')
    expect(fake.charts[1].setOption).toHaveBeenCalled()
    wrapper.unmount()
  })
})
