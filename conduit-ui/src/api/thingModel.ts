import { http } from './client'
import type { ImportMode, PropertyRequest, PropertyView, ThingModelDocument } from './types'

const base = (productKey: string) => `/api/v1/products/${encodeURIComponent(productKey)}`
const property = (productKey: string, identifier: string) =>
  `${base(productKey)}/properties/${encodeURIComponent(identifier)}`

export const thingModelApi = {
  list: (productKey: string) =>
    http.get<PropertyView[]>(`${base(productKey)}/properties`).then((r) => r.data),
  create: (productKey: string, req: PropertyRequest) =>
    http.post<PropertyView>(`${base(productKey)}/properties`, req).then((r) => r.data),
  update: (productKey: string, identifier: string, req: PropertyRequest) =>
    http.put<PropertyView>(property(productKey, identifier), req).then((r) => r.data),
  remove: (productKey: string, identifier: string) =>
    http.delete(property(productKey, identifier)).then(() => undefined),
  exportModel: (productKey: string) =>
    http.get<ThingModelDocument>(`${base(productKey)}/thing-model`).then((r) => r.data),
  /** document 为用户选择的文件内容，原样提交，由服务端整体校验 */
  importModel: (productKey: string, document: unknown, mode: ImportMode) =>
    http.put<PropertyView[]>(`${base(productKey)}/thing-model`, document, { params: { mode } }).then((r) => r.data),
}
