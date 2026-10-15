/**
 * 设备详情页与路由 query 的互相转换：?tab= 为标签（最新值 latest，默认；历史 history），
 * ?props= 为进入历史标签时初始选中的测点（逗号分隔，由点击指标卡片写入）。
 */
import type { LocationQuery, LocationQueryValue } from 'vue-router'

export type DeviceTab = 'latest' | 'history'

const TABS: readonly DeviceTab[] = ['latest', 'history']
const DEFAULT_TAB: DeviceTab = 'latest'

/** 把 ?tab= 解析为设备详情页的标签；重复参数取第一个，未知值回到「最新值」 */
export function deviceTabFromQuery(value: LocationQueryValue | LocationQueryValue[] | undefined): DeviceTab {
  const first = Array.isArray(value) ? value[0] : value
  return TABS.includes(first as DeviceTab) ? (first as DeviceTab) : DEFAULT_TAB
}

/**
 * 生成切换标签后的 query，保留其他参数；默认标签不写入地址栏。
 * 切到历史以外的标签时去掉 props：它只决定进入历史标签那一次的初始选中。
 */
export function deviceTabQuery(current: LocationQuery, tab: DeviceTab): LocationQuery {
  const next: LocationQuery = { ...current }
  delete next.tab
  if (tab !== DEFAULT_TAB) {
    next.tab = tab
  }
  if (tab !== 'history') {
    delete next.props
  }
  return next
}

/** 点击指标卡片后的 query：切到历史标签并只选中该测点，保留其他参数 */
export function historyQuery(current: LocationQuery, identifier: string): LocationQuery {
  return { ...current, tab: 'history', props: identifier }
}

/** 解析 ?props=：逗号分隔，去掉空白与空项并去重；重复参数取第一个；没有时为空数组 */
export function propsFromQuery(value: LocationQueryValue | LocationQueryValue[] | undefined): string[] {
  const first = Array.isArray(value) ? value[0] : value
  if (!first) {
    return []
  }
  return [...new Set(first.split(',').map((s) => s.trim()).filter(Boolean))]
}
