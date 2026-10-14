import { onBeforeUnmount, onMounted } from 'vue'

/** 立即执行一次，之后每次执行结束后等待 intervalMs 再执行；失败不影响后续轮询。 */
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

export function usePolling(task: () => unknown, intervalMs: number) {
  let stop: () => void = () => {}
  onMounted(() => {
    stop = startPolling(task, intervalMs)
  })
  onBeforeUnmount(() => stop())
}
