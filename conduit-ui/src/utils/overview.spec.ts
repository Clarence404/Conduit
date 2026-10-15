/** overview 工具的单元测试：在线率、死信摘要（按条数排序、中文原因、0 为「无」、不可用） */
import { describe, expect, it } from 'vitest'
import { deadText, onlineRate, reasonLabel } from './overview'

describe('onlineRate', () => {
  it('rounds the percentage and handles empty or unknown values', () => {
    expect(onlineRate(100, 200)).toBe(50)
    expect(onlineRate(1, 3)).toBe(33)
    expect(onlineRate(0, 0)).toBe(0)
    expect(onlineRate(null, 10)).toBeNull()
  })
})

describe('deadText', () => {
  it('summarizes reasons by count with Chinese labels', () => {
    expect(deadText({ total: 5, byReason: { PROCESSING_ERROR: 1, FIELD_REJECTED: 3, MALFORMED_JSON: 1 } })).toBe(
      '测点被剔除 3 · JSON 格式错误 1 · 处理出错 1',
    )
  })

  it('shows 无 for zero and 暂不可用 when unknown', () => {
    expect(deadText({ total: 0, byReason: {} })).toBe('无')
    expect(deadText(null)).toBe('暂不可用')
  })

  it('keeps an unknown reason code as it is', () => {
    expect(reasonLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
    expect(deadText({ total: 2, byReason: { SOMETHING_NEW: 2 } })).toBe('SOMETHING_NEW 2')
  })
})
