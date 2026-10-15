/** relativeTime 的单元测试：刚刚 / 分钟 / 小时 / 天 / 超过 30 天显示日期 / 空值与无效值 */
import { describe, expect, it } from 'vitest'
import { relativeTime } from './relativeTime'

const NOW = new Date(2026, 9, 15, 8, 0, 0).getTime()
const MIN = 60_000
const HOUR = 3_600_000
const DAY = 86_400_000

describe('relativeTime', () => {
  it.each([
    [NOW - 10_000, '刚刚'],
    [NOW + 5_000, '刚刚'],
    [NOW - 3 * MIN, '3 分钟前'],
    [NOW - 59 * MIN, '59 分钟前'],
    [NOW - 2 * HOUR, '2 小时前'],
    [NOW - 3 * DAY, '3 天前'],
    [NOW - 29 * DAY, '29 天前'],
  ])('formats %s', (value, expected) => {
    expect(relativeTime(value, NOW)).toBe(expected)
  })

  it('shows the date for anything older than 30 days', () => {
    expect(relativeTime(new Date(2026, 7, 1, 9, 30).getTime(), NOW)).toBe('2026-08-01')
  })

  it('accepts ISO strings', () => {
    expect(relativeTime(new Date(NOW - 5 * MIN).toISOString(), NOW)).toBe('5 分钟前')
  })

  it.each([null, undefined, '', 'not a date'])('shows — for %s', (value) => {
    expect(relativeTime(value, NOW)).toBe('—')
  })
})
