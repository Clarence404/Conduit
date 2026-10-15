/**
 * 分页表格的通用状态（服务端分页与前端分页都适用）：页码与每页条数（默认 10 条）、加载状态、首次加载标记、
 * 请求序号（只有最后发出的请求能写入结果）与越界页码修正（删除等让总数变少、当前页已不存在时退到最后一页并重新加载）。
 * 取代各页面手写的 page/size/loading/requestSeq/clampPage 代码。
 */
import { ref, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import { problemMessage } from '@/api/errors'
import type { PageView } from '@/api/types'
import { clampPage, DEFAULT_PAGE_SIZE } from '@/utils/pagination'
import { createRequestSeq } from '@/utils/requestSeq'

/** 取一页数据；page 从 1 开始 */
export type PageFetcher<T> = (page: number, size: number) => Promise<PageView<T>>

/** useTable 返回的状态与操作；页面解构后在模板中使用（解构出的 ref 才会在模板中自动解包） */
export interface TableState<T> {
  items: Ref<T[]>
  total: Ref<number>
  page: Ref<number>
  size: Ref<number>
  /** 正在加载：表格的 loading 遮罩 */
  loading: Ref<boolean>
  /** 首次加载已结束（成功或失败）；之前显示骨架屏 */
  loaded: Ref<boolean>
  /** 最近一次加载失败（下一次加载开始时清除）；页面据此把空表格显示为「加载失败，点击重试」而不是引导新建 */
  failed: Ref<boolean>
  /** 按当前页码与条数加载 */
  load(): Promise<void>
  /** 回到第 1 页加载（筛选条件变化时） */
  search(): Promise<void>
  /** 每页条数变化：回到第 1 页加载 */
  onSizeChange(): Promise<void>
  /** 页码变化：加载该页 */
  onPageChange(): Promise<void>
}

/**
 * 创建分页表格状态。不会自动加载，由页面在挂载或切换标签时调用 load()。
 * @param fetch 取一页数据的函数；抛错时提示接口错误并保留上一次的数据
 */
export function useTable<T>(fetch: PageFetcher<T>): TableState<T> {
  const items = ref([]) as Ref<T[]>
  const total = ref(0)
  const page = ref(1)
  const size = ref(DEFAULT_PAGE_SIZE)
  const loading = ref(false)
  const loaded = ref(false)
  const failed = ref(false)
  const seq = createRequestSeq()

  /**
   * 加载当前页。先发后到的旧响应（及其错误提示、loading 复位）被丢弃；
   * 当前页为空且超出最后一页时，退到最后一页再加载一次。
   */
  async function load(): Promise<void> {
    const id = seq.next()
    loading.value = true
    failed.value = false
    try {
      const res = await fetch(page.value, size.value)
      if (!seq.isLatest(id)) return
      const last = clampPage(page.value, res.total, size.value)
      if (res.items.length === 0 && last < page.value) {
        page.value = last
        await load()
        return
      }
      items.value = res.items
      total.value = res.total
    } catch (e) {
      if (seq.isLatest(id)) {
        failed.value = true
        ElMessage.error(problemMessage(e))
      }
    } finally {
      if (seq.isLatest(id)) {
        loading.value = false
        loaded.value = true
      }
    }
  }

  /** 回到第 1 页加载 */
  function search(): Promise<void> {
    page.value = 1
    return load()
  }

  return { items, total, page, size, loading, loaded, failed, load, search, onSizeChange: search, onPageChange: load }
}
