/** 历史页的测点候选、默认选中与初始选中（来自 ?props=） */
import type { PointView, PropertyView } from '@/api/types'

/** 可选测点：numeric 为 true 的画折线，否则画状态阶梯线 */
export interface PropertyCandidate {
  identifier: string
  label: string
  numeric: boolean
}

/** 最多同时选择的测点数（与接口上限一致） */
export const MAX_SELECTED = 8
/** 默认选中的数值测点数 */
export const DEFAULT_SELECTED = 4

/**
 * 测点候选：物模型中非 TEXT 的属性（按物模型顺序），再加上最新值中出现、物模型未定义的数值或布尔测点。
 * @param model 产品物模型属性
 * @param latest 设备最新值（用于发现没有物模型的测点）
 */
export function propertyCandidates(model: PropertyView[], latest: PointView[]): PropertyCandidate[] {
  const result: PropertyCandidate[] = model
    .filter((p) => p.dataType !== 'TEXT')
    .map((p) => ({
      identifier: p.identifier,
      label: `${p.name}（${p.identifier}）`,
      numeric: p.dataType === 'DOUBLE' || p.dataType === 'INT',
    }))
  const defined = new Set(model.map((p) => p.identifier))
  for (const v of latest) {
    if (defined.has(v.identifier)) {
      continue
    }
    if (typeof v.value === 'number') {
      result.push({ identifier: v.identifier, label: v.identifier, numeric: true })
    } else if (typeof v.value === 'boolean') {
      result.push({ identifier: v.identifier, label: v.identifier, numeric: false })
    }
  }
  return result
}

/** 默认选中：前 4 个数值测点（不足 4 个时全选数值测点） */
export function defaultSelection(candidates: PropertyCandidate[]): string[] {
  return candidates
    .filter((c) => c.numeric)
    .slice(0, DEFAULT_SELECTED)
    .map((c) => c.identifier)
}

/**
 * 初始选中：给定的测点（来自 ?props=）中存在于候选里的部分，保持给定顺序，最多 MAX_SELECTED 个；
 * 一个都不剩（或没有给定）时用默认选中。
 */
export function initialSelection(candidates: PropertyCandidate[], requested: readonly string[]): string[] {
  const known = new Set(candidates.map((c) => c.identifier))
  const picked = requested.filter((id) => known.has(id)).slice(0, MAX_SELECTED)
  return picked.length > 0 ? picked : defaultSelection(candidates)
}
