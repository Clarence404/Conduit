/** startPolling 与 createVisiblePoller 的单元测试 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createVisiblePoller, startPolling } from './usePolling'

describe('startPolling', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('runs immediately and then after each interval', async () => {
    const task = vi.fn().mockResolvedValue(undefined)

    const stop = startPolling(task, 5000)
    await vi.advanceTimersByTimeAsync(0)
    expect(task).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(5000)
    expect(task).toHaveBeenCalledTimes(2)

    stop()
    await vi.advanceTimersByTimeAsync(20000)
    expect(task).toHaveBeenCalledTimes(2)
  })

  it('keeps polling after a failure', async () => {
    const task = vi.fn().mockRejectedValueOnce(new Error('down')).mockResolvedValue(undefined)

    const stop = startPolling(task, 1000)
    await vi.advanceTimersByTimeAsync(1000)

    expect(task).toHaveBeenCalledTimes(2)
    stop()
  })

  it('does not overlap slow tasks', async () => {
    let running = 0
    let maxRunning = 0
    const task = vi.fn(async () => {
      running++
      maxRunning = Math.max(maxRunning, running)
      await new Promise((r) => setTimeout(r, 3000))
      running--
    })

    const stop = startPolling(task, 1000)
    await vi.advanceTimersByTimeAsync(10000)

    expect(maxRunning).toBe(1)
    stop()
  })
})

describe('createVisiblePoller', () => {
  let hidden = false

  beforeEach(() => {
    vi.useFakeTimers()
    hidden = false
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  function setHidden(value: boolean) {
    hidden = value
    document.dispatchEvent(new Event('visibilitychange'))
  }

  it('runs on start, polls, and stops on stop()', async () => {
    const task = vi.fn().mockResolvedValue(undefined)
    const poller = createVisiblePoller(task, 1000)

    poller.start()
    await vi.advanceTimersByTimeAsync(0)
    expect(task).toHaveBeenCalledTimes(1)
    expect(poller.isActive()).toBe(true)

    await vi.advanceTimersByTimeAsync(1000)
    expect(task).toHaveBeenCalledTimes(2)

    poller.stop()
    await vi.advanceTimersByTimeAsync(5000)
    expect(task).toHaveBeenCalledTimes(2)
    expect(poller.isActive()).toBe(false)
    poller.dispose()
  })

  it('pauses while hidden and runs immediately when visible again', async () => {
    const task = vi.fn().mockResolvedValue(undefined)
    const poller = createVisiblePoller(task, 1000)
    poller.start()
    await vi.advanceTimersByTimeAsync(0)

    setHidden(true)
    await vi.advanceTimersByTimeAsync(5000)
    expect(task).toHaveBeenCalledTimes(1)

    setHidden(false)
    await vi.advanceTimersByTimeAsync(0)
    expect(task).toHaveBeenCalledTimes(2)
    poller.dispose()
  })

  it('does not resume a stopped poller when the page becomes visible', async () => {
    const task = vi.fn().mockResolvedValue(undefined)
    const poller = createVisiblePoller(task, 1000)
    poller.start()
    await vi.advanceTimersByTimeAsync(0)
    poller.stop()

    setHidden(true)
    setHidden(false)
    await vi.advanceTimersByTimeAsync(5000)

    expect(task).toHaveBeenCalledTimes(1)
    poller.dispose()
  })

  it('waits for visibility when started while hidden', async () => {
    const task = vi.fn().mockResolvedValue(undefined)
    hidden = true
    const poller = createVisiblePoller(task, 1000)

    poller.start()
    await vi.advanceTimersByTimeAsync(3000)
    expect(task).not.toHaveBeenCalled()

    setHidden(false)
    await vi.advanceTimersByTimeAsync(0)
    expect(task).toHaveBeenCalledTimes(1)
    poller.dispose()
  })

  it('stops listening after dispose()', async () => {
    const task = vi.fn().mockResolvedValue(undefined)
    const poller = createVisiblePoller(task, 1000)
    poller.start()
    await vi.advanceTimersByTimeAsync(0)

    poller.dispose()
    setHidden(true)
    setHidden(false)
    await vi.advanceTimersByTimeAsync(5000)

    expect(task).toHaveBeenCalledTimes(1)
  })
})
