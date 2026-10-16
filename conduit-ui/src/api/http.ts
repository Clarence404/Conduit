/**
 * Axios 实例工厂：统一附带 access token、401 时自动刷新并重试、识别「需要先修改密码」的 403。
 * 不直接依赖 store 与路由，token 的读写、刷新结果与登录失效处理由调用方通过 TokenHolder 注入。
 */

import axios, { isAxiosError, type AxiosError, type AxiosInstance } from 'axios'
import type { TokenResponse } from './types'

declare module 'axios' {
  interface AxiosRequestConfig {
    /** 已经因 401 重试过一次，不再刷新 */
    _retried?: boolean
  }
}

/** token 的存取与各类回调，由使用方（api/client.ts）实现，使 http.ts 不依赖 store 和路由。 */
export interface TokenHolder {
  getToken(): string | null
  setToken(token: string | null): void
  onAuthFailed(): void
  /** 自动刷新成功后收到完整响应（含最新的用户信息，例如代入的租户已停用时退回平台管理员身份） */
  onRefreshed?(res: TokenResponse): void
  /** 任一接口返回 403 且 code 为 PASSWORD_CHANGE_REQUIRED 时调用（弹出强制修改密码对话框） */
  onPasswordChangeRequired?(): void
}

/** 刷新 token 的接口地址 */
export const REFRESH_URL = '/api/v1/auth/refresh'
/** 需要先修改密码时服务端 ProblemDetail 的 code */
export const PASSWORD_CHANGE_REQUIRED = 'PASSWORD_CHANGE_REQUIRED'
/** 公开的认证接口：不带 token，也不触发自动刷新（退出租户等其他 /api/v1/auth/ 接口需要 token） */
const PUBLIC_AUTH_URLS = new Set(['/api/v1/auth/login', REFRESH_URL, '/api/v1/auth/logout'])

/** 是否为公开的认证接口 */
function isPublicAuth(url: string | undefined): boolean {
  return url !== undefined && PUBLIC_AUTH_URLS.has(url)
}

/** 是否为服务端明确拒绝登录态（401/403）。网络错误、5xx 不算，避免服务端抖动时把用户登出。 */
function isAuthRejection(error: unknown): boolean {
  const status = isAxiosError(error) ? error.response?.status : undefined
  return status === 401 || status === 403
}

/** 是否为「需要先修改密码」的 403 */
function isPasswordChangeRequired(error: AxiosError): boolean {
  const data = error.response?.data as { code?: unknown } | undefined
  return error.response?.status === 403 && data?.code === PASSWORD_CHANGE_REQUIRED
}

/**
 * 创建带鉴权的 Axios 实例：自动附带 access token；遇到 401 时用 refresh Cookie 换新 token 并重试一次，
 * 并发的多个 401 共享同一次刷新，刷新成功后把完整响应交给 onRefreshed。刷新失败（401/403）则清空 token 并通知调用方（跳转登录）。
 * 任一 403 PASSWORD_CHANGE_REQUIRED 通知 onPasswordChangeRequired，错误照常抛给调用方。
 */
export function createHttp(holder: TokenHolder): AxiosInstance {
  const http = axios.create({ withCredentials: true })
  let refreshing: Promise<string> | null = null

  function refreshToken(): Promise<string> {
    refreshing ??= http
      .post<TokenResponse>(REFRESH_URL, null, { _retried: true })
      .then((res) => {
        holder.setToken(res.data.accessToken)
        holder.onRefreshed?.(res.data)
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
    if (token && !isPublicAuth(config.url)) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  })

  http.interceptors.response.use(undefined, async (error: AxiosError) => {
    if (isPasswordChangeRequired(error)) {
      holder.onPasswordChangeRequired?.()
    }
    const config = error.config
    if (error.response?.status !== 401 || !config || config._retried || isPublicAuth(config.url)) {
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
