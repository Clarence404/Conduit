/**
 * ECharts 按需引入：只注册历史曲线用到的折线图、网格、提示框、工具栏（框选缩放）、区域缩放、标记区域组件和 Canvas 渲染器，
 * 不引入 vue-echarts。组件测试中整体 mock 本模块。
 */
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import {
  DataZoomComponent,
  GridComponent,
  MarkAreaComponent,
  ToolboxComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  LineChart,
  GridComponent,
  TooltipComponent,
  ToolboxComponent,
  DataZoomComponent,
  MarkAreaComponent,
  CanvasRenderer,
])

/** 一个图表实例 */
export type ECharts = echarts.EChartsType

export { echarts }
