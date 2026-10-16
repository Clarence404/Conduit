/** ResetPasswordDialog 组件测试：密码不合规或两次不一致时不调用 save；成功后关闭；save 失败时把原因显示在对话框内 */
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ResetPasswordDialog from './ResetPasswordDialog.vue'

type Vm = { form: { password: string; confirm: string }; error: string; submit(): Promise<void> }

function mountDialog(save: (password: string) => Promise<void>) {
  const wrapper = mount(ResetPasswordDialog, {
    props: { modelValue: true, username: 'east-admin', save },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
  return { wrapper, vm: wrapper.vm as unknown as Vm }
}

describe('ResetPasswordDialog', () => {
  it('checks the password before saving', async () => {
    const save = vi.fn().mockResolvedValue(undefined)
    const { wrapper, vm } = mountDialog(save)

    vm.form.password = 'weak'
    vm.form.confirm = 'weak'
    await vm.submit()
    expect(vm.error).toBe('密码须为 8～64 位，且同时包含字母和数字')

    vm.form.password = 'Reset-pass-9'
    vm.form.confirm = 'Reset-pass-8'
    await vm.submit()
    expect(vm.error).toBe('两次输入的密码不一致')
    expect(save).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('saves and closes', async () => {
    const save = vi.fn().mockResolvedValue(undefined)
    const { wrapper, vm } = mountDialog(save)

    vm.form.password = 'Reset-pass-9'
    vm.form.confirm = 'Reset-pass-9'
    await vm.submit()

    expect(save).toHaveBeenCalledWith('Reset-pass-9')
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
    wrapper.unmount()
  })

  it('shows the server error', async () => {
    const { wrapper, vm } = mountDialog(vi.fn().mockRejectedValue(new Error('只能重置租户管理员的密码')))

    vm.form.password = 'Reset-pass-9'
    vm.form.confirm = 'Reset-pass-9'
    await vm.submit()

    expect(vm.error).toBe('只能重置租户管理员的密码')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    wrapper.unmount()
  })

  it('clears the password fields and the error when closed', async () => {
    const { wrapper, vm } = mountDialog(vi.fn().mockRejectedValue(new Error('只能重置租户管理员的密码')))
    vm.form.password = 'Reset-pass-9'
    vm.form.confirm = 'Reset-pass-9'
    await vm.submit()
    expect(vm.error).not.toBe('')

    await wrapper.setProps({ modelValue: false })

    expect(vm.form.password).toBe('')
    expect(vm.form.confirm).toBe('')
    expect(vm.error).toBe('')
    wrapper.unmount()
  })
})
