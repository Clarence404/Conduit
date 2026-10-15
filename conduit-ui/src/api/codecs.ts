import { http } from './client'
import type { CodecView } from './types'

export const codecApi = {
  list: () => http.get<CodecView[]>('/api/v1/codecs').then((r) => r.data),
}
