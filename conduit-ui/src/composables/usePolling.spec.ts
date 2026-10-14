import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { startPolling } from './usePolling'

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
