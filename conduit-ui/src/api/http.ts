/**
 * Axios 实例工厂：统一附带 access token、401 时自动刷新并重试。
 * 不直接依赖 store 与路由，token 的读写和登录失效处理由调用方通过 TokenHolder 注入。
 */

import axios, { isAxiosError, type AxiosError, type AxiosInstance } from 'axios'

declare module 'axios' {
  interface AxiosRequestConfig {
    /** 已经因 401 重试过一次，不再刷新 */
    _retried?: boolean
  }
}

/** token 的存取与登录失效回调，由使用方（api/client.ts）实现，使 http.ts 不依赖 store 和路由。 */
export interface TokenHolder {
  getToken(): string | null
  setToken(token: string | null): void
  onAuthFailed(): void
}

/** 刷新 token 的接口地址；以 AUTH_PREFIX 开头的请求不带 token，也不会触发自动刷新。 */
export const REFRESH_URL = '/api/v1/auth/refresh'
const AUTH_PREFIX = '/api/v1/auth/'

/** 是否为服务端明确拒绝登录态（401/403）。网络错误、5xx 不算，避免服务端抖动时把用户登出。 */
function isAuthRejection(error: unknown): boolean {
  const status = isAxiosError(error) ? error.response?.status : undefined
  return status === 401 || status === 403
}

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
      .catch((refreshError: unknown) => {
        // 只在共享刷新失败时处理一次；仅 401/403 视为登录失效，网络错误/5xx 不登出
        if (isAuthRejection(refreshError)) {
          holder.setToken(null)
          holder.onAuthFailed()
        }
        throw refreshError
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
      return Promise.reject(isAuthRejection(refreshError) ? refreshError : error)
    }
  })

  return http
}
