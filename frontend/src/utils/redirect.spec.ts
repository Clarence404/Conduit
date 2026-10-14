import { describe, expect, it } from 'vitest'
import { safeRedirect } from './redirect'

describe('safeRedirect', () => {
  it('accepts in-app paths', () => {
    expect(safeRedirect('/devices/abc')).toBe('/devices/abc')
    expect(safeRedirect('/devices?x=1#y')).toBe('/devices?x=1#y')
  })

  it.each(['//evil.com', '/\\evil.com', 'http://evil.com', 'javascript:alert(1)', 'devices', ''])(
    'rejects %s',
    (value) => {
      expect(safeRedirect(value)).toBe('/devices')
    },
  )

  it('rejects non-strings', () => {
    expect(safeRedirect(undefined)).toBe('/devices')
    expect(safeRedirect(['/devices'])).toBe('/devices')
  })

  it('uses a custom fallback', () => {
    expect(safeRedirect('//x', '/home')).toBe('/home')
  })
})
