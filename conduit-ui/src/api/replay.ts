/**
 * 遥测重放的 REST 调用（/api/v1/platform/telemetry/replay，仅平台管理员）：
 * 查询当前任务状态、提交重放、请求取消。同一时刻只允许一个任务，因此三个调用都不带任务 id。
 */

import { http } from './client'
import type { ReplayRequest, ReplayStatus } from './types'

const PATH = '/api/v1/platform/telemetry/replay'

export const replayApi = {
  status: () => http.get<ReplayStatus>(PATH).then((r) => r.data),
  start: (req: ReplayRequest) => http.post<ReplayStatus>(PATH, req).then((r) => r.data),
  cancel: () => http.delete<ReplayStatus>(PATH).then((r) => r.data),
}
