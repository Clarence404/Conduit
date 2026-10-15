/**
 * 列表请求序号：连续触发多次加载（如先改每页条数再翻页）时，
 * 只有最后发出的请求能写入结果，先发后到的旧响应会被丢弃。
 */
export function createRequestSeq() {
  let latest = 0
  return {
    next: () => ++latest,
    isLatest: (id: number) => id === latest,
  }
}
