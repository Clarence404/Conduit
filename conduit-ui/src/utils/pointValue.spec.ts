import { describe, expect, it } from 'vitest'
import type { PointView } from '@/api/types'
import { formatPointValue, pointLabel } from './pointValue'

function point(patch: Partial<PointView>): PointView {
  return { identifier: 'Ua', name: null, value: 220.123, ts: 0, unit: null, decimals: null, enumLabel: null, ...patch }
}

describe('pointLabel', () => {
  it('shows name with identifier, or the identifier alone', () => {
    expect(pointLabel(point({ name: 'A 相电压' }))).toBe('A 相电压（Ua）')
    expect(pointLabel(point({}))).toBe('Ua')
  })
})

describe('formatPointValue', () => {
  it('applies decimals and unit', () => {
    expect(formatPointValue(point({ value: 220.1, decimals: 1, unit: 'V' }))).toBe('220.1 V')
    expect(formatPointValue(point({ value: 220.46, decimals: 0, unit: 'V' }))).toBe('220 V')
    expect(formatPointValue(point({ value: 5, decimals: 2 }))).toBe('5.00')
  })

  it('falls back to the default number formatting without decimals', () => {
    expect(formatPointValue(point({ value: 220.123456, unit: 'V' }))).toBe('220.123 V')
  })

  it('shows the enum label instead of the value', () => {
    expect(formatPointValue(point({ value: 1, enumLabel: '运行', unit: 'x' }))).toBe('运行')
  })

  it('renders booleans and strings as-is', () => {
    expect(formatPointValue(point({ value: true }))).toBe('true')
    expect(formatPointValue(point({ value: 'auto' }))).toBe('auto')
  })
})
