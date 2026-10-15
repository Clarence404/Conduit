/** deviceTabs 的单元测试：?tab= 与设备详情标签互转 */
import { describe, expect, it } from 'vitest'
import { deviceTabFromQuery, deviceTabQuery } from './deviceTabs'

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
