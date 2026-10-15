/** HistoryPanel 组件测试：默认查询、自动刷新、框选缩放重新查询并暂停刷新、重置缩放、乱序响应丢弃、空数据与聚合文案 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import HistoryPanel from './HistoryPanel.vue'
import { historyApi } from '@/api/history'
import { thingModelApi } from '@/api/thingModel'
import { deviceApi } from '@/api/devices'
import { echarts } from '@/utils/echarts'
import type { DataType, HistoryPoint, HistoryView, PointView, PropertyView, SeriesView } from '@/api/types'
import { settle } from '@/test/viewTestUtils'

/** 代替 ECharts 实例的假对象：记录 setOption，保存事件处理器以便测试触发 datazoom */
interface FakeChart {
  group: string
  handlers: Record<string, (params: unknown) => void>
  setOption: ReturnType<typeof vi.fn>
  dispatchAction: ReturnType<typeof vi.fn>
  dispose: ReturnType<typeof vi.fn>
  resize: ReturnType<typeof vi.fn>
  getDom: () => HTMLElement
  on: (name: string, handler: (params: unknown) => void) => void
}

const fake = vi.hoisted(() => ({ charts: [] as FakeChart[] }))

vi.mock('@/utils/echarts', () => ({
  echarts: {
    init: vi.fn((el: HTMLElement) => {
      const chart: FakeChart = {
        group: '',
        handlers: {},
        setOption: vi.fn(),
        dispatchAction: vi.fn(),
        dispose: vi.fn(),
        resize: vi.fn(),
        getDom: () => el,
        on: (name, handler) => {
          chart.handlers[name] = handler
        },
      }
      fake.charts.push(chart)
      return chart
    }),
    connect: vi.fn(),
  },
}))
vi.mock('@/api/history', () => ({ historyApi: { query: vi.fn() } }))
vi.mock('@/api/thingModel', () => ({
  thingModelApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(), exportModel: vi.fn(), importModel: vi.fn() },
}))
vi.mock('@/api/devices', () => ({
  deviceApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), resetSecret: vi.fn(), remove: vi.fn(), latest: vi.fn() },
}))

const NOW = Date.UTC(2026, 9, 15, 8, 0, 0)
const HOUR = 3_600_000
const DEFAULT_IDS = ['Ua', 'Ub', 'Zz']

function prop(identifier: string, dataType: DataType, name: string): PropertyView {
  return {
    identifier,
    name,
    dataType,
    unit: null,
    minValue: null,
    maxValue: null,
    decimals: null,
    enumItems: null,
    description: null,
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
  }
}

function point(identifier: string, value: number): PointView {
  return { identifier, name: null, value, ts: NOW, unit: null, decimals: null, enumLabel: null }
}

function ua(points: HistoryPoint[] = [[NOW - 1000, 220.1]], mode: SeriesView['mode'] = 'RAW'): SeriesView {
  return { identifier: 'Ua', name: 'A 相电压', dataType: 'DOUBLE', unit: 'V', decimals: 1, enumItems: null, mode, points }
}

function view(patch: Partial<HistoryView> = {}): HistoryView {
  return { deviceKey: 'dk1', from: NOW - HOUR, to: NOW, bucketMs: null, series: [ua()], ...patch }
}

