import type { LocationQuery, LocationQueryValue } from 'vue-router'

export type ProductTab = 'basic' | 'model' | 'devices'

const TABS: readonly ProductTab[] = ['basic', 'model', 'devices']
const DEFAULT_TAB: ProductTab = 'basic'

/** 把 ?tab= 解析为产品详情页的标签，未知值回到「基本信息」 */
export function tabFromQuery(value: LocationQueryValue | LocationQueryValue[] | undefined): ProductTab {
  const first = Array.isArray(value) ? value[0] : value
  return TABS.includes(first as ProductTab) ? (first as ProductTab) : DEFAULT_TAB
}

/** 生成切换标签后的 query，保留其他参数；默认标签不写入地址栏 */
export function tabQuery(current: LocationQuery, tab: ProductTab): LocationQuery {
  const next: LocationQuery = { ...current }
  delete next.tab
  if (tab !== DEFAULT_TAB) {
    next.tab = tab
  }
  return next
}
