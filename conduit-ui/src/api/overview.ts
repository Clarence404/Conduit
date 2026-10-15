/** 首页总览 REST 调用：GET /api/v1/overview（只读，按当前租户统计；在线相关字段可能为 null）。 */
import { http } from './client'
import type { OverviewView } from './types'

export const overviewApi = {
  get: () => http.get<OverviewView>('/api/v1/overview').then((r) => r.data),
}
