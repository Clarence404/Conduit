/**
 * 外观偏好（Pinia）：主题（浅色/深色/跟随系统）、侧栏风格（浅色/深色）、侧栏折叠、设备最新值视图（卡片/表格）。
 * 存在 localStorage 键 conduit.ui；读写失败（隐私模式、被禁用、配额满）时使用默认值，不报错。
 */
import { defineStore } from 'pinia'

export type ThemePref = 'light' | 'dark' | 'system'
export type SidebarStyle = 'light' | 'dark'
export type LatestView = 'cards' | 'table'

/** 全部外观偏好 */
export interface UiPrefs {
  theme: ThemePref
  sidebarStyle: SidebarStyle
  sidebarCollapsed: boolean
  latestView: LatestView
}

export const UI_STORAGE_KEY = 'conduit.ui'

/** 默认偏好：跟随系统、深色侧栏、侧栏展开、卡片视图 */
export const DEFAULT_PREFS: Readonly<UiPrefs> = Object.freeze({
  theme: 'system',
  sidebarStyle: 'dark',
  sidebarCollapsed: false,
  latestView: 'cards',
})

const THEMES: readonly ThemePref[] = ['light', 'dark', 'system']
const SIDEBAR_STYLES: readonly SidebarStyle[] = ['light', 'dark']
const LATEST_VIEWS: readonly LatestView[] = ['cards', 'table']

/** 值在允许范围内则原样返回，否则返回默认值 */
function pick<T extends string>(value: unknown, allowed: readonly T[], fallback: T): T {
  return allowed.includes(value as T) ? (value as T) : fallback
}

/**
 * 解析 localStorage 中的 JSON：整体无法解析或不是对象时返回默认值；逐字段校验，无效字段取默认值，不影响其他字段。
 * @param raw localStorage 原始值；没有存过时为 null
 */
export function parsePrefs(raw: string | null): UiPrefs {
  let data: Record<string, unknown> = {}
  if (raw) {
    try {
      const parsed: unknown = JSON.parse(raw)
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        data = parsed as Record<string, unknown>
      }
    } catch {
      // 无法解析时用默认值
    }
  }
  return {
    theme: pick(data.theme, THEMES, DEFAULT_PREFS.theme),
    sidebarStyle: pick(data.sidebarStyle, SIDEBAR_STYLES, DEFAULT_PREFS.sidebarStyle),
    sidebarCollapsed: typeof data.sidebarCollapsed === 'boolean' ? data.sidebarCollapsed : DEFAULT_PREFS.sidebarCollapsed,
    latestView: pick(data.latestView, LATEST_VIEWS, DEFAULT_PREFS.latestView),
  }
}

/** 读取偏好；localStorage 不可用或读取抛错时返回默认值 */
export function loadPrefs(): UiPrefs {
  try {
    return parsePrefs(window.localStorage.getItem(UI_STORAGE_KEY))
  } catch {
    return { ...DEFAULT_PREFS }
  }
}

/** 保存偏好；写入失败时静默忽略（本次会话内偏好仍然生效） */
export function savePrefs(prefs: UiPrefs): void {
  try {
    window.localStorage.setItem(UI_STORAGE_KEY, JSON.stringify(prefs))
  } catch {
    // 忽略
  }
}

export const useUiStore = defineStore('ui', {
  state: (): UiPrefs => loadPrefs(),
  actions: {
    /** 设置主题偏好并保存 */
    setTheme(theme: ThemePref) {
      this.theme = theme
      this.persist()
    },
    /** 设置侧栏风格并保存 */
    setSidebarStyle(style: SidebarStyle) {
      this.sidebarStyle = style
      this.persist()
    },
    /** 设置侧栏是否折叠并保存 */
    setSidebarCollapsed(collapsed: boolean) {
      this.sidebarCollapsed = collapsed
      this.persist()
    },
    /** 切换侧栏折叠状态并保存 */
    toggleSidebar() {
      this.setSidebarCollapsed(!this.sidebarCollapsed)
    },
    /** 设置设备最新值视图（卡片/表格）并保存 */
    setLatestView(view: LatestView) {
      this.latestView = view
      this.persist()
    },
    /** 把当前全部偏好写入 localStorage */
    persist() {
      savePrefs({
        theme: this.theme,
        sidebarStyle: this.sidebarStyle,
        sidebarCollapsed: this.sidebarCollapsed,
        latestView: this.latestView,
      })
    },
  },
})
