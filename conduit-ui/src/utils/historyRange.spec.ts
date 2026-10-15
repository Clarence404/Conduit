/** historyRange 的单元测试：快捷项换算、缩放下限、自定义范围校验、聚合桶文案 */
import { describe, expect, it } from 'vitest'
import {
  bucketLabel,
  DEFAULT_QUICK_RANGE,
  MAX_SPAN_MS,
  QUICK_RANGES,
  quickRange,
  rangeError,
  zoomTo,
} from './historyRange'

const NOW = Date.UTC(2026, 9, 15, 8, 0, 0)
const HOUR = 3_600_000

describe('quick ranges', () => {
  it('lists the spec ranges in order with Chinese labels and defaults to the last hour', () => {
    expect(QUICK_RANGES.map((r) => r.label)).toEqual(['最近 1 小时', '最近 6 小时', '最近 24 小时', '最近 7 天', '最近 30 天'])
    expect(DEFAULT_QUICK_RANGE).toBe('1h')
  })

  it('ends at now', () => {
    expect(quickRange('1h', NOW)).toEqual({ from: NOW - HOUR, to: NOW })
    expect(quickRange('6h', NOW)).toEqual({ from: NOW - 6 * HOUR, to: NOW })
    expect(quickRange('24h', NOW)).toEqual({ from: NOW - 24 * HOUR, to: NOW })
    expect(quickRange('7d', NOW)).toEqual({ from: NOW - 7 * 24 * HOUR, to: NOW })
    expect(quickRange('30d', NOW)).toEqual({ from: NOW - 30 * 24 * HOUR, to: NOW })
  })
})

describe('zoomTo', () => {
  it('rounds outward to whole milliseconds', () => {
    expect(zoomTo(1000.4, 200000.2)).toEqual({ from: 1000, to: 200001 })
  })

  it('widens a selection shorter than one minute to one minute', () => {
    expect(zoomTo(1000, 11000)).toEqual({ from: 1000, to: 61000 })
  })

  it('accepts a reversed selection', () => {
    expect(zoomTo(200000, 100000)).toEqual({ from: 100000, to: 200000 })
  })
})

describe('rangeError', () => {
  it('accepts one minute to thirty days', () => {
    expect(rangeError({ from: NOW - 60_000, to: NOW })).toBeNull()
    expect(rangeError({ from: NOW - MAX_SPAN_MS, to: NOW })).toBeNull()
  })

  it('explains what is wrong', () => {
    expect(rangeError({ from: NOW, to: NOW })).toBe('开始时间须早于结束时间')
    expect(rangeError({ from: NOW - 59_999, to: NOW })).toBe('时间跨度至少 1 分钟')
    expect(rangeError({ from: NOW - MAX_SPAN_MS - 1, to: NOW })).toBe('时间跨度不能超过 30 天')
  })
})

describe('bucketLabel', () => {
  it('uses the largest whole unit', () => {
    expect(bucketLabel(1000)).toBe('1 秒')
    expect(bucketLabel(30_000)).toBe('30 秒')
    expect(bucketLabel(60_000)).toBe('1 分钟')
    expect(bucketLabel(300_000)).toBe('5 分钟')
    expect(bucketLabel(HOUR)).toBe('1 小时')
    expect(bucketLabel(12 * HOUR)).toBe('12 小时')
  })
})
