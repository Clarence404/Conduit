/**
 * deviceFilters 日期转换的夏令时测试：把时区固定为 America/New_York，2026-11-01 夏令时结束（当天 25 小时），
 * 验证「次日本地 0 点」按日历计算而不是加 24 小时。若运行环境不允许在测试内改 TZ，则跳过。
 */
import { describe, expect, it } from 'vitest'
import { dayRangeToMillis, millisToDayRange } from './deviceFilters'

process.env.TZ = 'America/New_York'
// 时区未生效时（6 月偏移不是纽约夏令时的 240 分钟）跳过
const tzApplied = new Date(2026, 5, 1).getTimezoneOffset() === 240

describe.skipIf(!tzApplied)('day range conversion across DST end (America/New_York)', () => {
  it('treats the DST end day as 25 hours long', () => {
    const { from, to } = dayRangeToMillis(new Date(2026, 10, 1), new Date(2026, 10, 1))
    expect(to - from).toBe(25 * 3_600_000)
    expect(new Date(from).getHours()).toBe(0)
    expect(new Date(to).getHours()).toBe(0)
  })

  it('round-trips a range ending on the DST end day', () => {
    const { from, to } = dayRangeToMillis(new Date(2026, 9, 30), new Date(2026, 10, 1))
    const [s, e] = millisToDayRange(from, to)
    expect(s).toEqual(new Date(2026, 9, 30))
    expect(e).toEqual(new Date(2026, 10, 1))
  })
})
