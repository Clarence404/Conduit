/** sparklineOption 的单元测试：单条折线、无坐标轴与提示框、透明背景 */
import { describe, expect, it } from 'vitest'
import { sparklineOption } from './sparkline'

interface SparkParts {
  backgroundColor: string
  xAxis: { type: string; show: boolean }
  yAxis: { show: boolean; scale: boolean }
  tooltip?: unknown
  series: Array<{ type: string; data: number[][]; symbol: string }>
}

describe('sparklineOption', () => {
  it('draws one line without axes on a transparent background', () => {
    const o = sparklineOption([
      [1, 2],
      [3, 4],
    ]) as unknown as SparkParts

    expect(o.backgroundColor).toBe('transparent')
    expect(o.xAxis).toMatchObject({ type: 'time', show: false })
    expect(o.yAxis).toMatchObject({ show: false, scale: true })
    expect(o.tooltip).toBeUndefined()
    expect(o.series).toHaveLength(1)
    expect(o.series[0]).toMatchObject({ type: 'line', symbol: 'none', data: [[1, 2], [3, 4]] })
  })
})
