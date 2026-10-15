/** 响应式媒体查询：返回当前是否匹配，媒体条件变化时更新，组件卸载时移除监听；不支持 matchMedia 的环境恒为 false。 */
import { onBeforeUnmount, ref, type Ref } from 'vue'

/**
 * 在组件 setup 中调用。
 * @param query CSS 媒体查询，例如 '(max-width: 819px)'
 */
export function useMediaQuery(query: string): Readonly<Ref<boolean>> {
  const matches = ref(false)
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return matches
  }
  const mql = window.matchMedia(query)
  matches.value = mql.matches
  const onChange = (event: MediaQueryListEvent) => {
    matches.value = event.matches
  }
  mql.addEventListener('change', onChange)
  onBeforeUnmount(() => mql.removeEventListener('change', onChange))
  return matches
}
