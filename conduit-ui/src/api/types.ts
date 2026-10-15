export interface PageView<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface UserView {
  username: string
  role: string
}

export interface TokenResponse {
  accessToken: string
  expiresIn: number
  user: UserView
}

export interface ProductView {
  productKey: string
  name: string
  description: string | null
  createdAt: string
}

export interface SaveProductRequest {
  name: string
  description: string | null
}

export type DeviceStatus = 'ENABLED' | 'DISABLED'

export interface DeviceView {
  deviceKey: string
  name: string
  productKey: string
  status: DeviceStatus
  online: boolean
  lastReportAt: number | null
  createdAt: string
}

export interface DeviceCreated {
  device: DeviceView
  deviceSecret: string
}

export interface SecretView {
  deviceKey: string
  deviceSecret: string
}

export interface PointView {
  identifier: string
  /** 物模型中的显示名称；未定义时为 null */
  name: string | null
  value: number | string | boolean
  ts: number
  unit: string | null
  decimals: number | null
  /** 枚举值对应的名称；非枚举或未定义时为 null */
  enumLabel: string | null
}

export interface LatestView {
  deviceKey: string
  values: PointView[]
}
