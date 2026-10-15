<script setup lang="ts">
/** 迷你趋势线（ECharts 小图）：points 变化时重绘；主题切换时按新主题重建实例；窗口尺寸变化时自适应；卸载时销毁。 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { echarts, type ECharts } from '@/utils/echarts'
import { chartTheme, useTheme } from '@/composables/useTheme'
import { sparklineOption } from '@/utils/sparkline'
import type { SparkPoint } from '@/utils/metricCards'

const props = defineProps<{ points: SparkPoint[] }>()

const el = ref<HTMLElement | null>(null)
const { isDark } = useTheme()
let chart: ECharts | null = null

/** 按当前数据绘制；实例不存在时按当前主题创建 */
function render() {
  if (!el.value) {
    return
  }
  chart ??= echarts.init(el.value, chartTheme(isDark.value))
  chart.setOption(sparklineOption(props.points), true)
}

/** 销毁实例（主题切换、卸载时） */
function dispose() {
  chart?.dispose()
  chart = null
}

/** 容器尺寸变化时自适应 */
function resize() {
  chart?.resize()
}

watch(() => props.points, render)
watch(isDark, () => {
  dispose()
  render()
})

onMounted(() => {
  render()
  window.addEventListener('resize', resize)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  dispose()
})
</script>

<template>
  <div ref="el" class="sparkline" />
</template>

<style scoped>
.sparkline {
  width: 100%;
  height: 36px;
}
</style>
