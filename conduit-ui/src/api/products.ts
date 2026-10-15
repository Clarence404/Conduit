import { http } from './client'
import type { PageView, ProductView, SaveProductRequest } from './types'

export const productApi = {
  list: (page: number, size: number) =>
    http.get<PageView<ProductView>>('/api/v1/products', { params: { page, size } }).then((r) => r.data),
  get: (productKey: string) =>
    http.get<ProductView>(`/api/v1/products/${encodeURIComponent(productKey)}`).then((r) => r.data),
  create: (req: SaveProductRequest) => http.post<ProductView>('/api/v1/products', req).then((r) => r.data),
  update: (productKey: string, req: SaveProductRequest) =>
    http.put<ProductView>(`/api/v1/products/${encodeURIComponent(productKey)}`, req).then((r) => r.data),
  remove: (productKey: string) =>
    http.delete(`/api/v1/products/${encodeURIComponent(productKey)}`).then(() => undefined),
}
