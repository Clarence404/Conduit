/**
 * 设备列表筛选条件与路由 query 的互相转换（纯函数）：解析（非法值忽略）、序列化、接口参数、
 * 日期范围与毫秒时间戳互转、「更多筛选」中生效条件计数。
 * query 键：productKey、keyword、online、status、reported、createdFrom、createdTo。
 */
import type { DeviceStatus } from '@/api/types'

export type OnlineFilter = '' | 'true' | 'false'
export type StatusFilter = '' | DeviceStatus
/** 最后上报：RECENT 1 小时内有上报；STALE 有上报但超过 1 小时；NEVER 从未上报 */
export type ReportedFilter = '' | 'RECENT' | 'STALE' | 'NEVER'

/** 设备列表的全部筛选条件；空串/null 表示不筛选。创建时间为 [createdFrom, createdTo) 毫秒区间，两者同时有或同时无。 */
export interface DeviceFilters {
  productKey: string
  keyword: string
  online: OnlineFilter
  status: StatusFilter
  reported: ReportedFilter
  createdFrom: number | null
  createdTo: number | null
}

/** GET /api/v1/devices 的筛选类参数（不含分页）；未设置的条件缺省 */
export interface DeviceFilterParams {
  productKey?: string
  keyword?: string
  online?: boolean
  status?: DeviceStatus
  reported?: Exclude<ReportedFilter, ''>
  createdFrom?: number
  createdTo?: number
}

/** 没有任何筛选的初始值 */
export function emptyDeviceFilters(): DeviceFilters {
  return { productKey: '', keyword: '', online: '', status: '', reported: '', createdFrom: null, createdTo: null }
}

/** 取 query 值：数组取第一项，非字符串忽略 */
function first(v: unknown): string {
  const x = Array.isArray(v) ? v[0] : v
  return typeof x === 'string' ? x : ''
}

/** 只接受整数毫秒（纯数字字符串），否则 null */
function parseMillis(v: string): number | null {
  return /^\d{1,15}$/.test(v) ? Number(v) : null
}

/**
 * 从路由 query 解析筛选条件。枚举值不合法则忽略该项；创建时间必须两端都是合法整数且 from < to，否则两端一起忽略。
 */
export function parseDeviceQuery(query: Record<string, unknown>): DeviceFilters {
  const f = emptyDeviceFilters()
  f.productKey = first(query.productKey)
  f.keyword = first(query.keyword).trim()
  const online = first(query.online)
  if (online === 'true' || online === 'false') f.online = online
  const status = first(query.status)
  if (status === 'ENABLED' || status === 'DISABLED') f.status = status
  const reported = first(query.reported)
  if (reported === 'RECENT' || reported === 'STALE' || reported === 'NEVER') f.reported = reported
  const from = parseMillis(first(query.createdFrom))
  const to = parseMillis(first(query.createdTo))
  if (from !== null && to !== null && from < to) {
    f.createdFrom = from
    f.createdTo = to
  }
  return f
}

/** 序列化为路由 query：只包含有值的条件 */
export function toDeviceQuery(f: DeviceFilters): Record<string, string> {
  const q: Record<string, string> = {}
  if (f.productKey) q.productKey = f.productKey
  if (f.keyword) q.keyword = f.keyword
  if (f.online) q.online = f.online
  if (f.status) q.status = f.status
  if (f.reported) q.reported = f.reported
  if (f.createdFrom !== null && f.createdTo !== null) {
    q.createdFrom = String(f.createdFrom)
    q.createdTo = String(f.createdTo)
  }
  return q
}

/** 转为接口参数：online 变布尔，空条件省略 */
export function toDeviceParams(f: DeviceFilters): DeviceFilterParams {
  const p: DeviceFilterParams = {}
  if (f.productKey) p.productKey = f.productKey
  if (f.keyword) p.keyword = f.keyword
  if (f.online) p.online = f.online === 'true'
  if (f.status) p.status = f.status
  if (f.reported) p.reported = f.reported
  if (f.createdFrom !== null && f.createdTo !== null) {
    p.createdFrom = f.createdFrom
    p.createdTo = f.createdTo
  }
  return p
}

/** 日期选择器的起止日期 → [起始日本地 0 点, 结束日次日本地 0 点) 毫秒区间（用日历运算，夏令时日也正确） */
export function dayRangeToMillis(start: Date, end: Date): { from: number; to: number } {
  return {
    from: new Date(start.getFullYear(), start.getMonth(), start.getDate()).getTime(),
    to: new Date(end.getFullYear(), end.getMonth(), end.getDate() + 1).getTime(),
  }
}

/** 毫秒区间 → 日期选择器的 [起始日, 结束日]（结束日是 to 前 1 毫秒所在的那一天） */
export function millisToDayRange(from: number, to: number): [Date, Date] {
  const s = new Date(from)
  const e = new Date(to - 1)
  return [new Date(s.getFullYear(), s.getMonth(), s.getDate()), new Date(e.getFullYear(), e.getMonth(), e.getDate())]
}

/** 「更多筛选」里生效的条件个数：最后上报、创建时间 */
export function hiddenFilterCount(f: DeviceFilters): number {
  return (f.reported ? 1 : 0) + (f.createdFrom !== null && f.createdTo !== null ? 1 : 0)
}

/** 是否有任何筛选生效（用于区分「没有设备」与「没有符合条件的设备」） */
export function hasAnyFilter(f: DeviceFilters): boolean {
  return Boolean(f.productKey || f.keyword || f.online || f.status) || hiddenFilterCount(f) > 0
}
