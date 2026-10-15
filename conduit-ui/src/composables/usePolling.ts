/** 轮询工具：startPolling 是与框架无关的核心，usePolling 把它绑定到组件的挂载/卸载生命周期。 */

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
