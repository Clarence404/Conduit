/** ui store 的单元测试：默认值、读取已存偏好、每次修改写入 conduit.ui、读写 localStorage 失败时的容错 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { DEFAULT_PREFS, parsePrefs, UI_STORAGE_KEY, useUiStore } from './ui'

describe('parsePrefs', () => {
  it.each([null, '', 'not json', '[]', '42', 'null'])('returns the defaults for %s', (raw) => {
    expect(parsePrefs(raw)).toEqual(DEFAULT_PREFS)
  })

  it('keeps valid fields and replaces invalid ones with defaults', () => {
    const raw = JSON.stringify({ theme: 'dark', sidebarStyle: 'purple', sidebarCollapsed: 'yes', latestView: 'table' })

    expect(parsePrefs(raw)).toEqual({ theme: 'dark', sidebarStyle: 'dark', sidebarCollapsed: false, latestView: 'table' })
  })
})

describe('useUiStore', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('starts with system theme, dark sidebar, expanded menu and the card view', () => {
    const ui = useUiStore()

    expect(ui.theme).toBe('system')
    expect(ui.sidebarStyle).toBe('dark')
    expect(ui.sidebarCollapsed).toBe(false)
    expect(ui.latestView).toBe('cards')
  })

  it('loads saved preferences', () => {
    localStorage.setItem(UI_STORAGE_KEY, JSON.stringify({ theme: 'light', sidebarStyle: 'light', sidebarCollapsed: true, latestView: 'table' }))

    const ui = useUiStore()

    expect(ui.theme).toBe('light')
    expect(ui.sidebarStyle).toBe('light')
    expect(ui.sidebarCollapsed).toBe(true)
    expect(ui.latestView).toBe('table')
  })

  it('saves every change under conduit.ui', () => {
    const ui = useUiStore()

    ui.setTheme('dark')
    ui.setSidebarStyle('light')
    ui.toggleSidebar()
    ui.setLatestView('table')

    expect(JSON.parse(localStorage.getItem(UI_STORAGE_KEY)!)).toEqual({
      theme: 'dark',
      sidebarStyle: 'light',
      sidebarCollapsed: true,
      latestView: 'table',
    })
  })

  it('falls back to the defaults when localStorage cannot be read', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied')
    })

    expect(useUiStore().$state).toEqual(DEFAULT_PREFS)
  })

  it('keeps working when localStorage cannot be written', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota')
    })
    const ui = useUiStore()

    expect(() => ui.setTheme('dark')).not.toThrow()
    expect(ui.theme).toBe('dark')
  })
})
