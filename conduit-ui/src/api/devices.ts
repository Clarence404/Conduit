import { http } from './client'
import type { DeviceCreated, DeviceStatus, DeviceView, LatestView, PageView, SecretView } from './types'

export interface DeviceQuery {
  productKey?: string
  keyword?: string
  page: number
  size: number
}

const path = (deviceKey: string) => `/api/v1/devices/${encodeURIComponent(deviceKey)}`

export const deviceApi = {
  list: (query: DeviceQuery) =>
    http.get<PageView<DeviceView>>('/api/v1/devices', { params: query }).then((r) => r.data),
  get: (deviceKey: string) => http.get<DeviceView>(path(deviceKey)).then((r) => r.data),
  create: (productKey: string, name: string) =>
    http.post<DeviceCreated>('/api/v1/devices', { productKey, name }).then((r) => r.data),
  update: (deviceKey: string, name: string, status: DeviceStatus) =>
    http.put<DeviceView>(path(deviceKey), { name, status }).then((r) => r.data),
  resetSecret: (deviceKey: string) =>
    http.post<SecretView>(`${path(deviceKey)}/reset-secret`).then((r) => r.data),
  remove: (deviceKey: string) => http.delete(path(deviceKey)).then(() => undefined),
  latest: (deviceKey: string) => http.get<LatestView>(`${path(deviceKey)}/latest`).then((r) => r.data),
}
