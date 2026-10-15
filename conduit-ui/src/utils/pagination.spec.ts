import { describe, expect, it } from 'vitest'
import { clampPage, DEFAULT_PAGE_SIZE, PAGE_SIZES, pageSlice } from './pagination'

describe('page size options', () => {
  it('defaults to 10 and offers 10/20/50/100', () => {
    expect(DEFAULT_PAGE_SIZE).toBe(10)
    expect(PAGE_SIZES).toEqual([10, 20, 50, 100])
  })
})

describe('clampPage', () => {
  it('keeps a page that is still in range', () => {
    expect(clampPage(2, 25, 10)).toBe(2)
    expect(clampPage(3, 25, 10)).toBe(3)
  })

  it('moves back to the last page when the list shrinks', () => {
    expect(clampPage(3, 20, 10)).toBe(2)
    expect(clampPage(5, 11, 10)).toBe(2)
  })

  it('falls back to page 1 for an empty list or invalid input', () => {
    expect(clampPage(4, 0, 10)).toBe(1)
    expect(clampPage(0, 30, 10)).toBe(1)
    expect(clampPage(2, 30, 0)).toBe(1)
  })
})

describe('pageSlice', () => {
  const items = Array.from({ length: 23 }, (_, i) => i)

  it('returns the rows of the requested page', () => {
    expect(pageSlice(items, 1, 10)).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8, 9])
    expect(pageSlice(items, 3, 10)).toEqual([20, 21, 22])
  })

  it('returns an empty array past the end', () => {
    expect(pageSlice(items, 4, 10)).toEqual([])
  })
})
