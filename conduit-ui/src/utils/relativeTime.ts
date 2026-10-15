/** 相对时间文案：「刚刚」「N 分钟前」「N 小时前」「N 天前」，超过 30 天显示日期。 */
import { formatDateTime } from './format'

const MIN = 60_000
const HOUR = 60 * MIN
const DAY = 24 * HOUR

/**
 * 把时间转成相对 now 的文案。
 * @param value ISO 字符串或毫秒时间戳；空值或无法解析时返回「—」
 * @param now 当前时间（毫秒）
 * @returns 不到 1 分钟（含时钟偏差导致的未来时间）为「刚刚」；不到 1 小时为「N 分钟前」；不到 1 天为「N 小时前」；
 *          不到 30 天为「N 天前」；更早为 yyyy-MM-dd
 */
export function relativeTime(value: string | number | null | undefined, now: number): string {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  const t = new Date(value).getTime()
  if (Number.isNaN(t)) {
    return '—'
  }
  const diff = now - t
  if (diff < MIN) {
    return '刚刚'
  }
  if (diff < HOUR) {
    return `${Math.floor(diff / MIN)} 分钟前`
  }
  if (diff < DAY) {
    return `${Math.floor(diff / HOUR)} 小时前`
  }
  if (diff < 30 * DAY) {
    return `${Math.floor(diff / DAY)} 天前`
  }
  return formatDateTime(t).slice(0, 10)
}
