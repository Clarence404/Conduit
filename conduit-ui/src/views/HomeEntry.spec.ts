/** HomeEntry 组件测试：未代入的平台管理员看到平台首页；租户用户与代入的平台管理员看到租户首页 */
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import HomeEntry from './HomeEntry.vue'
import type { UserView } from '@/api/types'
import { createTestPinia } from '@/test/viewTestUtils'

vi.mock('./HomeView.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { default: defineComponent({ render: () => h('div', { class: 'tenant-home' }) }) }
})
vi.mock('./PlatformHomeView.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return { default: defineComponent({ render: () => h('div', { class: 'platform-home' }) }) }
})

function mountAs(user: Partial<UserView>) {
  return mount(HomeEntry, { global: { plugins: [createTestPinia(user)] } })
}

describe('HomeEntry', () => {
  it('shows the platform home to a platform admin', () => {
    const wrapper = mountAs({ username: 'sysadmin', role: 'PLATFORM_ADMIN', tenantCode: null, tenantName: null })

    expect(wrapper.find('.platform-home').exists()).toBe(true)
    expect(wrapper.find('.tenant-home').exists()).toBe(false)
  })

  it('shows the tenant home to tenant users and to an impersonating platform admin', () => {
    expect(mountAs({ role: 'TENANT_VIEWER' }).find('.tenant-home').exists()).toBe(true)
    expect(mountAs({ username: 'sysadmin', impersonatedBy: 'sysadmin' }).find('.tenant-home').exists()).toBe(true)
  })
})
