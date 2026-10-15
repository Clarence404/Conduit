/** deviceFilters 工具测试：路由 query 解析（含非法值忽略）、序列化、接口参数、日期与毫秒互转、更多筛选计数 */
import { describe, expect, it } from 'vitest'
import {
  dayRangeToMillis,
  emptyDeviceFilters,
  hiddenFilterCount,
  isCanonicalQuery,
  sameDeviceFilters,
  hasAnyFilter,
  millisToDayRange,
  parseDeviceQuery,
  toDeviceParams,
  toDeviceQuery,
} from './deviceFilters'

describe('parseDeviceQuery', () => {
  it('returns empty filters for an empty query', () => {
    expect(parseDeviceQuery({})).toEqual(emptyDeviceFilters())
  })

  it('reads every supported key', () => {
    const f = parseDeviceQuery({
      productKey: 'pk1',
      keyword: '电表',
      online: 'true',
      status: 'DISABLED',
      reported: 'STALE',
      createdFrom: '1000',
      createdTo: '2000',
    })
    expect(f).toEqual({
      productKey: 'pk1',
      keyword: '电表',
      online: 'true',
      status: 'DISABLED',
      reported: 'STALE',
      createdFrom: 1000,
      createdTo: 2000,
    })
  })

  it('ignores invalid enum values and takes the first of repeated keys', () => {
    const f = parseDeviceQuery({ online: 'yes', status: 'enabled', reported: 'SOON', keyword: ['a', 'b'] })
    expect(f.online).toBe('')
    expect(f.status).toBe('')
    expect(f.reported).toBe('')
    expect(f.keyword).toBe('a')
  })

  it('ignores a malformed or incomplete creation range', () => {
    expect(parseDeviceQuery({ createdFrom: 'abc', createdTo: '2000' })).toMatchObject({ createdFrom: null, createdTo: null })
    expect(parseDeviceQuery({ createdFrom: '1000' })).toMatchObject({ createdFrom: null, createdTo: null })
    expect(parseDeviceQuery({ createdFrom: '2000', createdTo: '1000' })).toMatchObject({ createdFrom: null, createdTo: null })
    expect(parseDeviceQuery({ createdFrom: '1.5', createdTo: '2000' })).toMatchObject({ createdFrom: null, createdTo: null })
  })
})

describe('toDeviceQuery / toDeviceParams', () => {
  it('omits empty filters from the query', () => {
    expect(toDeviceQuery(emptyDeviceFilters())).toEqual({})
  })

  it('round-trips through parse', () => {
    const f = { ...emptyDeviceFilters(), productKey: 'pk1', online: 'false' as const, createdFrom: 1000, createdTo: 5000 }
    expect(toDeviceQuery(f)).toEqual({ productKey: 'pk1', online: 'false', createdFrom: '1000', createdTo: '5000' })
    expect(parseDeviceQuery(toDeviceQuery(f))).toEqual(f)
  })

  it('maps filters to API params with booleans and undefined for empty', () => {
    expect(toDeviceParams(emptyDeviceFilters())).toEqual({})
    expect(Object.values(toDeviceParams(emptyDeviceFilters())).every((v) => v === undefined)).toBe(true)
    expect(
      toDeviceParams({ ...emptyDeviceFilters(), online: 'true', status: 'ENABLED', reported: 'NEVER', keyword: 'x', createdFrom: 1, createdTo: 2 }),
    ).toEqual({ keyword: 'x', online: true, status: 'ENABLED', reported: 'NEVER', createdFrom: 1, createdTo: 2 })
    expect(toDeviceParams({ ...emptyDeviceFilters(), online: 'false' }).online).toBe(false)
  })
})

describe('day range conversion', () => {
  it('converts to local start of the first day and start of the day after the last', () => {
    const { from, to } = dayRangeToMillis(new Date(2026, 9, 1, 13, 30), new Date(2026, 9, 31, 8))
    expect(from).toBe(new Date(2026, 9, 1).getTime())
    expect(to).toBe(new Date(2026, 10, 1).getTime())
  })

  it('converts back to the first and last day', () => {
    const [s, e] = millisToDayRange(new Date(2026, 9, 1).getTime(), new Date(2026, 10, 1).getTime())
    expect(s).toEqual(new Date(2026, 9, 1))
    expect(e).toEqual(new Date(2026, 9, 31))
  })
})

describe('counts', () => {
  it('counts only active hidden filters', () => {
    const f = emptyDeviceFilters()
    expect(hiddenFilterCount(f)).toBe(0)
    expect(hiddenFilterCount({ ...f, online: 'true', keyword: 'a' })).toBe(0)
    expect(hiddenFilterCount({ ...f, reported: 'RECENT' })).toBe(1)
    expect(hiddenFilterCount({ ...f, reported: 'RECENT', createdFrom: 1, createdTo: 2 })).toBe(2)
  })

  it('detects any active filter', () => {
    expect(hasAnyFilter(emptyDeviceFilters())).toBe(false)
    expect(hasAnyFilter({ ...emptyDeviceFilters(), status: 'ENABLED' })).toBe(true)
    expect(hasAnyFilter({ ...emptyDeviceFilters(), createdFrom: 1, createdTo: 2 })).toBe(true)
  })
})

describe('canonical query and equality', () => {
  it('knows whether a query already equals its serialised parse', () => {
    expect(isCanonicalQuery({})).toBe(true)
    expect(isCanonicalQuery({ online: 'true' })).toBe(true)
    expect(isCanonicalQuery({ online: 'true', status: 'bogus' })).toBe(false)
    expect(isCanonicalQuery({ foo: 'x' })).toBe(false)
    expect(isCanonicalQuery({ online: ['true', 'false'] })).toBe(false)
  })

  it('compares filters by value', () => {
    expect(sameDeviceFilters(emptyDeviceFilters(), emptyDeviceFilters())).toBe(true)
    expect(sameDeviceFilters(emptyDeviceFilters(), { ...emptyDeviceFilters(), online: 'true' })).toBe(false)
  })
})
