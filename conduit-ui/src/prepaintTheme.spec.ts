/**
 * index.html 首屏前主题脚本的测试：把页面里的内联脚本取出来在 jsdom 中执行，
 * 校验它与 parsePrefs / resolveDark 的规则一致（light/dark/system、无效值按 system、读取失败不抛错、不支持 matchMedia 视为浅色）。
 */
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { parsePrefs, UI_STORAGE_KEY } from '@/stores/ui'
import { resolveDark } from '@/composables/useTheme'

const html = readFileSync(resolve(__dirname, '../index.html'), 'utf-8')
const script = /<script>([\s\S]*?)<\/script>/.exec(html)?.[1]

/** 在当前 jsdom 页面上执行首屏前脚本，返回 <html> 是否带 dark 类 */
function runPrepaint(): boolean {
  document.documentElement.classList.remove('dark')
  new Function(script!)()
  return document.documentElement.classList.contains('dark')
}

/** 假装系统偏好深色或浅色；传 null 表示环境没有 matchMedia */
function stubSystemDark(systemDark: boolean | null) {
  window.matchMedia = (systemDark === null ? undefined : () => ({ matches: systemDark })) as unknown as typeof window.matchMedia
}

describe('index.html pre-paint theme script', () => {
  const originalMatchMedia = window.matchMedia

  beforeEach(() => {
    localStorage.clear()
    stubSystemDark(false)
  })

  afterEach(() => {
    window.matchMedia = originalMatchMedia
    document.documentElement.classList.remove('dark')
    vi.restoreAllMocks()
  })

  it('exists as an inline script before the app bundle', () => {
    expect(script).toBeDefined()
    expect(html.indexOf('<script>')).toBeLessThan(html.indexOf('src="/src/main.ts"'))
    expect(script).toContain(UI_STORAGE_KEY)
  })

  it.each([
    ['dark', false, true],
    ['light', true, false],
    ['system', true, true],
    ['system', false, false],
    ['purple', true, true],
    ['purple', false, false],
  ])('theme %s with system dark=%s gives dark=%s, like parsePrefs + resolveDark', (theme, systemDark, expected) => {
    localStorage.setItem(UI_STORAGE_KEY, JSON.stringify({ theme }))
    stubSystemDark(systemDark)

    expect(runPrepaint()).toBe(expected)
    expect(resolveDark(parsePrefs(localStorage.getItem(UI_STORAGE_KEY)).theme, systemDark)).toBe(expected)
  })

  it.each([null, '', 'not json', '[]', 'null'])('falls back to following the system for stored value %s', (raw) => {
    if (raw !== null) localStorage.setItem(UI_STORAGE_KEY, raw)
    stubSystemDark(true)

    expect(runPrepaint()).toBe(true)
  })

  it('stays light without matchMedia and does not throw when localStorage fails', () => {
    stubSystemDark(null)
    expect(runPrepaint()).toBe(false)

    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied')
    })
    expect(() => runPrepaint()).not.toThrow()
  })
})
