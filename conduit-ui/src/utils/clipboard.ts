/** 剪贴板工具：优先使用 Clipboard API，不可用时退回 execCommand。 */

/**
 * 写入剪贴板，返回是否成功。
 * 演示环境通过 http + 非 localhost 访问，属于非安全上下文，没有 navigator.clipboard；
 * 这时（或 clipboard API 报错时）退回到隐藏 textarea + execCommand('copy')。
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 继续尝试旧方式
  }
  return legacyCopy(text)
}

/**
 * 旧式复制：用屏幕外的只读 textarea 选中文本后 execCommand('copy')，结束后移除并恢复原焦点。
 * 成功返回 true；抛错或浏览器不支持时返回 false。
 */
function legacyCopy(text: string): boolean {
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.top = '0'
  textarea.style.left = '0'
  textarea.style.opacity = '0'
  const previousFocus = document.activeElement as HTMLElement | null
  document.body.appendChild(textarea)
  try {
    textarea.focus()
    textarea.select()
    textarea.setSelectionRange(0, text.length)
    return typeof document.execCommand === 'function' && document.execCommand('copy')
  } catch {
    return false
  } finally {
    textarea.remove()
    previousFocus?.focus?.()
  }
}
