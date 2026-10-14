/** 只接受站内相对路径（以单个 / 开头），否则返回 fallback，防止登录后开放重定向。 */
export function safeRedirect(value: unknown, fallback = '/devices'): string {
  if (typeof value !== 'string' || !value.startsWith('/')) {
    return fallback
  }
  if (value.startsWith('//') || value.startsWith('/\\')) {
    return fallback
  }
  return value
}
