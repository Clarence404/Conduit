/**
 * 物模型相关的前端逻辑：数据类型选项、属性表单与请求体的互相转换及校验、显示格式化。
 * 表单里数值都用字符串保存，转换时才解析并校验。
 */

import type { CodecView, DataType, PropertyRequest, PropertyView } from '@/api/types'

/** 数据类型下拉选项（值与服务端 DataType 一致）。 */
export const DATA_TYPES: { value: DataType; label: string }[] = [
  { value: 'DOUBLE', label: '浮点数' },
  { value: 'INT', label: '整数' },
  { value: 'BOOL', label: '布尔' },
  { value: 'TEXT', label: '文本' },
  { value: 'ENUM', label: '枚举' },
]

/** V0.2-A spec 2.3：INT/ENUM 存为 integer，与已有的浮点历史数据冲突 */
export const INT_ENUM_HINT = '已有数据的测点请选择浮点数'

/** 数据类型的中文名称；未知类型原样返回。 */
export function dataTypeLabel(type: DataType): string {
  return DATA_TYPES.find((t) => t.value === type)?.label ?? type
}

/** 属性表单中随数据类型显示或隐藏的字段。 */
export interface FieldVisibility {
  range: boolean
  decimals: boolean
  enumItems: boolean
  intHint: boolean
}

/**
 * 按数据类型决定表单字段显隐：数值型（DOUBLE/INT）显示范围与小数位，ENUM 显示枚举项，
 * INT/ENUM 额外显示「已有数据的测点请选择浮点数」提示。toPropertyRequest 也依赖它，隐藏字段一律提交为 null。
 */
export function fieldVisibility(type: DataType): FieldVisibility {
  const numeric = type === 'DOUBLE' || type === 'INT'
  return {
    range: numeric,
    decimals: numeric,
    enumItems: type === 'ENUM',
    intHint: type === 'INT' || type === 'ENUM',
  }
}

/** 弹窗表单：数值字段用字符串，便于区分「未填写」与 0 */
export interface PropertyForm {
  identifier: string
  name: string
  dataType: DataType
  unit: string
  minValue: string
  maxValue: string
  decimals: string
  enumItems: { value: string; label: string }[]
  description: string
}

/** 新增属性时的空表单，默认类型为浮点数。 */
export function emptyForm(): PropertyForm {
  return {
    identifier: '',
    name: '',
    dataType: 'DOUBLE',
    unit: '',
    minValue: '',
    maxValue: '',
    decimals: '',
    enumItems: [],
    description: '',
  }
}

const text = (n: number | null) => (n === null ? '' : String(n))

/** 把已有属性转成编辑表单：null 变空串，枚举值转字符串。 */
export function formFromProperty(p: PropertyView): PropertyForm {
  return {
    identifier: p.identifier,
    name: p.name,
    dataType: p.dataType,
    unit: p.unit ?? '',
    minValue: text(p.minValue),
    maxValue: text(p.maxValue),
    decimals: text(p.decimals),
    enumItems: (p.enumItems ?? []).map((i) => ({ value: String(i.value), label: i.label })),
    description: p.description ?? '',
  }
}

/** 去首尾空白，空串视为未填写返回 null。 */
function blankToNull(s: string): string | null {
  const t = s.trim()
  return t ? t : null
}

/** 解析可选数字：空白返回 null，非数字抛 Error（message 以 label 开头，可直接提示用户）。 */
function optionalNumber(s: string, label: string): number | null {
  const t = s.trim()
  if (!t) {
    return null
  }
  const n = Number(t)
  if (!Number.isFinite(n)) {
    throw new Error(`${label}必须是数字`)
  }
  return n
}

/** 解析可选整数：在 optionalNumber 基础上要求为整数，否则抛 Error。 */
function optionalInteger(s: string, label: string): number | null {
  const n = optionalNumber(s, label)
  if (n !== null && !Number.isInteger(n)) {
    throw new Error(`${label}必须是整数`)
  }
  return n
}

/** 表单转请求：隐藏的字段一律为 null；格式错误抛出 Error（message 可直接提示用户） */
export function toPropertyRequest(f: PropertyForm): PropertyRequest {
  const v = fieldVisibility(f.dataType)
  return {
    identifier: f.identifier.trim(),
    name: f.name.trim(),
    dataType: f.dataType,
    unit: blankToNull(f.unit),
    minValue: v.range ? optionalNumber(f.minValue, '最小值') : null,
    maxValue: v.range ? optionalNumber(f.maxValue, '最大值') : null,
    decimals: v.decimals ? optionalInteger(f.decimals, '小数位数') : null,
    enumItems: v.enumItems
      ? f.enumItems.map((item, i) => {
          const value = optionalInteger(item.value, `第 ${i + 1} 个枚举值`)
          if (value === null) {
            throw new Error(`第 ${i + 1} 个枚举值不能为空`)
          }
          return { value, label: item.label.trim() }
        })
      : null,
    description: blankToNull(f.description),
  }
}

/** 取值范围的显示文案：两端都有为「min ~ max」，只有一端为「≥ min」或「≤ max」，都没有为「—」。 */
export function formatRange(min: number | null, max: number | null): string {
  if (min !== null && max !== null) {
    return `${min} ~ ${max}`
  }
  if (min !== null) {
    return `≥ ${min}`
  }
  if (max !== null) {
    return `≤ ${max}`
  }
  return '—'
}

/** 解码器的显示名称；列表里找不到（如尚未加载）时退回原始名称。 */
export function codecLabel(codecs: CodecView[], name: string): string {
  return codecs.find((c) => c.name === name)?.displayName ?? name
}
