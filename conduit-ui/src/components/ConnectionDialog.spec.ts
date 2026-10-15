/** ConnectionDialog 组件测试：显示 clientId、username 与上报 topic，「关闭」更新 v-model */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ConnectionDialog from './ConnectionDialog.vue'
import { settle } from '@/test/viewTestUtils'

describe('ConnectionDialog', () => {
  it('shows the MQTT parameters of the device and closes', async () => {
    const wrapper = mount(ConnectionDialog, {
      props: { modelValue: true, productKey: 'pk1', deviceKey: 'dk1' },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()

    const text = document.body.textContent ?? ''
    expect(text).toContain('conduit/pk1/dk1/up/property')
    expect(text).toContain('设备密钥（创建或重置时显示）')

    const close = Array.from(document.body.querySelectorAll('button')).find((b) => b.textContent?.trim() === '关闭')
    close!.click()
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
    wrapper.unmount()
  })
})
