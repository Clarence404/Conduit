import { describe, expect, it } from 'vitest'
import { buildConnectionInfo, formatValue } from './connection'

describe('buildConnectionInfo', () => {
  it('derives MQTT parameters from keys', () => {
    const info = buildConnectionInfo('pk1', 'dk1', 'broker.local')

    expect(info).toMatchObject({
      host: 'broker.local',
      port: 1883,
      clientId: 'dk1',
      username: 'dk1',
      topic: 'conduit/pk1/dk1/up/property',
    })
    expect(JSON.parse(info.payloadExample)).toHaveProperty('params')
  })
})

describe('formatValue', () => {
  it('trims trailing zeros of decimals to at most 3 places', () => {
    expect(formatValue(220.1)).toBe('220.1')
    expect(formatValue(1234.56789)).toBe('1234.568')
    expect(formatValue(5)).toBe('5')
  })

  it('renders strings and booleans as-is', () => {
    expect(formatValue('auto')).toBe('auto')
    expect(formatValue(true)).toBe('true')
  })
})
