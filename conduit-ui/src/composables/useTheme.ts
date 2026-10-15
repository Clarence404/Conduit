/**
 * 主题：按外观偏好（浅色/深色/跟随系统）在 <html> 上切换 Element Plus 的 dark 类，并向图表等组件提供当前是否为深色。
 * 「是否深色」放在模块级 ref 中（不放 Pinia），图表组件读取它时不依赖 store。
 */
import { ref, watch, type Ref } from 'vue'
import type { ThemePref } from '@/stores/ui'

const SYSTEM_DARK_QUERY = '(prefers-color-scheme: dark)'
const dark = ref(false)

/**
 * 合成最终主题：跟随系统时取系统设置，否则取偏好本身。
 * @param pref 主题偏好
 * @param systemDark 系统当前是否为深色
 * 注意：conduit-ui/index.html 的首屏前内联脚本复刻了这条规则，修改时要同步（prepaintTheme.spec.ts 校验两者一致）。
 */
export function resolveDark(pref: ThemePref, systemDark: boolean): boolean {
  return pref === 'system' ? systemDark : pref === 'dark'
}

/** 应用主题：更新共享状态，并在 <html> 上添加或移除 dark 类（Element Plus 暗色变量据此生效） */
export function applyTheme(isDark: boolean): void {
  dark.value = isDark
  document.documentElement.classList.toggle('dark', isDark)
}

/** ECharts 主题名：深色用 ECharts 内置的 dark 主题，浅色用默认主题（undefined） */
export function chartTheme(isDark: boolean): 'dark' | undefined {
  return isDark ? 'dark' : undefined
}

/** 系统深色查询；环境不支持 matchMedia（如测试环境）时为 null，视为浅色 */
function systemQuery(): MediaQueryList | null {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia(SYSTEM_DARK_QUERY)
    : null
}

/**
 * 安装主题：立即按偏好应用一次；之后偏好变化，或系统主题变化（只在跟随系统时影响结果）都会重新应用。
 * 在 main.ts 挂载应用之前调用，保证首屏就是正确的主题。
 * @param pref 返回当前主题偏好的函数（通常读 ui store）
 * @returns 卸载函数：停止监听偏好与系统主题
 */
export function installTheme(pref: () => ThemePref): () => void {
  const media = systemQuery()
  const update = () => applyTheme(resolveDark(pref(), media?.matches ?? false))
  const stopWatch = watch(pref, update, { immediate: true })
  media?.addEventListener('change', update)
  return () => {
    stopWatch()
    media?.removeEventListener('change', update)
  }
}

/** 组件内读取当前是否为深色（只读，可被 watch） */
export function useTheme(): { isDark: Readonly<Ref<boolean>> } {
  return { isDark: dark }
}
