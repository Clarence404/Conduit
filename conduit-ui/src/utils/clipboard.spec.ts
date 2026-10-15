import { afterEach, describe, expect, it, vi } from 'vitest'
import { copyText } from './clipboard'

function mockClipboard(writeText: (text: string) => Promise<void>) {
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
}

describe('copyText', () => {
  afterEach(() => {
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true })
  })

  it('writes the text and reports success', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    mockClipboard(writeText)

    await expect(copyText('abc')).resolves.toBe(true)
    expect(writeText).toHaveBeenCalledWith('abc')
  })

  it('reports failure when the clipboard rejects', async () => {
    mockClipboard(vi.fn().mockRejectedValue(new Error('denied')))

    await expect(copyText('abc')).resolves.toBe(false)
  })

  it('reports failure when the clipboard API is unavailable', async () => {
    await expect(copyText('abc')).resolves.toBe(false)
  })
})
