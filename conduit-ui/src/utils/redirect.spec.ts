/** safeRedirect 的单元测试：只接受站内相对路径，其余回到默认页（首页） */
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
      expect(safeRedirect(value)).toBe('/home')
    },
  )

  it('rejects non-strings', () => {
    expect(safeRedirect(undefined)).toBe('/home')
    expect(safeRedirect(['/devices'])).toBe('/home')
  })

  it('uses a custom fallback', () => {
    expect(safeRedirect('//x', '/products')).toBe('/products')
  })
})
