/** AppearancePanel 组件测试：显示当前偏好，切换主题与侧栏风格后写入 store 与 localStorage，两组单选有无障碍名称并关联可见标签 */
import { beforeEach, describe, expect, it } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import ElementPlus, { ElRadioGroup } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import AppearancePanel from './AppearancePanel.vue'
import { UI_STORAGE_KEY, useUiStore } from '@/stores/ui'

describe('AppearancePanel', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('shows the current preferences and saves changes', async () => {
    const wrapper = mount(AppearancePanel, { global: { plugins: [ElementPlus] } })
    const groups = wrapper.findAllComponents(ElRadioGroup)

    expect(wrapper.text()).toContain('跟随系统')
    expect(groups[0].props('modelValue')).toBe('system')
    expect(groups[1].props('modelValue')).toBe('dark')

    groups[0].vm.$emit('change', 'dark')
    groups[1].vm.$emit('change', 'light')
    await nextTick()

    const ui = useUiStore()
    expect(ui.theme).toBe('dark')
    expect(ui.sidebarStyle).toBe('light')
    expect(JSON.parse(localStorage.getItem(UI_STORAGE_KEY)!)).toMatchObject({ theme: 'dark', sidebarStyle: 'light' })
    wrapper.unmount()
  })
  it('names both radio groups and associates them with their visible labels', () => {
    const wrapper = mount(AppearancePanel, { global: { plugins: [ElementPlus] } })
    const groups = wrapper.findAll('[role="radiogroup"]')

    expect(groups.map((g) => g.attributes('aria-label'))).toEqual(['主题', '侧栏风格'])
    groups.forEach((g) => {
      const label = wrapper.find(`#${g.attributes('aria-labelledby')}`)
      expect(label.exists()).toBe(true)
      expect(label.text()).toBe(g.attributes('aria-label')!.slice(0, 2))
    })
    wrapper.unmount()
  })
})
