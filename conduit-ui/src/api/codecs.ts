/** 解码器 REST 调用：列出服务端注册的 PayloadDecoder（产品创建/编辑时选择）。 */

import { http } from './client'
import type { CodecView } from './types'

export const codecApi = {
  list: () => http.get<CodecView[]>('/api/v1/codecs').then((r) => r.data),
}
