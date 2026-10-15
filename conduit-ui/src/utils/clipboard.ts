/** 写入剪贴板，返回是否成功；非安全上下文（http + 非 localhost）没有 clipboard API */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (!navigator.clipboard) {
      return false
    }
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    return false
  }
}
