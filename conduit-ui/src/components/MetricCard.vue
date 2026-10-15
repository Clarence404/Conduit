<script setup lang="ts">
/**
 * 单个测点的指标卡片：名称与标识；数值（按小数位）+ 单位，或状态量的文字标签（如「运行」）；
 * 数值测点有至少 2 个点时画最近 1 小时迷你趋势线；底部为相对采样时间。点击或回车发出 select（测点标识）。
 */
import type { MetricCardModel, SparkPoint } from '@/utils/metricCards'
import Sparkline from './Sparkline.vue'
import RelativeTime from './RelativeTime.vue'

defineProps<{ card: MetricCardModel; points: SparkPoint[] | null }>()
const emit = defineEmits<{ select: [identifier: string] }>()
</script>

<template>
  <div
    class="metric-card"
    role="button"
    tabindex="0"
    :title="`查看「${card.title}」的历史曲线`"
    @click="emit('select', card.identifier)"
    @keydown.enter="emit('select', card.identifier)"
  >
    <div class="head">
      <span class="title">{{ card.title }}</span>
      <span class="identifier">{{ card.identifier }}</span>
    </div>
    <div class="value">
      <el-tag v-if="card.state" class="state-tag" size="large" effect="light">{{ card.valueText }}</el-tag>
      <template v-else>
        <span class="number">{{ card.valueText }}</span>
        <span v-if="card.unit" class="unit">{{ card.unit }}</span>
      </template>
    </div>
    <div class="trend">
      <Sparkline v-if="!card.state && points && points.length > 1" :points="points" />
    </div>
    <div class="foot"><RelativeTime :value="card.ts" /></div>
  </div>
</template>

<style scoped>
.metric-card {
  display: flex;
  flex-direction: column;
  gap: var(--cd-space-1);
  padding: var(--cd-space-3) var(--cd-space-4);
  background: var(--cd-panel-bg);
  border: 1px solid var(--cd-panel-border);
  border-radius: var(--cd-radius);
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.metric-card:hover,
.metric-card:focus-visible {
  border-color: var(--el-color-primary);
  box-shadow: var(--el-box-shadow-light);
  outline: none;
}
.head {
  display: flex;
  justify-content: space-between;
  gap: var(--cd-space-2);
}
.title {
  overflow: hidden;
  color: var(--el-text-color-regular);
  font-size: 13px;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.identifier {
  flex-shrink: 0;
  color: var(--cd-text-muted);
  font-size: 12px;
}
.value {
  display: flex;
  align-items: baseline;
  min-height: 32px;
}
.number {
  color: var(--el-text-color-primary);
  font-size: 24px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.unit {
  margin-left: var(--cd-space-1);
  color: var(--cd-text-muted);
}
.trend {
  height: 36px;
}
.foot {
  color: var(--cd-text-muted);
  font-size: 12px;
}
</style>
