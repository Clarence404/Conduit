/** useTheme 的单元测试：偏好与系统设置的合成、跟随系统实时切换、手动选择写入 <html> 的 dark 类、无 matchMedia 环境 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { nextTick, ref } from 'vue'
import type { ThemePref } from '@/stores/ui'
import { applyTheme, chartTheme, installTheme, resolveDark, useTheme } from './useTheme'

/** 安装一个可控的 matchMedia：matches 可改，fire() 触发 change 监听 */
function fakeMedia(matches: boolean) {
  const listeners = new Set<() => void>()
  const mql = {
    matches,
    addEventListener: (_type: string, listener: () => void) => listeners.add(listener),
    removeEventListener: (_type: string, listener: () => void) => listeners.delete(listener),
  }
  window.matchMedia = vi.fn(() => mql as unknown as MediaQueryList)
  return { mql, listeners, fire: () => listeners.forEach((l) => l()) }
}

const isDarkClass = () => document.documentElement.classList.contains('dark')

describe('useTheme', () => {
  afterEach(() => {
    applyTheme(false)
    Reflect.deleteProperty(window, 'matchMedia')
  })

  it('resolves the preference against the system setting', () => {
    expect(resolveDark('system', true)).toBe(true)
    expect(resolveDark('system', false)).toBe(false)
    expect(resolveDark('dark', false)).toBe(true)
    expect(resolveDark('light', true)).toBe(false)
  })

  it('follows the system setting while the preference is system', () => {
    const media = fakeMedia(true)

    const stop = installTheme(() => 'system')
    expect(isDarkClass()).toBe(true)
    expect(useTheme().isDark.value).toBe(true)

    media.mql.matches = false
    media.fire()
    expect(isDarkClass()).toBe(false)
    expect(useTheme().isDark.value).toBe(false)

    stop()
    expect(media.listeners.size).toBe(0)
  })

  it('applies a manual choice and ignores system changes', async () => {
    const media = fakeMedia(true)
    const pref = ref<ThemePref>('light')

    const stop = installTheme(() => pref.value)
    expect(isDarkClass()).toBe(false)

    pref.value = 'dark'
    await nextTick()
    expect(isDarkClass()).toBe(true)

    media.mql.matches = false
    media.fire()
    expect(isDarkClass()).toBe(true)
    stop()
  })

  it('treats a missing matchMedia as a light system', () => {
    const stop = installTheme(() => 'system')

    expect(isDarkClass()).toBe(false)
    stop()
  })

  it('maps the theme to the ECharts theme name', () => {
    expect(chartTheme(true)).toBe('dark')
    expect(chartTheme(false)).toBeUndefined()
  })
})
