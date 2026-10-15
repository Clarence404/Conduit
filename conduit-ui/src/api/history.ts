/** 设备历史查询接口（V0.2-B） */
import { http } from './client'
import type { HistoryView } from './types'

export const historyApi = {
  /**
   * 查询设备若干测点在 [from, to) 内的历史曲线；服务端按点数自动选择原始或聚合模式。
   * @param properties 测点标识（1～8 个），按顺序以逗号拼接
   * @param from 起点毫秒（含）
   * @param to 终点毫秒（不含）
   */
  query: (deviceKey: string, properties: string[], from: number, to: number) =>
    http
      .get<HistoryView>(`/api/v1/devices/${encodeURIComponent(deviceKey)}/history`, {
        params: { properties: properties.join(','), from, to },
      })
      .then((r) => r.data),
}
