import { afterEach, describe, expect, it, vi } from 'vitest'
import { copyText } from './clipboard'

function mockClipboard(writeText: ((text: string) => Promise<void>) | undefined) {
  Object.defineProperty(navigator, 'clipboard', {
    value: writeText ? { writeText } : undefined,
    configurable: true,
  })
}

/** jsdom 没有 execCommand，测试里自己挂一个，并记录调用时被选中的文本 */
function mockExecCommand(result: boolean | Error) {
  const copied: string[] = []
  const exec = vi.fn((command: string) => {
    expect(command).toBe('copy')
    const active = document.activeElement as HTMLTextAreaElement | null
    copied.push(active?.value.substring(active.selectionStart, active.selectionEnd) ?? '')
    if (result instanceof Error) {
      throw result
    }
    return result
  })
  Object.defineProperty(document, 'execCommand', { value: exec, configurable: true, writable: true })
  return { exec, copied }
}

describe('copyText', () => {
  afterEach(() => {
    mockClipboard(undefined)
    Reflect.deleteProperty(document, 'execCommand')
  })

  it('uses the clipboard API when available', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    mockClipboard(writeText)
    const { exec } = mockExecCommand(true)

    await expect(copyText('abc')).resolves.toBe(true)
    expect(writeText).toHaveBeenCalledWith('abc')
    expect(exec).not.toHaveBeenCalled()
  })

  it('falls back to execCommand when the clipboard API is unavailable (plain http)', async () => {
    const { copied } = mockExecCommand(true)

    await expect(copyText('pk-123')).resolves.toBe(true)
    expect(copied).toEqual(['pk-123'])
    expect(document.querySelectorAll('textarea')).toHaveLength(0)
  })

  it('falls back to execCommand when the clipboard API rejects', async () => {
    mockClipboard(vi.fn().mockRejectedValue(new Error('denied')))
    const { copied } = mockExecCommand(true)

    await expect(copyText('abc')).resolves.toBe(true)
    expect(copied).toEqual(['abc'])
  })

  it('reports failure when the fallback also fails', async () => {
    mockClipboard(vi.fn().mockRejectedValue(new Error('denied')))
    mockExecCommand(false)
    await expect(copyText('abc')).resolves.toBe(false)

    mockExecCommand(new Error('not supported'))
    await expect(copyText('abc')).resolves.toBe(false)
    expect(document.querySelectorAll('textarea')).toHaveLength(0)
  })
})
