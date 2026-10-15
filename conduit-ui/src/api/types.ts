/** 与服务端 REST 接口对应的数据类型（响应视图与请求体），字段含义以服务端 DTO 为准。 */

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
  codec: string
  propertyCount: number
  createdAt: string
}

export interface SaveProductRequest {
  name: string
  description: string | null
  /** 新建时缺省为 standard-json；修改时不传则保持不变 */
  codec?: string
}

export interface CodecView {
  name: string
  displayName: string
}

export type DataType = 'DOUBLE' | 'INT' | 'BOOL' | 'TEXT' | 'ENUM'

export interface EnumItem {
  value: number
  label: string
}

export interface PropertyRequest {
  identifier: string
  name: string
  dataType: DataType
  unit: string | null
  minValue: number | null
  maxValue: number | null
  decimals: number | null
  enumItems: EnumItem[] | null
  description: string | null
}

export interface PropertyView extends PropertyRequest {
  createdAt: string
  updatedAt: string
}

export interface ThingModelDocument {
  properties: PropertyRequest[]
}

export type ImportMode = 'merge' | 'replace'

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
