import { describe, expect, it } from 'vitest'
import { tabFromQuery, tabQuery } from './productTabs'

describe('tabFromQuery', () => {
  it('accepts the known tabs', () => {
    expect(tabFromQuery('basic')).toBe('basic')
    expect(tabFromQuery('model')).toBe('model')
    expect(tabFromQuery('devices')).toBe('devices')
  })

  it('defaults to basic for missing or unknown values', () => {
    expect(tabFromQuery(undefined)).toBe('basic')
    expect(tabFromQuery(null)).toBe('basic')
    expect(tabFromQuery('')).toBe('basic')
    expect(tabFromQuery('secrets')).toBe('basic')
  })

  it('uses the first value of a repeated query param', () => {
    expect(tabFromQuery(['model', 'devices'])).toBe('model')
    expect(tabFromQuery([null, 'model'])).toBe('basic')
  })
})

describe('tabQuery', () => {
  it('sets the tab and keeps other query params', () => {
    expect(tabQuery({ foo: 'bar' }, 'model')).toEqual({ foo: 'bar', tab: 'model' })
    expect(tabQuery({ tab: 'model' }, 'devices')).toEqual({ tab: 'devices' })
  })

  it('drops the param for the default tab', () => {
    expect(tabQuery({ tab: 'model', foo: 'bar' }, 'basic')).toEqual({ foo: 'bar' })
  })
})
