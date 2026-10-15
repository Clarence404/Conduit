/** historyProperties 的单元测试：测点候选过滤、上限、默认选中 */
import { describe, expect, it } from 'vitest'
import type { DataType, PointView, PropertyView } from '@/api/types'
import { DEFAULT_SELECTED, MAX_SELECTED, defaultSelection, initialSelection, propertyCandidates } from './historyProperties'

function prop(identifier: string, dataType: DataType, name = identifier.toUpperCase()): PropertyView {
  return {
    identifier,
    name,
    dataType,
    unit: null,
    minValue: null,
    maxValue: null,
    decimals: null,
    enumItems: null,
    description: null,
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
  }
}

function point(identifier: string, value: number | string | boolean): PointView {
  return { identifier, name: null, value, ts: 0, unit: null, decimals: null, enumLabel: null }
}

describe('propertyCandidates', () => {
  it('keeps model order, drops TEXT and labels with name and identifier', () => {
    const c = propertyCandidates([prop('Ua', 'DOUBLE', 'A 相电压'), prop('note', 'TEXT'), prop('st', 'ENUM', '运行状态')], [])

    expect(c).toEqual([
      { identifier: 'Ua', label: 'A 相电压（Ua）', numeric: true },
      { identifier: 'st', label: '运行状态（st）', numeric: false },
    ])
  })

  it('adds numeric and boolean latest values that the model does not define', () => {
    const c = propertyCandidates(
      [prop('Ua', 'DOUBLE'), prop('note', 'TEXT')],
      [point('Ua', 220), point('note', 'x'), point('Zz', 3), point('on', true), point('mode', 'auto')],
    )

    expect(c.map((x) => x.identifier)).toEqual(['Ua', 'Zz', 'on'])
    expect(c[1]).toEqual({ identifier: 'Zz', label: 'Zz', numeric: true })
    expect(c[2]).toEqual({ identifier: 'on', label: 'on', numeric: false })
  })

  it('treats INT as numeric and BOOL as state', () => {
    const c = propertyCandidates([prop('cnt', 'INT'), prop('flag', 'BOOL')], [])

    expect(c.map((x) => x.numeric)).toEqual([true, false])
  })
})

describe('defaultSelection', () => {
  it('selects the first four numeric candidates', () => {
    const c = propertyCandidates(
      ['Ua', 'Ub', 'st', 'Uc', 'Ia', 'Ib'].map((id) => prop(id, id === 'st' ? 'ENUM' : 'DOUBLE')),
      [],
    )

    expect(defaultSelection(c)).toEqual(['Ua', 'Ub', 'Uc', 'Ia'])
    expect(DEFAULT_SELECTED).toBe(4)
    expect(MAX_SELECTED).toBe(8)
  })

  it('selects fewer when fewer are numeric', () => {
    expect(defaultSelection(propertyCandidates([prop('st', 'ENUM'), prop('Ua', 'DOUBLE')], []))).toEqual(['Ua'])
    expect(defaultSelection([])).toEqual([])
  })
})

describe('initialSelection', () => {
  const candidates = propertyCandidates(
    [prop('Ua', 'DOUBLE'), prop('Ub', 'DOUBLE'), prop('Uc', 'DOUBLE'), prop('Ia', 'DOUBLE'), prop('Ib', 'DOUBLE'), prop('st', 'ENUM')],
    [],
  )

  it('keeps the requested identifiers that exist, in the requested order', () => {
    expect(initialSelection(candidates, ['st', 'nope', 'Ua'])).toEqual(['st', 'Ua'])
  })

  it('falls back to the default selection when nothing requested exists', () => {
    expect(initialSelection(candidates, ['nope'])).toEqual(defaultSelection(candidates))
    expect(initialSelection(candidates, [])).toEqual(defaultSelection(candidates))
  })

  it('keeps at most MAX_SELECTED identifiers', () => {
    const many = propertyCandidates(
      Array.from({ length: 10 }, (_, i) => prop(`P${i}`, 'DOUBLE')),
      [],
    )

    expect(initialSelection(many, many.map((c) => c.identifier))).toHaveLength(MAX_SELECTED)
  })
})
