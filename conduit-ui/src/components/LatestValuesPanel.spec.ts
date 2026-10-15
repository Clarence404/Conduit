/**
 * LatestValuesPanel 组件测试：一次历史查询取前 8 个数值测点最近 1 小时的趋势、每 60 秒刷新；状态量卡片无趋势线；
 * 切到表格视图后保存偏好并停止趋势请求；物模型尚未加载完（modelReady=false）时不发趋势请求；点击卡片发出 open-history。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus, { ElRadioGroup, ElTable } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import LatestValuesPanel from './LatestValuesPanel.vue'
import MetricCard from './MetricCard.vue'
import Sparkline from './Sparkline.vue'
import { historyApi } from '@/api/history'
import type { HistoryView, PointView } from '@/api/types'
import { useUiStore } from '@/stores/ui'
import { settle } from '@/test/viewTestUtils'

vi.mock('@/api/history', () => ({ historyApi: { query: vi.fn() } }))
vi.mock('@/utils/echarts', () => ({
  echarts: { init: vi.fn(() => ({ setOption: vi.fn(), dispose: vi.fn(), resize: vi.fn() })) },
}))

const NOW = Date.UTC(2026, 9, 15, 8, 0, 0)
const IDS = Array.from({ length: 10 }, (_, i) => `P${String(i + 1).padStart(2, '0')}`)

function point(identifier: string, value: number, patch: Partial<PointView> = {}): PointView {
  return { identifier, name: null, value, ts: NOW - 5000, unit: null, decimals: null, enumLabel: null, ...patch }
}

const VALUES: PointView[] = [...IDS.map((id, i) => point(id, i)), point('st', 1, { name: '运行状态', enumLabel: '运行' })]

function view(): HistoryView {
  return {
    deviceKey: 'dk1',
    from: NOW - 3_600_000,
    to: NOW,
    bucketMs: null,
    series: [
      {
        identifier: 'P01',
        name: null,
        dataType: 'DOUBLE',
        unit: null,
        decimals: null,
        enumItems: null,
        mode: 'RAW',
        points: [
          [NOW - 60_000, 1],
          [NOW, 2],
        ],
      },
    ],
  }
}

async function mountPanel() {
  const wrapper = mount(LatestValuesPanel, {
    props: { deviceKey: 'dk1', values: VALUES, model: [], lastRefreshed: NOW },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
  await settle()
  return wrapper
}

function cardOf(wrapper: Awaited<ReturnType<typeof mountPanel>>, identifier: string) {
  return wrapper.findAllComponents(MetricCard).find((c) => c.props('card').identifier === identifier)!
}

describe('LatestValuesPanel', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] })
    vi.setSystemTime(NOW)
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false })
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(historyApi.query).mockResolvedValue(view())
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('queries the last hour of the first 8 numeric properties once and refreshes every 60 seconds', async () => {
    const wrapper = await mountPanel()

    expect(historyApi.query).toHaveBeenCalledTimes(1)
    expect(historyApi.query).toHaveBeenCalledWith('dk1', IDS.slice(0, 8), NOW - 3_600_000, NOW)

    await vi.advanceTimersByTimeAsync(60_000)
    expect(historyApi.query).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('draws sparklines only for numeric cards that have points and shows the enum label', async () => {
    const wrapper = await mountPanel()

    expect(wrapper.findAllComponents(MetricCard)).toHaveLength(11)
    expect(cardOf(wrapper, 'P01').findComponent(Sparkline).exists()).toBe(true)
    expect(cardOf(wrapper, 'P02').findComponent(Sparkline).exists()).toBe(false)
    expect(cardOf(wrapper, 'P09').props('points')).toBeNull()
    expect(cardOf(wrapper, 'st').props('points')).toBeNull()
    expect(cardOf(wrapper, 'st').text()).toContain('运行')
    wrapper.unmount()
  })

  it('switches to the table, remembers it and stops sparkline requests', async () => {
    const wrapper = await mountPanel()

    wrapper.findComponent(ElRadioGroup).vm.$emit('change', 'table')
    await settle()

    expect(useUiStore().latestView).toBe('table')
    expect(wrapper.findComponent(ElTable).exists()).toBe(true)
    expect(wrapper.findAllComponents(MetricCard)).toHaveLength(0)
    await vi.advanceTimersByTimeAsync(180_000)
    expect(historyApi.query).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('emits open-history when a card is clicked', async () => {
    const wrapper = await mountPanel()

    await cardOf(wrapper, 'P01').trigger('click')

    expect(wrapper.emitted('open-history')).toEqual([['P01']])
    wrapper.unmount()
  })

  it('waits for the thing model before querying sparklines so the first load issues a single query', async () => {
    const wrapper = mount(LatestValuesPanel, {
      props: { deviceKey: 'dk1', values: VALUES, model: [], lastRefreshed: NOW, modelReady: false },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()
    expect(historyApi.query).not.toHaveBeenCalled()

    // 物模型到达后卡片分类变化（例如枚举量不再当数值），此时才查询一次
    await wrapper.setProps({ model: [], modelReady: true })
    await settle()

    expect(historyApi.query).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('stops sparkline requests on unmount', async () => {
    const wrapper = await mountPanel()

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(180_000)

    expect(historyApi.query).toHaveBeenCalledTimes(1)
  })
})
