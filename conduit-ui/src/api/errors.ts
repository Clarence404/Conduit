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
