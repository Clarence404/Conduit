/** useBreadcrumb 的单元测试：面包屑生成（最后一级不可点击）、浏览器标题、详情页设置对象名称与卸载时清除 */
import { describe, expect, it } from 'vitest'
import { defineComponent } from 'vue'
import { mount } from '@vue/test-utils'
import type { BreadcrumbItem } from '@/router/meta'
import { createTestRouter } from '@/test/viewTestUtils'
import { buildBreadcrumb, documentTitle, useBreadcrumb, useCurrentPage } from './useBreadcrumb'

describe('buildBreadcrumb', () => {
  it('appends the object name and drops the link of the last item', () => {
    const base: BreadcrumbItem[] = [{ label: '设备接入' }, { label: '设备', to: { name: 'devices' } }]

    expect(buildBreadcrumb(base, '1号楼配电室 电表01')).toEqual([
      { label: '设备接入' },
      { label: '设备', to: { name: 'devices' } },
      { label: '1号楼配电室 电表01' },
    ])
    expect(buildBreadcrumb(base, null)).toEqual([{ label: '设备接入' }, { label: '设备' }])
    expect(buildBreadcrumb(undefined, null)).toEqual([])
  })
})

describe('documentTitle', () => {
  it('prefers the object name, then the page name, then the app name', () => {
    expect(documentTitle('设备详情', '1号楼配电室 电表01')).toBe('1号楼配电室 电表01 · Conduit')
    expect(documentTitle('产品', null)).toBe('产品 · Conduit')
    expect(documentTitle(undefined, null)).toBe('Conduit')
  })
})

describe('useBreadcrumb and useCurrentPage', () => {
  it('shows the name set by a detail page and clears it when the page unmounts', async () => {
    const router = createTestRouter()
    await router.push('/devices/dk1')
    await router.isReady()
    const Reader = defineComponent({
      setup: () => useCurrentPage(),
      template: '<div />',
    })
    const Setter = defineComponent({
      setup() {
        useBreadcrumb().setName('1号楼配电室 电表01')
        return {}
      },
      template: '<div />',
    })
    const reader = mount(Reader, { global: { plugins: [router] } })
    const setter = mount(Setter, { global: { plugins: [router] } })
    const vm = reader.vm as unknown as { breadcrumb: BreadcrumbItem[]; title: string }

    expect(vm.breadcrumb.map((i) => i.label)).toEqual(['设备接入', '设备', '1号楼配电室 电表01'])
    expect(vm.title).toBe('1号楼配电室 电表01 · Conduit')

    setter.unmount()
    expect(vm.breadcrumb.map((i) => i.label)).toEqual(['设备接入', '设备'])
    expect(vm.title).toBe('设备详情 · Conduit')
    reader.unmount()
  })

  it('ignores a name set after the page was left', async () => {
    const router = createTestRouter()
    await router.push('/products/pk1')
    await router.isReady()
    let setName: (name: string | null) => void = () => {}
    const Setter = defineComponent({
      setup() {
        setName = useBreadcrumb().setName
        return {}
      },
      template: '<div />',
    })
    const Reader = defineComponent({ setup: () => useCurrentPage(), template: '<div />' })
    const setter = mount(Setter, { global: { plugins: [router] } })
    const reader = mount(Reader, { global: { plugins: [router] } })

    setter.unmount()
    setName('迟到的名称')

    expect((reader.vm as unknown as { title: string }).title).toBe('产品详情 · Conduit')
    reader.unmount()
  })
})
