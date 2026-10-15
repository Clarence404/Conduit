/** 登录后跳转地址的校验：只接受站内相对路径（以单个 / 开头），否则返回 fallback（默认首页），防止开放重定向。 */
export function safeRedirect(value: unknown, fallback = '/home'): string {
  if (typeof value !== 'string' || !value.startsWith('/')) {
    return fallback
  }
  if (value.startsWith('//') || value.startsWith('/\\')) {
    return fallback
  }
  return value
}
