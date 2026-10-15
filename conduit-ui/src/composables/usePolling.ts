/** 轮询工具：startPolling 是与框架无关的核心，usePolling 把它绑定到组件的挂载/卸载生命周期，createVisiblePoller 提供可启停且页面隐藏时暂停的轮询。 */

import { onBeforeUnmount, onMounted } from 'vue'

/**
 * 立即执行一次，之后每次执行结束后等待 intervalMs 再执行（串行，不会重叠）；任务抛错被吞掉，不影响后续轮询。
 * @returns 停止函数：调用后不再安排新一轮（已在执行的这一轮会跑完）
 */
export function startPolling(task: () => unknown, intervalMs: number): () => void {
  let stopped = false
  let timer: ReturnType<typeof setTimeout> | undefined

  const tick = async () => {
    if (stopped) {
      return
    }
    try {
      await task()
    } catch {
      // 下一轮再试
    } finally {
      if (!stopped) {
        timer = setTimeout(tick, intervalMs)
      }
    }
  }

  void tick()
  return () => {
    stopped = true
    if (timer) {
      clearTimeout(timer)
    }
  }
}

/**
 * 组件内轮询：挂载后开始，卸载时自动停止。
 * @param task 每轮执行的任务，可返回 Promise
 * @param intervalMs 两轮之间的等待毫秒数（从上一轮结束后算起，不会并发重叠）
 */
export function usePolling(task: () => unknown, intervalMs: number) {
  let stop: () => void = () => {}
  onMounted(() => {
    stop = startPolling(task, intervalMs)
  })
  onBeforeUnmount(() => stop())
}

/** 可手动启停的轮询 */
export interface VisiblePoller {
  /** 开始（或重新开始）：页面可见时立即执行一次，之后按间隔轮询 */
  start(): void
  /** 停止，页面重新可见也不恢复 */
  stop(): void
  /** 停止并移除页面可见性监听；组件卸载时调用 */
  dispose(): void
  isActive(): boolean
}

/**
 * 创建页面隐藏时自动暂停的轮询（历史曲线自动刷新用）。处于启动状态时：页面隐藏即暂停，重新可见时立即执行一次并恢复轮询。
 * 与 usePolling 不同，它不随组件挂载自动开始，由调用方按需 start/stop。
 * @param task 每次执行的任务（失败不影响后续轮询）
 * @param intervalMs 上一次执行结束到下一次开始的间隔
 */
export function createVisiblePoller(task: () => unknown, intervalMs: number): VisiblePoller {
  let active = false
  let stopTimer: (() => void) | null = null

  const halt = () => {
    stopTimer?.()
    stopTimer = null
  }
  const run = () => {
    halt()
    stopTimer = startPolling(task, intervalMs)
  }
  const onVisibility = () => {
    if (!active) {
      return
    }
    if (document.hidden) {
      halt()
    } else {
      run()
    }
  }
  document.addEventListener('visibilitychange', onVisibility)

  return {
    start() {
      active = true
      if (document.hidden) {
        halt()
      } else {
        run()
      }
    },
    stop() {
      active = false
      halt()
    },
    dispose() {
      active = false
      halt()
      document.removeEventListener('visibilitychange', onVisibility)
    },
    isActive: () => active,
  }
}
