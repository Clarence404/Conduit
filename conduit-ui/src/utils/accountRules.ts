/**
 * 账号输入规则（与服务端一致，用于表单即时校验；服务端仍会再次校验）：用户名、密码、租户编码。
 * 各函数返回错误文案，合法时返回 null。
 */

/** 用户名：3～32 位，字母开头，只能含字母、数字、_、-、. */
export const USERNAME_PATTERN = /^[A-Za-z][A-Za-z0-9_.-]{2,31}$/
/** 租户编码：2～32 位，小写字母开头，只能含小写字母、数字和 - */
export const TENANT_CODE_PATTERN = /^[a-z][a-z0-9-]{1,31}$/

/** 校验用户名 */
export function usernameProblem(value: string): string | null {
  return USERNAME_PATTERN.test(value) ? null : '用户名须为 3～32 位，字母开头，只能包含字母、数字、_、-、.'
}

/** 校验密码：8～64 位，同时包含字母和数字 */
export function passwordProblem(value: string): string | null {
  const ok = value.length >= 8 && value.length <= 64 && /[A-Za-z]/.test(value) && /[0-9]/.test(value)
  return ok ? null : '密码须为 8～64 位，且同时包含字母和数字'
}

/** 校验租户编码 */
export function tenantCodeProblem(value: string): string | null {
  return TENANT_CODE_PATTERN.test(value) ? null : '租户编码须为 2～32 位，小写字母开头，只能包含小写字母、数字和 -'
}
