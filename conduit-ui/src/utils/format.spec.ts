import { describe, expect, it } from 'vitest'
import { formatDateTime } from './format'

describe('formatDateTime', () => {
  it('formats epoch millis in local time', () => {
    const ms = new Date(2026, 9, 14, 8, 5, 3).getTime()
    expect(formatDateTime(ms)).toBe('2026-10-14 08:05:03')
  })

  it('formats ISO strings', () => {
    const iso = new Date(2026, 0, 2, 3, 4, 5).toISOString()
    expect(formatDateTime(iso)).toBe('2026-01-02 03:04:05')
  })

  it('renders a dash for empty or invalid values', () => {
    expect(formatDateTime(null)).toBe('—')
    expect(formatDateTime(undefined)).toBe('—')
    expect(formatDateTime('')).toBe('—')
    expect(formatDateTime('not a date')).toBe('—')
  })
})