async function mountPanel() {
  const wrapper = mount(HistoryPanel, {
    props: { deviceKey: 'dk1', productKey: 'pk1' },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
  await settle()
  return wrapper
}

function button(wrapper: Awaited<ReturnType<typeof mountPanel>>, text: string) {
  const found = wrapper.findAll('button').find((b) => b.text() === text)
  if (!found) {
    throw new Error(`button ${text} not found`)
  }
  return found
}

describe('HistoryPanel', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] })
    vi.setSystemTime(NOW)
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false })
    fake.charts.length = 0
    vi.clearAllMocks()
    vi.mocked(thingModelApi.list).mockResolvedValue([
      prop('Ua', 'DOUBLE', 'A 相电压'),
      prop('Ub', 'DOUBLE', 'B 相电压'),
      prop('st', 'ENUM', '运行状态'),
      prop('note', 'TEXT', '备注'),
    ])
    vi.mocked(deviceApi.latest).mockResolvedValue({ deviceKey: 'dk1', values: [point('Zz', 3)] })
    vi.mocked(historyApi.query).mockResolvedValue(view())
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('loads the last hour of the first numeric properties and draws linked charts', async () => {
    const wrapper = await mountPanel()

    expect(historyApi.query).toHaveBeenCalledTimes(1)
    expect(historyApi.query).toHaveBeenCalledWith('dk1', DEFAULT_IDS, NOW - HOUR, NOW)
    expect(wrapper.text()).toContain('原始数据')
    expect(wrapper.text()).toContain('A 相电压（Ua）')
    expect(fake.charts).toHaveLength(1)
    expect(fake.charts[0].setOption).toHaveBeenCalled()
    expect(fake.charts[0].group).toBe('history-dk1')
    expect(echarts.connect).toHaveBeenCalledWith('history-dk1')
    wrapper.unmount()
  })

  it('refreshes a quick range every 30 seconds', async () => {
    const wrapper = await mountPanel()

    await vi.advanceTimersByTimeAsync(30_000)

    expect(historyApi.query).toHaveBeenCalledTimes(2)
    expect(historyApi.query).toHaveBeenLastCalledWith('dk1', DEFAULT_IDS, NOW + 30_000 - HOUR, NOW + 30_000)
    wrapper.unmount()
  })

  it('re-queries the selected range on zoom and pauses auto refresh', async () => {
    const wrapper = await mountPanel()

    fake.charts[0].handlers.datazoom({ batch: [{ startValue: NOW - 600_000, endValue: NOW - 300_000 }] })
    await settle()

    expect(historyApi.query).toHaveBeenLastCalledWith('dk1', DEFAULT_IDS, NOW - 600_000, NOW - 300_000)
    const calls = vi.mocked(historyApi.query).mock.calls.length
    await vi.advanceTimersByTimeAsync(90_000)
    expect(historyApi.query).toHaveBeenCalledTimes(calls)
    expect(wrapper.text()).toContain('已缩放，自动刷新已暂停')
    wrapper.unmount()
  })

  it('ignores repeated zoom events from connected charts', async () => {
    const wrapper = await mountPanel()
    const event = { batch: [{ startValue: NOW - 600_000, endValue: NOW - 300_000 }] }

    fake.charts[0].handlers.datazoom(event)
    fake.charts[0].handlers.datazoom(event)
    await settle()

    expect(historyApi.query).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('widens a zoom shorter than one minute', async () => {
    const wrapper = await mountPanel()

    fake.charts[0].handlers.datazoom({ batch: [{ startValue: NOW - 100_000, endValue: NOW - 90_000 }] })
    await settle()

    expect(historyApi.query).toHaveBeenLastCalledWith('dk1', DEFAULT_IDS, NOW - 100_000, NOW - 40_000)
    wrapper.unmount()
  })

  it('reset zoom restores the quick range and resumes auto refresh', async () => {
    const wrapper = await mountPanel()
    fake.charts[0].handlers.datazoom({ batch: [{ startValue: NOW - 600_000, endValue: NOW - 300_000 }] })
    await settle()
    await vi.advanceTimersByTimeAsync(10_000)

    await button(wrapper, '重置缩放').trigger('click')
    await settle()

    const now = Date.now()
    expect(historyApi.query).toHaveBeenLastCalledWith('dk1', DEFAULT_IDS, now - HOUR, now)
    const calls = vi.mocked(historyApi.query).mock.calls.length
    await vi.advanceTimersByTimeAsync(30_000)
    expect(historyApi.query).toHaveBeenCalledTimes(calls + 1)
    expect(wrapper.text()).toContain('每 30 秒自动刷新')
    wrapper.unmount()
  })

  it('drops a response that arrives after a newer one', async () => {
    let resolveFirst: (v: HistoryView) => void = () => {}
    vi.mocked(historyApi.query)
      .mockImplementationOnce(() => new Promise<HistoryView>((resolve) => (resolveFirst = resolve)))
      .mockResolvedValueOnce(view({ bucketMs: 60_000, series: [ua([[NOW - HOUR, 220, 219, 221]], 'AGG')] }))
    const wrapper = await mountPanel()

    await button(wrapper, '刷新').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('已按 1 分钟聚合（平均/最小/最大）')

    resolveFirst(view())
    await settle()
    expect(wrapper.text()).toContain('已按 1 分钟聚合（平均/最小/最大）')
    expect(wrapper.text()).not.toContain('原始数据')
    wrapper.unmount()
  })

  it('shows the aggregation bucket', async () => {
    vi.mocked(historyApi.query).mockResolvedValue(
      view({ bucketMs: 300_000, series: [ua([[NOW - HOUR, 220, 219, 221]], 'AGG')] }),
    )
    const wrapper = await mountPanel()

    expect(wrapper.text()).toContain('已按 5 分钟聚合（平均/最小/最大）')
    wrapper.unmount()
  })

  it('shows an empty state instead of a chart when a series has no points', async () => {
    vi.mocked(historyApi.query).mockResolvedValue(view({ series: [ua([])] }))
    const wrapper = await mountPanel()

    expect(wrapper.text()).toContain('该时段没有数据')
    expect(fake.charts).toHaveLength(0)
    wrapper.unmount()
  })

  it('stops polling and disposes charts on unmount', async () => {
    const wrapper = await mountPanel()
    const chart = fake.charts[0]

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(90_000)

    expect(historyApi.query).toHaveBeenCalledTimes(1)
    expect(chart.dispose).toHaveBeenCalled()
  })
})
