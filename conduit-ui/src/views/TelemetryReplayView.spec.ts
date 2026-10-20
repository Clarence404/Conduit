/**
 * TelemetryReplayView 组件测试：从未提交任务时的空状态、6 小时跨度边界与各校验提示、
 * 运行中轮询并显示取消按钮、取消后的「正在取消」过渡、任务结束后停止轮询、离开页面清除定时器、
 * 409 后纠正界面、连续轮询失败提示。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
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

  const HOUR6 = 6 * 3600_000

  it('shows an empty state when no replay has ever run', async () => {
    const { wrapper } = await mountView()

    expect(wrapper.find('.el-empty').exists()).toBe(true)
    expect(wrapper.find('[data-test="cancel"]').exists()).toBe(false)
  })

  it('shows a retry affordance instead of the empty state when the first status load fails', async () => {
    vi.mocked(replayApi.status).mockRejectedValueOnce(new Error('boom'))
    const { wrapper } = await mountView()

    expect(wrapper.find('[data-test="load-failed"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('还没有重放过数据')

    vi.mocked(replayApi.status).mockResolvedValue(status())
    await wrapper.find('[data-test="load-failed"] button').trigger('click')
    await settle()

    expect(wrapper.find('[data-test="load-failed"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="cancel"]').exists()).toBe(true)
  })

  it('accepts a range of exactly six hours', async () => {
    vi.mocked(replayApi.start).mockResolvedValue(status())
    const { vm } = await mountView()
    vm.range = [new Date(START), new Date(START + HOUR6)]

    await vm.start()

    expect(replayApi.start).toHaveBeenCalledWith({ from: START, to: START + HOUR6 })
  })

  it('refuses six hours plus one millisecond and tells the user', async () => {
    const warn = vi.spyOn(ElMessage, 'warning')
    const { vm } = await mountView()
    vm.range = [new Date(START), new Date(START + HOUR6 + 1)]

    await vm.start()

    expect(replayApi.start).not.toHaveBeenCalled()
    expect(warn).toHaveBeenCalledWith('时间跨度不能超过 6 小时')
  })

  it('asks for a range when none is chosen', async () => {
    const warn = vi.spyOn(ElMessage, 'warning')
    const { vm } = await mountView()
    vm.range = null

    await vm.start()

    expect(replayApi.start).not.toHaveBeenCalled()
    expect(warn).toHaveBeenCalledWith('请选择时间范围')
  })

  it('refuses a range whose start is not before its end', async () => {
    const warn = vi.spyOn(ElMessage, 'warning')
    const { vm } = await mountView()

    vm.range = [new Date(START), new Date(START)]
    await vm.start()
    vm.range = [new Date(START + 1000), new Date(START)]
    await vm.start()

    expect(replayApi.start).not.toHaveBeenCalled()
    expect(warn).toHaveBeenCalledTimes(2)
    expect(warn).toHaveBeenCalledWith('开始时间必须早于结束时间')
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

  it('shows a disabled cancelling button while the task keeps running, then settles', async () => {
    vi.mocked(replayApi.start).mockResolvedValue(status())
    vi.mocked(replayApi.cancel).mockResolvedValue(status())
    const { vm, wrapper } = await mountView()
    vi.mocked(replayApi.status).mockResolvedValue(status())
    vm.range = [new Date(START), new Date(START + 3600_000)]
    await vm.start()
    await settle()

    await vm.cancel()
    await settle()

    const button = wrapper.find('[data-test="cancel"]')
    expect(button.exists()).toBe(true)
    expect(button.attributes('disabled')).toBeDefined()
    expect(button.text()).toBe('正在取消…')

    // 取消尚未生效时轮询继续
    const before = vi.mocked(replayApi.status).mock.calls.length
    await vi.advanceTimersByTimeAsync(2100)
    expect(vi.mocked(replayApi.status).mock.calls.length).toBeGreaterThan(before)

    // 任务真正停下后按钮消失，轮询停止
    vi.mocked(replayApi.status).mockResolvedValue(status({ state: 'CANCELLED', finishedAt: START + 5000 }))
    await vi.advanceTimersByTimeAsync(2100)
    await settle()
    expect(wrapper.find('[data-test="cancel"]').exists()).toBe(false)
    const after = vi.mocked(replayApi.status).mock.calls.length
    await vi.advanceTimersByTimeAsync(6000)
    expect(vi.mocked(replayApi.status).mock.calls.length).toBe(after)
  })

  it('reloads the status after start is rejected because a task is already running', async () => {
    vi.mocked(replayApi.start).mockRejectedValue(new Error('conflict'))
    const { vm, wrapper } = await mountView()
    vi.mocked(replayApi.status).mockResolvedValue(status())
    vm.range = [new Date(START), new Date(START + 3600_000)]

    await vm.start()
    await settle()

    expect(wrapper.find('[data-test="cancel"]').exists()).toBe(true)
  })

  it('reloads the status after cancel is rejected', async () => {
    vi.mocked(replayApi.start).mockResolvedValue(status())
    vi.mocked(replayApi.cancel).mockRejectedValue(new Error('conflict'))
    const { vm, wrapper } = await mountView()
    vi.mocked(replayApi.status).mockResolvedValue(status())
    vm.range = [new Date(START), new Date(START + 3600_000)]
    await vm.start()
    await settle()
    // 任务其实已经结束
    vi.mocked(replayApi.status).mockResolvedValue(status({ state: 'SUCCEEDED', finishedAt: START + 5000 }))

    await vm.cancel()
    await settle()

    expect(wrapper.find('[data-test="cancel"]').exists()).toBe(false)
  })

  it('shows a refresh-failed hint after repeated poll failures and clears it on recovery', async () => {
    vi.mocked(replayApi.start).mockResolvedValue(status())
    const { vm, wrapper } = await mountView()
    vi.mocked(replayApi.status).mockResolvedValue(status())
    vm.range = [new Date(START), new Date(START + 3600_000)]
    await vm.start()
    await settle()
    expect(wrapper.find('[data-test="poll-failed"]').exists()).toBe(false)

    vi.mocked(replayApi.status).mockRejectedValue(new Error('network'))
    await vi.advanceTimersByTimeAsync(6100)
    await settle()
    expect(wrapper.find('[data-test="poll-failed"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="poll-failed"]').text()).toContain('状态刷新失败')

    vi.mocked(replayApi.status).mockResolvedValue(status())
    await vi.advanceTimersByTimeAsync(2100)
    await settle()
    expect(wrapper.find('[data-test="poll-failed"]').exists()).toBe(false)
  })
})
