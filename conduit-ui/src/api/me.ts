/** 个人接口（/api/v1/me）：修改显示名称（返回最新的当前用户）、修改密码（返回新令牌，解除「需改密码」限制）。 */

import { http } from './client'
import type { ChangePasswordRequest, TokenResponse, UpdateMeRequest, UserView } from './types'

export const meApi = {
  update: (req: UpdateMeRequest) => http.put<UserView>('/api/v1/me', req).then((r) => r.data),
  changePassword: (req: ChangePasswordRequest) =>
    http.put<TokenResponse>('/api/v1/me/password', req).then((r) => r.data),
}
