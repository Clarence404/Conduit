/** accountRules 单元测试：用户名、密码、租户编码的合法与非法取值（与服务端规则一致） */
import { describe, expect, it } from 'vitest'
import { passwordProblem, tenantCodeProblem, usernameProblem } from './accountRules'

describe('accountRules', () => {
  it('checks usernames', () => {
    for (const ok of ['abc', 'Admin_01', 'zhang.san', 'a-b', 'a'.repeat(32)]) {
      expect(usernameProblem(ok)).toBeNull()
    }
    for (const bad of ['', 'ab', '1abc', '_abc', 'abc def', '张三丰', 'a'.repeat(33)]) {
      expect(usernameProblem(bad)).toContain('用户名须为 3～32 位')
    }
  })

  it('checks passwords', () => {
    for (const ok of ['abcdefg1', 'Passw0rd!', '1234567a', 'a'.repeat(63) + '1']) {
      expect(passwordProblem(ok)).toBeNull()
    }
    for (const bad of ['', 'abc1234', 'abcdefgh', '12345678', 'a'.repeat(64) + '1']) {
      expect(passwordProblem(bad)).toBe('密码须为 8～64 位，且同时包含字母和数字')
    }
  })

  it('checks tenant codes', () => {
    for (const ok of ['default', 'ab', 'east-1', 'a' + 'b'.repeat(31)]) {
      expect(tenantCodeProblem(ok)).toBeNull()
    }
    for (const bad of ['', 'a', '1abc', 'Abc', 'a_b', '-ab', 'a'.repeat(33), '华东']) {
      expect(tenantCodeProblem(bad)).toContain('租户编码须为 2～32 位')
    }
  })
})
