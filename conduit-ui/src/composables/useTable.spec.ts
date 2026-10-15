/** useTable 的单元测试：默认第 1 页 10 条、筛选与改条数回到第 1 页、过期响应丢弃、越界页码修正、失败提示与首次加载标记 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import type { PageView } from '@/api/types'
import { useTable } from './useTable'

function pageOf(items: string[], total: number, page = 1, size = 10): PageView<string> {
  return { items, total, page, size }
}

/** 可手动完成的 Promise，用来制造乱序响应 */
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

describe('useTable', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('loads page 1 with 10 rows and marks the first load as done', async () => {
    const fetch = vi.fn().mockResolvedValue(pageOf(['a', 'b'], 2))
    const t = useTable<string>(fetch)
    expect(t.loaded.value).toBe(false)

    await t.load()

    expect(fetch).toHaveBeenCalledWith(1, 10)
    expect(t.items.value).toEqual(['a', 'b'])
    expect(t.total.value).toBe(2)
    expect(t.loading.value).toBe(false)
    expect(t.loaded.value).toBe(true)
  })

  it('goes back to page 1 on search and on a page-size change, and loads the chosen page', async () => {
    const fetch = vi.fn().mockResolvedValue(pageOf(['x'], 500))
    const t = useTable<string>(fetch)

    t.page.value = 3
    await t.search()
    expect(fetch).toHaveBeenLastCalledWith(1, 10)

    t.page.value = 2
    t.size.value = 50
    await t.onSizeChange()
    expect(fetch).toHaveBeenLastCalledWith(1, 50)

    t.page.value = 4
    await t.onPageChange()
    expect(fetch).toHaveBeenLastCalledWith(4, 50)
  })

  it('drops a response that arrives after a newer one', async () => {
    const slow = deferred<PageView<string>>()
    const fast = deferred<PageView<string>>()
    const fetch = vi.fn().mockReturnValueOnce(slow.promise).mockReturnValueOnce(fast.promise)
    const t = useTable<string>(fetch)

    const first = t.load()
    const second = t.load()
    fast.resolve(pageOf(['new'], 1))
    await second
    slow.resolve(pageOf(['old'], 1))
    await first

    expect(t.items.value).toEqual(['new'])
    expect(t.loading.value).toBe(false)
  })

  it('moves back to the last page when the current page no longer exists', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(pageOf([], 15, 3)).mockResolvedValueOnce(pageOf(['k'], 15, 2))
    const t = useTable<string>(fetch)
    t.page.value = 3

    await t.load()

    expect(fetch).toHaveBeenNthCalledWith(1, 3, 10)
    expect(fetch).toHaveBeenNthCalledWith(2, 2, 10)
    expect(t.page.value).toBe(2)
    expect(t.items.value).toEqual(['k'])
  })

  it('shows the error of the latest request and keeps the previous rows', async () => {
    const error = vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: () => {} }) as never)
    const fetch = vi.fn().mockResolvedValueOnce(pageOf(['a'], 1)).mockRejectedValueOnce(new Error('服务不可用'))
    const t = useTable<string>(fetch)

    await t.load()
    await t.load()

    expect(error).toHaveBeenCalledWith('服务不可用')
    expect(t.items.value).toEqual(['a'])
    expect(t.loaded.value).toBe(true)
    expect(t.loading.value).toBe(false)
  })

  it('flags a failed load, keeps it until a later load succeeds, and clears it at the start of each load', async () => {
    vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: () => {} }) as never)
    const retry = deferred<PageView<string>>()
    const fetch = vi.fn().mockRejectedValueOnce(new Error('服务不可用')).mockReturnValueOnce(retry.promise)
    const t = useTable<string>(fetch)
    expect(t.failed.value).toBe(false)

    await t.load()
    expect(t.failed.value).toBe(true)
    expect(t.loaded.value).toBe(true)

    const second = t.load()
    expect(t.failed.value).toBe(false)
    retry.resolve(pageOf(['a'], 1))
    await second
    expect(t.failed.value).toBe(false)
    expect(t.items.value).toEqual(['a'])
  })
})
