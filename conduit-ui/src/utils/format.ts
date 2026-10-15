/** 显示格式化：日期时间与计数。 */

/**
 * 格式化为本地时区的 yyyy-MM-dd HH:mm:ss。
 * @param value ISO 字符串或毫秒时间戳；空值或无法解析时返回「—」
 */
export function formatDateTime(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) {
    return '—'
  }
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** 计数显示：null / undefined（如 Redis 不可用导致在线数未知）显示「—」，否则原样显示 */
export function formatCount(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : String(value)
}
