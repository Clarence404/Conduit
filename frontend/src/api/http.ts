import axios, { type AxiosError, type AxiosInstance } from 'axios'

declare module 'axios' {
  interface AxiosRequestConfig {
    /** 已经因 401 重试过一次，不再刷新 */
    _retried?: boolean
  }
}

export interface TokenHolder {
  getToken(): string | null
  setToken(token: string | null): void
  onAuthFailed(): void
}

export const REFRESH_URL = '/api/v1/auth/refresh'
const AUTH_PREFIX = '/api/v1/auth/'

/**
 * 创建带鉴权的 Axios 实例：自动附带 access token；遇到 401 时用 refresh Cookie 换新 token 并重试一次，
 * 并发的多个 401 共享同一次刷新。刷新失败则清空 token 并通知调用方（跳转登录）。
 */
export function createHttp(holder: TokenHolder): AxiosInstance {
  const http = axios.create({ withCredentials: true })
  let refreshing: Promise<string> | null = null

  function refreshToken(): Promise<string> {
    refreshing ??= http
      .post<{ accessToken: string }>(REFRESH_URL, null, { _retried: true })
      .then((res) => {
        holder.setToken(res.data.accessToken)
        return res.data.accessToken
      })
      .finally(() => {
        refreshing = null
      })
    return refreshing
  }

  http.interceptors.request.use((config) => {
    const token = holder.getToken()
    if (token && !config.url?.startsWith(AUTH_PREFIX)) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  })

  http.interceptors.response.use(undefined, async (error: AxiosError) => {
    const config = error.config
    if (error.response?.status !== 401 || !config || config._retried || config.url?.startsWith(AUTH_PREFIX)) {
      return Promise.reject(error)
    }
    config._retried = true
    try {
      const token = await refreshToken()
      config.headers.Authorization = `Bearer ${token}`
      return http(config)
    } catch (refreshError) {
      holder.setToken(null)
      holder.onAuthFailed()
      return Promise.reject(refreshError)
    }
  })

  return http
}
