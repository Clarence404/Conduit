/** deviceTabs 的单元测试：?tab= 与设备详情标签互转、离开历史标签去掉 ?props=、卡片跳转 query、?props= 解析 */
import { describe, expect, it } from 'vitest'
import { deviceTabFromQuery, deviceTabQuery, historyQuery, propsFromQuery } from './deviceTabs'

describe('deviceTabFromQuery', () => {
  it('accepts the known tabs', () => {
    expect(deviceTabFromQuery('latest')).toBe('latest')
    expect(deviceTabFromQuery('history')).toBe('history')
  })

  it('defaults to latest for missing or unknown values', () => {
    expect(deviceTabFromQuery(undefined)).toBe('latest')
    expect(deviceTabFromQuery(null)).toBe('latest')
    expect(deviceTabFromQuery('')).toBe('latest')
    expect(deviceTabFromQuery('secrets')).toBe('latest')
  })

  it('uses the first value of a repeated query param', () => {
    expect(deviceTabFromQuery(['history', 'latest'])).toBe('history')
  })
})

describe('deviceTabQuery', () => {
  it('sets the tab and keeps other query params', () => {
    expect(deviceTabQuery({ foo: 'bar' }, 'history')).toEqual({ foo: 'bar', tab: 'history' })
  })

  it('drops the param for the default tab', () => {
    expect(deviceTabQuery({ tab: 'history', foo: 'bar' }, 'latest')).toEqual({ foo: 'bar' })
  })
})

describe('props in the query', () => {
  it('drops props when leaving the history tab and keeps them on it', () => {
    expect(deviceTabQuery({ tab: 'history', props: 'Ua', foo: 'bar' }, 'latest')).toEqual({ foo: 'bar' })
    expect(deviceTabQuery({ props: 'Ua' }, 'history')).toEqual({ tab: 'history', props: 'Ua' })
  })

  it('builds the history query for a clicked card', () => {
    expect(historyQuery({ foo: 'bar' }, 'Ua')).toEqual({ foo: 'bar', tab: 'history', props: 'Ua' })
  })

  it('parses props: comma separated, trimmed, de-duplicated, first of a repeated param', () => {
    expect(propsFromQuery('Ua, Ub,,Ua')).toEqual(['Ua', 'Ub'])
    expect(propsFromQuery(['Ia', 'Ua'])).toEqual(['Ia'])
    expect(propsFromQuery(undefined)).toEqual([])
    expect(propsFromQuery(null)).toEqual([])
    expect(propsFromQuery('')).toEqual([])
  })
})
