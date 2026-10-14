import { http } from './client'
import type { TokenResponse } from './types'

export const authApi = {
  login: (username: string, password: string) =>
    http.post<TokenResponse>('/api/v1/auth/login', { username, password }).then((r) => r.data),
  refresh: () => http.post<TokenResponse>('/api/v1/auth/refresh').then((r) => r.data),
  logout: () => http.post('/api/v1/auth/logout').then(() => undefined),
}
