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
  /**
   * 当前会话代数（每次签发或清空会话时递增）。刷新开始时记下代数，响应回来时若代数已变（期间进入/退出租户、改密码、登出），
   * 说明有更新的会话，这次刷新的结果（成功或失败）一律作废，避免覆盖新会话或把新会话误登出。不提供则不做此检查。
   */
  getGeneration?(): number
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

/** 是否为「需要先修改密码」的 403（登录态仍然有效，只是要先改密码） */
function isPasswordChangeRequired(error: AxiosError): boolean {
  const data = error.response?.data as { code?: unknown } | undefined
  return error.response?.status === 403 && data?.code === PASSWORD_CHANGE_REQUIRED
}

/**
 * 是否为服务端明确拒绝登录态（401/403）。网络错误、5xx 不算，避免服务端抖动时把用户登出；
 * 403 PASSWORD_CHANGE_REQUIRED 也不算（登录态有效，由调用方弹出改密码对话框），即使服务端没有豁免刷新接口也不会误登出。
 */
function isAuthRejection(error: unknown): boolean {
  if (!isAxiosError(error)) return false
  if (isPasswordChangeRequired(error)) return false
  const status = error.response?.status
  return status === 401 || status === 403
}

/**
 * 创建带鉴权的 Axios 实例：自动附带 access token；遇到 401 时用 refresh Cookie 换新 token 并重试一次，
 * 并发的多个 401 共享同一次刷新（若刷新期间会话已被更新的签发取代则丢弃其结果），刷新成功后把完整响应交给 onRefreshed。刷新失败（401/403）则清空 token 并通知调用方（跳转登录）。
 * 任一 403 PASSWORD_CHANGE_REQUIRED 通知 onPasswordChangeRequired，错误照常抛给调用方。
 */
export function createHttp(holder: TokenHolder): AxiosInstance {
  const http = axios.create({ withCredentials: true })
  let refreshing: Promise<string> | null = null

  function refreshToken(): Promise<string> {
    const startGeneration = holder.getGeneration?.()
    /** 刷新期间会话已被更新的签发（进入/退出租户、改密码等）所取代 */
    const superseded = () => holder.getGeneration !== undefined && holder.getGeneration() !== startGeneration
    refreshing ??= http
      .post<TokenResponse>(REFRESH_URL, null, { _retried: true })
      .then((res) => {
        if (superseded()) {
          // 过期的刷新结果不能覆盖新会话：丢弃，改用当前会话的 token 重试
          return holder.getToken() ?? ''
        }
        holder.setToken(res.data.accessToken)
        holder.onRefreshed?.(res.data)
        return res.data.accessToken
      })
      .catch((refreshError: unknown) => {
        // 只在共享刷新失败时处理一次；仅 401/403 视为登录失效，网络错误/5xx 不登出
        if (isAuthRejection(refreshError) && !superseded()) {
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
