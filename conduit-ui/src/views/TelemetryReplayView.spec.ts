/**
 * TelemetryReplayView 组件测试：从未提交任务时的空状态、跨度超过 6 小时时前端拦截、
 * 运行中轮询并显示取消按钮、任务结束后停止轮询、离开页面清除定时器。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import TelemetryReplayView from './TelemetryReplayView.vue'
import { replayApi } from '@/api/replay'
import type { ReplayStatus } from '@/api/types'
import { createTestPinia, createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/replay', () => ({
  replayApi: { status: vi.fn(), start: vi.fn(), cancel: vi.fn() },
}))
vi.mock('@/api/auth', () => ({ authApi: { login: vi.fn(), refresh: vi.fn(), logout: vi.fn(), exitTenant: vi.fn() } }))
vi.mock('@/api/me', () => ({ meApi: { update: vi.fn(), changePassword: vi.fn() } }))
// 确认框直接确认
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return { ...actual, ElMessageBox: { ...actual.ElMessageBox, confirm: vi.fn().mockResolvedValue('confirm') } }
})

const START = 1760400000000

function status(patch: Partial<ReplayStatus> = {}): ReplayStatus {
  return {
    state: 'RUNNING',
    from: START,
    to: START + 3600_000,
    startedAt: START,
    scanned: 10,
    written: 9,
    skipped: 1,
    rejected: 0,
    ...patch,
  }
}

type Vm = {
  range: [Date, Date] | null
  start(): Promise<void>
  cancel(): Promise<void>
}

async function mountView() {
  const pinia = createTestPinia({ username: 'sysadmin', role: 'PLATFORM_ADMIN', tenantCode: null, tenantName: null })
  const router = createTestRouter()
  await router.push('/telemetry-replay')
  await router.isReady()
  const wrapper = mount(TelemetryReplayView, {
    global: { plugins: [pinia, router, ElementPlus] },
    attachTo: document.body,
  })
  await settle()
  return { wrapper, vm: wrapper.vm as unknown as Vm }
}

describe('TelemetryReplayView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
    vi.mocked(replayApi.status).mockResolvedValue({ state: 'NONE' })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows an empty state when no replay has ever run', async () => {
    const { wrapper } = await mountView()

    expect(wrapper.find('.el-empty').exists()).toBe(true)
    expect(wrapper.find('[data-test="cancel"]').exists()).toBe(false)
  })

  it('refuses a range longer than six hours without calling the backend', async () => {
    const { vm } = await mountView()
    vm.range = [new Date(START), new Date(START + 6 * 3600_000 + 60_000)]

    await vm.start()

    expect(replayApi.start).not.toHaveBeenCalled()
  })

  it('submits the chosen range and keeps polling while the task runs', async () => {
    vi.mocked(replayApi.start).mockResolvedValue(status())
    const { vm, wrapper } = await mountView()
    // 从这一刻起状态查询都返回「进行中」，轮询应持续
    vi.mocked(replayApi.status).mockResolvedValue(status())
    vm.range = [new Date(START), new Date(START + 3600_000)]

    await vm.start()
    await settle()

    expect(replayApi.start).toHaveBeenCalledWith({ from: START, to: START + 3600_000 })
    expect(wrapper.find('[data-test="cancel"]').exists()).toBe(true)

    const before = vi.mocked(replayApi.status).mock.calls.length
    await vi.advanceTimersByTimeAsync(2100)

    expect(vi.mocked(replayApi.status).mock.calls.length).toBeGreaterThan(before)
  })

  it('stops polling once the task has finished', async () => {
    vi.mocked(replayApi.start).mockResolvedValue(status())
    const { vm, wrapper } = await mountView()
    // 提交后的第一次轮询就报告已完成，轮询应立即停止
    vi.mocked(replayApi.status).mockResolvedValue(status({ state: 'SUCCEEDED', finishedAt: START + 5000 }))
    vm.range = [new Date(START), new Date(START + 3600_000)]
    await vm.start()
    await settle()

    const callsAfterFinish = vi.mocked(replayApi.status).mock.calls.length
    await vi.advanceTimersByTimeAsync(6000)

    expect(vi.mocked(replayApi.status).mock.calls.length).toBe(callsAfterFinish)
    expect(wrapper.find('[data-test="cancel"]').exists()).toBe(false)
  })

  it('clears the timer when the page is left', async () => {
    vi.mocked(replayApi.start).mockResolvedValue(status())
    const { vm, wrapper } = await mountView()
    vi.mocked(replayApi.status).mockResolvedValue(status())
    vm.range = [new Date(START), new Date(START + 3600_000)]
    await vm.start()
    await settle()

    wrapper.unmount()
    const callsAtUnmount = vi.mocked(replayApi.status).mock.calls.length
    await vi.advanceTimersByTimeAsync(6000)

    expect(vi.mocked(replayApi.status).mock.calls.length).toBe(callsAtUnmount)
  })

  it('cancels the running task', async () => {
    vi.mocked(replayApi.start).mockResolvedValue(status())
    vi.mocked(replayApi.cancel).mockResolvedValue(status({ state: 'CANCELLED', finishedAt: START + 5000 }))
    const { vm, wrapper } = await mountView()
    vi.mocked(replayApi.status).mockResolvedValue(status())
    vm.range = [new Date(START), new Date(START + 3600_000)]
    await vm.start()
    await settle()

    await vm.cancel()
    await settle()

    expect(replayApi.cancel).toHaveBeenCalled()
    expect(wrapper.find('[data-test="cancel"]').exists()).toBe(false)
  })
})
