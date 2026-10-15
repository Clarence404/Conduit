import { describe, expect, it } from 'vitest'
import type { PropertyView } from '@/api/types'
import {
  codecLabel,
  emptyForm,
  fieldVisibility,
  formatRange,
  formFromProperty,
  toPropertyRequest,
  type PropertyForm,
} from './thingModel'

function form(patch: Partial<PropertyForm>): PropertyForm {
  return { ...emptyForm(), identifier: 'Ua', name: 'A 相电压', ...patch }
}

describe('fieldVisibility', () => {
  it('shows range and decimals only for numeric types', () => {
    expect(fieldVisibility('DOUBLE')).toEqual({ range: true, decimals: true, enumItems: false, intHint: false })
    expect(fieldVisibility('INT')).toEqual({ range: true, decimals: true, enumItems: false, intHint: true })
  })

  it('shows enum items only for ENUM and hides everything for BOOL/TEXT', () => {
    expect(fieldVisibility('ENUM')).toEqual({ range: false, decimals: false, enumItems: true, intHint: true })
    expect(fieldVisibility('BOOL')).toEqual({ range: false, decimals: false, enumItems: false, intHint: false })
    expect(fieldVisibility('TEXT')).toEqual({ range: false, decimals: false, enumItems: false, intHint: false })
  })
})

describe('toPropertyRequest', () => {
  it('parses numbers and turns blanks into null', () => {
    expect(toPropertyRequest(form({ dataType: 'DOUBLE', unit: ' V ', minValue: '0', maxValue: '', decimals: '1' }))).toEqual({
      identifier: 'Ua',
      name: 'A 相电压',
      dataType: 'DOUBLE',
      unit: 'V',
      minValue: 0,
      maxValue: null,
      decimals: 1,
      enumItems: null,
      description: null,
    })
  })

  it('drops fields hidden for the type', () => {
    const req = toPropertyRequest(form({ dataType: 'BOOL', minValue: '1', decimals: '2', enumItems: [{ value: '1', label: 'x' }] }))
    expect(req.minValue).toBeNull()
    expect(req.decimals).toBeNull()
    expect(req.enumItems).toBeNull()
  })

  it('parses enum items', () => {
    const req = toPropertyRequest(form({ dataType: 'ENUM', enumItems: [{ value: '0', label: ' 停止 ' }, { value: '1', label: '运行' }] }))
    expect(req.enumItems).toEqual([
      { value: 0, label: '停止' },
      { value: 1, label: '运行' },
    ])
  })

  it('rejects non-numeric or non-integer input', () => {
    expect(() => toPropertyRequest(form({ minValue: 'abc' }))).toThrow('最小值必须是数字')
    expect(() => toPropertyRequest(form({ decimals: '1.5' }))).toThrow('小数位数必须是整数')
    expect(() => toPropertyRequest(form({ dataType: 'ENUM', enumItems: [{ value: '1.5', label: 'x' }] }))).toThrow('整数')
    expect(() => toPropertyRequest(form({ dataType: 'ENUM', enumItems: [{ value: '', label: 'x' }] }))).toThrow('不能为空')
  })

  it('round-trips an existing property', () => {
    const view: PropertyView = {
      identifier: 'st',
      name: '状态',
      dataType: 'ENUM',
      unit: null,
      minValue: null,
      maxValue: null,
      decimals: null,
      enumItems: [{ value: 1, label: '运行' }],
      description: '运行状态',
      createdAt: '',
      updatedAt: '',
    }
    const { createdAt, updatedAt, ...request } = view
    void createdAt
    void updatedAt
    expect(toPropertyRequest(formFromProperty(view))).toEqual(request)
  })
})

describe('formatRange', () => {
  it('renders both, one or no bound', () => {
    expect(formatRange(0, 500)).toBe('0 ~ 500')
    expect(formatRange(0, null)).toBe('≥ 0')
    expect(formatRange(null, 500)).toBe('≤ 500')
    expect(formatRange(null, null)).toBe('—')
  })
})

describe('codecLabel', () => {
  it('uses the display name and falls back to the raw name', () => {
    const codecs = [{ name: 'flat-json', displayName: '扁平 JSON' }]
    expect(codecLabel(codecs, 'flat-json')).toBe('扁平 JSON')
    expect(codecLabel(codecs, 'custom')).toBe('custom')
  })
})
