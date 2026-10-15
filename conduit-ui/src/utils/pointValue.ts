import type { PointView } from '@/api/types'
import { formatValue } from './connection'

/** 「名称（标识）」；物模型中未定义时只显示标识 */
export function pointLabel(p: PointView): string {
  return p.name ? `${p.name}（${p.identifier}）` : p.identifier
}

/** 枚举显示标签；数值按小数位数格式化；有单位时追加单位 */
export function formatPointValue(p: PointView): string {
  if (p.enumLabel) {
    return p.enumLabel
  }
  const s =
    typeof p.value === 'number' && p.decimals !== null && p.decimals !== undefined
      ? p.value.toFixed(p.decimals)
      : formatValue(p.value)
  return p.unit ? `${s} ${p.unit}` : s
}
