/** 设备详情页的标签与路由 query ?tab= 的互相转换：最新值 latest（默认）/ 历史 history */
import type { LocationQuery, LocationQueryValue } from 'vue-router'

export type DeviceTab = 'latest' | 'history'

const TABS: readonly DeviceTab[] = ['latest', 'history']
const DEFAULT_TAB: DeviceTab = 'latest'

/** 把 ?tab= 解析为设备详情页的标签；重复参数取第一个，未知值回到「最新值」 */
export function deviceTabFromQuery(value: LocationQueryValue | LocationQueryValue[] | undefined): DeviceTab {
  const first = Array.isArray(value) ? value[0] : value
  return TABS.includes(first as DeviceTab) ? (first as DeviceTab) : DEFAULT_TAB
}

/** 生成切换标签后的 query，保留其他参数；默认标签不写入地址栏 */
export function deviceTabQuery(current: LocationQuery, tab: DeviceTab): LocationQuery {
  const next: LocationQuery = { ...current }
  delete next.tab
  if (tab !== DEFAULT_TAB) {
    next.tab = tab
  }
  return next
}
