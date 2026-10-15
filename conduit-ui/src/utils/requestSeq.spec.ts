import { describe, expect, it } from 'vitest'
import { createRequestSeq } from './requestSeq'

describe('createRequestSeq', () => {
  it('treats only the most recently started request as current', () => {
    const seq = createRequestSeq()
    const first = seq.next()
    const second = seq.next()

    expect(seq.isLatest(first)).toBe(false)
    expect(seq.isLatest(second)).toBe(true)
  })

  it('keeps separate sequences independent', () => {
    const a = createRequestSeq()
    const b = createRequestSeq()
    const idA = a.next()
    b.next()

    expect(a.isLatest(idA)).toBe(true)
  })
})
