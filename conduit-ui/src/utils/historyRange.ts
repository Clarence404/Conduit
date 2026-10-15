/** 历史页的时间范围：快捷范围、框选缩放范围、自定义范围校验与聚合桶文案 */

/** 半开区间 [from, to)，毫秒 */
export interface TimeRange {
  from: number
  to: number
}

export type QuickRangeKey = '1h' | '6h' | '24h' | '7d' | '30d'

const HOUR = 3_600_000
const DAY = 24 * HOUR

/** 快捷范围，顺序即按钮顺序 */
export const QUICK_RANGES: readonly { key: QuickRangeKey; label: string; ms: number }[] = [
  { key: '1h', label: '最近 1 小时', ms: HOUR },
  { key: '6h', label: '最近 6 小时', ms: 6 * HOUR },
  { key: '24h', label: '最近 24 小时', ms: DAY },
  { key: '7d', label: '最近 7 天', ms: 7 * DAY },
  { key: '30d', label: '最近 30 天', ms: 30 * DAY },
]

export const DEFAULT_QUICK_RANGE: QuickRangeKey = '1h'
/** 接口允许的最小跨度 1 分钟 */
export const MIN_SPAN_MS = 60_000
/** 接口允许的最大跨度 30 天 */
export const MAX_SPAN_MS = 30 * DAY

/** 以 now 为终点的快捷范围 */
export function quickRange(key: QuickRangeKey, now: number): TimeRange {
  const item = QUICK_RANGES.find((r) => r.key === key) ?? QUICK_RANGES[0]
  return { from: now - item.ms, to: now }
}

/**
 * 框选缩放得到的查询范围：起止向外取整到毫秒（允许反向框选）；不足 1 分钟时保留起点、向后补足到 1 分钟。
 */
export function zoomTo(start: number, end: number): TimeRange {
  const from = Math.floor(Math.min(start, end))
  const to = Math.max(Math.ceil(Math.max(start, end)), from + MIN_SPAN_MS)
  return { from, to }
}

/** 自定义范围的校验：返回给用户看的错误文案，合法时返回 null */
export function rangeError(range: TimeRange): string | null {
  if (range.from >= range.to) {
    return '开始时间须早于结束时间'
  }
  if (range.to - range.from < MIN_SPAN_MS) {
    return '时间跨度至少 1 分钟'
  }
  if (range.to - range.from > MAX_SPAN_MS) {
    return '时间跨度不能超过 30 天'
  }
  return null
}

/** 聚合桶宽的中文文案，取能整除的最大单位，如 60000 →「1 分钟」 */
export function bucketLabel(ms: number): string {
  if (ms % HOUR === 0) {
    return `${ms / HOUR} 小时`
  }
  if (ms % 60_000 === 0) {
    return `${ms / 60_000} 分钟`
  }
  return `${ms / 1000} 秒`
}
