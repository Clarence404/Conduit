/** 分页常量与前端分页辅助函数（所有分页表格默认每页 10 条，见 CLAUDE.md 2.2）。 */

/** 所有分页表格统一的每页条数 */
export const DEFAULT_PAGE_SIZE = 10
/** 每页条数可选项。 */
export const PAGE_SIZES = [10, 20, 50, 100]

/** 列表缩短（删除、导入、换每页条数）后把当前页收回到有效范围内 */
export function clampPage(page: number, total: number, size: number): number {
  if (!(size > 0) || !(page >= 1)) {
    return 1
  }
  const last = Math.max(1, Math.ceil(total / size))
  return Math.min(page, last)
}

/** 前端分页：取第 page 页（从 1 开始）的数据 */
export function pageSlice<T>(items: readonly T[], page: number, size: number): T[] {
  const start = (page - 1) * size
  return items.slice(start, start + size)
}
