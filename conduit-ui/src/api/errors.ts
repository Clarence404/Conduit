/** 把接口错误（Axios 错误、ProblemDetail）转换成可直接展示给用户的中文文案与逐项错误列表。 */

import { isAxiosError } from 'axios'

/** 从接口错误中提取给用户看的文案：优先 ProblemDetail.detail。 */
export function problemMessage(error: unknown): string {
  if (isAxiosError(error)) {
    const detail = (error.response?.data as { detail?: unknown } | undefined)?.detail
    if (typeof detail === 'string' && detail) {
      return detail
    }
    if (error.response) {
      return `请求失败（${error.response.status}）`
    }
    return '无法连接服务器'
  }
  return error instanceof Error ? error.message : '未知错误'
}

/** ProblemDetail.errors 中的一项：identifier 为出错的属性标识（可能没有），message 为错误原因。 */
export interface ProblemFieldError {
  identifier: string | null
  message: string
}

/** ProblemDetail 中的 errors 列表（如物模型导入的逐项错误）；没有时返回空数组。 */
export function problemErrors(error: unknown): ProblemFieldError[] {
  if (!isAxiosError(error)) {
    return []
  }
  const errors = (error.response?.data as { errors?: unknown } | undefined)?.errors
  if (!Array.isArray(errors)) {
    return []
  }
  return errors
    .filter((e): e is { identifier?: unknown; message?: unknown } => typeof e === 'object' && e !== null)
    .map((e) => ({
      identifier: typeof e.identifier === 'string' ? e.identifier : null,
      message: typeof e.message === 'string' ? e.message : '',
    }))
}
