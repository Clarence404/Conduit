<script setup lang="ts">
/**
 * 设备「最新值」标签页内容：卡片 / 表格两种视图（选择存入外观偏好）。卡片视图每个测点一张 MetricCard，
 * 前 8 个数值测点带最近 1 小时迷你趋势线：一次历史查询取得，每 60 秒刷新（页面隐藏时暂停；表格视图和组件卸载后不再请求）。
 * 数值本身由父组件每 5 秒刷新后通过 values 传入。物模型决定哪些测点是数值，父组件还没取到物模型时（modelReady=false）
 * 先不查趋势，避免用错误的分类查一次、物模型到达后又查一次。点击卡片发出 open-history（测点标识）。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { historyApi } from '@/api/history'
import type { PointView, PropertyView } from '@/api/types'
import { useUiStore, type LatestView } from '@/stores/ui'
import { createVisiblePoller } from '@/composables/usePolling'
import { createRequestSeq } from '@/utils/requestSeq'
import {
  SPARK_WINDOW_MS,
  sparklineIdentifiers,
  sparklineSeries,
  toMetricCards,
  type SparkPoint,
} from '@/utils/metricCards'
import { formatPointValue, pointLabel } from '@/utils/pointValue'
import { formatDateTime } from '@/utils/format'
import MetricCard from './MetricCard.vue'

const props = withDefaults(defineProps<{
  deviceKey: string
  values: PointView[]
  model: PropertyView[]
  lastRefreshed: number | null
  /** 物模型已加载完成（成功或失败都算）；默认 true，false 时暂不查趋势 */
  modelReady?: boolean
}>(), { modelReady: true })
const emit = defineEmits<{ 'open-history': [identifier: string] }>()

const SPARK_REFRESH_MS = 60_000

const ui = useUiStore()
const cards = computed(() => toMetricCards(props.values, props.model))
const sparkIds = computed(() => sparklineIdentifiers(cards.value))
const sparks = ref<Record<string, SparkPoint[]>>({})
const seq = createRequestSeq()
const poller = createVisiblePoller(loadSparks, SPARK_REFRESH_MS)

/** 一次查询取得全部趋势线；只有最后发出的请求能写入结果，失败时保留原有趋势线（错误由轮询器吞掉） */
async function loadSparks() {
  const ids = sparkIds.value
  if (ids.length === 0) {
    return
  }
  const id = seq.next()
  const now = Date.now()
  const res = await historyApi.query(props.deviceKey, ids, now - SPARK_WINDOW_MS, now)
  if (seq.isLatest(id)) {
    sparks.value = sparklineSeries(res)
  }
}

/** 某张卡片的趋势点：不画趋势线的测点为 null，尚未取到时为空数组 */
function sparkPoints(identifier: string): SparkPoint[] | null {
  return sparkIds.value.includes(identifier) ? (sparks.value[identifier] ?? []) : null
}

/** 卡片 / 表格切换 */
function onViewChange(value: unknown) {
  ui.setLatestView(value as LatestView)
}

// 只在物模型就绪、卡片视图且有数值测点时轮询趋势；测点集合变化（而不是每 5 秒的数值刷新）才重新开始
watch(
  [() => sparkIds.value.join(','), () => ui.latestView, () => props.modelReady],
  ([ids, view, ready]) => {
    if (ids && view === 'cards' && ready) {
      poller.start()
    } else {
      poller.stop()
      seq.next()
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  poller.dispose()
  seq.next()
})
</script>

<template>
  <div class="latest">
    <div class="bar">
      <span class="cd-muted">每 5 秒刷新 · {{ formatDateTime(lastRefreshed) }}</span>
      <el-radio-group :model-value="ui.latestView" size="small" @change="onViewChange">
        <el-radio-button value="cards">卡片</el-radio-button>
        <el-radio-button value="table">表格</el-radio-button>
      </el-radio-group>
    </div>

    <el-empty v-if="values.length === 0" description="暂无数据：设备上报后在这里显示最新值" />
    <div v-else-if="ui.latestView === 'cards'" class="cards">
      <MetricCard
        v-for="c in cards"
        :key="c.identifier"
        :card="c"
        :points="sparkPoints(c.identifier)"
        @select="(identifier: string) => emit('open-history', identifier)"
      />
    </div>
    <el-table v-else :data="values">
      <el-table-column label="测点" min-width="200">
        <template #default="{ row }">{{ pointLabel(row) }}</template>
      </el-table-column>
      <el-table-column label="值" min-width="140">
        <template #default="{ row }">{{ formatPointValue(row) }}</template>
      </el-table-column>
      <el-table-column label="采样时间" width="200">
        <template #default="{ row }">{{ formatDateTime(row.ts) }}</template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.latest {
  display: flex;
  flex-direction: column;
  gap: var(--cd-space-3);
}
.bar {
  display: flex;
  flex-wrap: wrap;
  gap: var(--cd-space-2);
  align-items: center;
  justify-content: space-between;
}
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: var(--cd-space-3);
}
</style>
