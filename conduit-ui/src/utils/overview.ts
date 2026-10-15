/** 首页总览的显示文案：死信原因的中文名称与摘要、在线率。 */
import type { DeadSummary } from '@/api/types'

const REASON_LABELS: Record<string, string> = {
  FIELD_REJECTED: '测点被剔除',
  MALFORMED_ENVELOPE: '报文结构错误',
  MALFORMED_JSON: 'JSON 格式错误',
  INVALID_PARAMS: '参数不合法',
  UNKNOWN_DEVICE: '未知设备',
  DEVICE_DISABLED: '设备已禁用',
  PRODUCT_MISMATCH: '产品不匹配',
  TS_OUT_OF_RANGE: '时间戳越界',
  FIELD_TYPE_CONFLICT: '测点类型冲突',
  UNSUPPORTED_TYPE: '不支持的消息类型',
  MAX_DELIVERY_EXCEEDED: '超过投递次数',
  PROCESSING_ERROR: '处理出错',
  UNKNOWN_CODEC: '未知解码器',
}

/** 死信原因的中文名称；未知原因原样显示 */
export function reasonLabel(reason: string): string {
  return REASON_LABELS[reason] ?? reason
}

/**
 * 死信摘要：按条数从多到少（相同时按原因代码）列出「原因 条数」，以「 · 」连接；0 条为「无」；null（服务端读不到死信流）为「暂不可用」。
 */
export function deadText(dead: DeadSummary | null): string {
  if (!dead) {
    return '暂不可用'
  }
  if (dead.total === 0) {
    return '无'
  }
  return Object.entries(dead.byReason)
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .map(([reason, count]) => `${reasonLabel(reason)} ${count}`)
    .join(' · ')
}

/**
 * 在线率（整数百分比）：在线数未知时为 null；没有设备时为 0。
 * @param online 在线设备数（可能为 null）
 * @param total 设备总数
 */
export function onlineRate(online: number | null, total: number): number | null {
  if (online === null) {
    return null
  }
  if (total <= 0) {
    return 0
  }
  return Math.round((online / total) * 100)
}
