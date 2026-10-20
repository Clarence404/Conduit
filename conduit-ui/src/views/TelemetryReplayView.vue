<script setup lang="ts">
/**
 * 数据重放（平台管理员）：按时间范围把总线中保留的原始报文重新写入时序库，用于 InfluxDB 断电丢数后的恢复。
 * 跨度上限 6 小时，提交前在前端先行校验并弹确认；任务运行中每 2 秒轮询状态并可取消，任务结束即停止轮询，
 * 离开页面时清除定时器。从未提交过任务时显示空状态。
 * 取消只在批次之间与重试等待中检查，最长约 90 秒才生效，所以点击后界面显示「正在取消」直到状态变为已取消；
 * 运行中的状态没有结束时间与错误信息，这两项只在任务结束后出现。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { replayApi } from '@/api/replay'
import { problemMessage } from '@/api/errors'
import type { ReplayState, ReplayStatus } from '@/api/types'
import { startPolling } from '@/composables/usePolling'
import PageHeader from '@/components/PageHeader.vue'

/** 单次重放允许的最大跨度：6 小时，与服务端一致 */
const MAX_SPAN_MS = 6 * 60 * 60 * 1000
/** 运行中的状态轮询间隔 */
const POLL_MS = 2000

const range = ref<[Date, Date] | null>(null)
const status = ref<ReplayStatus | null>(null)
const submitting = ref(false)
/** 已发出取消请求但任务尚未停下 */
const cancelRequested = ref(false)
let stopPoll: (() => void) | null = null

/** 任务是否在运行中（决定是否轮询、是否显示取消按钮） */
const running = computed(() => status.value?.state === 'RUNNING')
/** 从未提交过任务：显示空状态与引导文案 */
const empty = computed(() => !status.value || status.value.state === 'NONE')

const STATE_LABELS: Record<ReplayState, string> = {
  NONE: '未开始',
  RUNNING: '进行中',
  SUCCEEDED: '已完成',
  FAILED: '已失败',
  CANCELLED: '已取消',
}

const STATE_TYPES: Record<ReplayState, 'info' | 'primary' | 'success' | 'danger' | 'warning'> = {
  NONE: 'info',
  RUNNING: 'primary',
  SUCCEEDED: 'success',
  FAILED: 'danger',
  CANCELLED: 'warning',
}

/** 毫秒时间戳的本地显示；没有值时显示「—」 */
function formatTime(ms?: number): string {
  return ms === undefined ? '—' : new Date(ms).toLocaleString('zh-CN', { hour12: false })
}

/** 计数的显示；没有值时显示「—」 */
function formatCount(n?: number): string {
  return n === undefined ? '—' : String(n)
}

/** 耗时：任务结束后按起止时间算秒数；仍在运行时显示「进行中」 */
const elapsed = computed(() => {
  const s = status.value
  if (!s || s.startedAt === undefined) {
    return '—'
  }
  if (s.finishedAt === undefined) {
    return '进行中'
  }
  return `${((s.finishedAt - s.startedAt) / 1000).toFixed(1)} 秒`
})

/** 停止轮询并清除定时器 */
function stopPolling() {
  stopPoll?.()
  stopPoll = null
}

/** 运行中才轮询：状态变为非运行中立即停表 */
function syncPolling() {
  if (running.value) {
    if (!stopPoll) {
      stopPoll = startPolling(poll, POLL_MS)
    }
  } else {
    stopPolling()
  }
}

/** 轮询一次状态；失败保留上次状态，等下一轮 */
async function poll() {
  try {
    status.value = await replayApi.status()
  } catch {
    return
  }
  if (!running.value) {
    cancelRequested.value = false
    stopPolling()
  }
}

/** 首次进入时读取一次状态，运行中则开始轮询 */
async function load() {
  try {
    status.value = await replayApi.status()
  } catch (e) {
    ElMessage.error(problemMessage(e))
    return
  }
  syncPolling()
}

/** 提交重放：校验时间范围与跨度、弹确认、提交后开始轮询 */
async function start() {
  const picked = range.value
  if (!picked) {
    ElMessage.warning('请选择时间范围')
    return
  }
  const from = picked[0].getTime()
  const to = picked[1].getTime()
  if (from >= to) {
    ElMessage.warning('开始时间必须早于结束时间')
    return
  }
  if (to - from > MAX_SPAN_MS) {
    ElMessage.warning('时间跨度不能超过 6 小时')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将重放 ${formatTime(from)} 到 ${formatTime(to)} 之间的原始报文，重新写入时序库。`,
      '开始重放',
      { confirmButtonText: '开始重放', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  submitting.value = true
  try {
    cancelRequested.value = false
    status.value = await replayApi.start({ from, to })
    syncPolling()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    submitting.value = false
  }
}

/** 请求取消正在运行的任务；服务端只在批次之间检查，最长约 90 秒后才真正停下 */
async function cancel() {
  try {
    await ElMessageBox.confirm(
      '取消请求不会立即生效：服务端只在批次之间检查，最长可能要约 90 秒才会停下。已写入的数据保留，未重放的数据不会写入。',
      '取消重放',
      { confirmButtonText: '取消重放', cancelButtonText: '继续运行', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    status.value = await replayApi.cancel()
    cancelRequested.value = running.value
    syncPolling()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

onMounted(() => {
  void load()
})
onBeforeUnmount(stopPolling)

defineExpose({ range, status, start, cancel })
</script>

<template>
  <div class="replay">
    <PageHeader
      title="数据重放"
      subtitle="把总线中保留的原始报文按时间范围重新写入时序库，用于断电后恢复；同设备同时间戳覆盖为相同值，不更新在线状态，也不重复写死信。"
    />

    <div class="cd-panel form">
      <el-form label-width="88px" @submit.prevent>
        <el-form-item label="时间范围">
          <el-date-picker
            v-model="range"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            :disabled="running"
            data-test="range"
          />
          <span class="hint">单次最多 6 小时，结束时间不含在内。时间范围按总线中消息的记录时刻匹配，与本机时钟可能有秒级偏差，恢复时建议前后各放宽一分钟。</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" :disabled="running" data-test="start" @click="start">
            开始重放
          </el-button>
          <el-button
            v-if="running"
            type="danger"
            plain
            :disabled="cancelRequested"
            data-test="cancel"
            @click="cancel"
          >
            {{ cancelRequested ? '正在取消…' : '取消' }}
          </el-button>
          <span v-if="running && cancelRequested" class="hint">已请求取消，最长约 90 秒后停止。</span>
        </el-form-item>
      </el-form>
    </div>

    <div class="cd-panel task">
      <el-empty v-if="empty" description="还没有重放过数据">
        <span class="hint">选择一段时间范围后点击「开始重放」，恢复该时间段内丢失的时序数据。</span>
      </el-empty>
      <template v-else-if="status">
        <div class="task-head">
          <span class="task-title">当前任务</span>
          <el-tag :type="STATE_TYPES[status.state]" disable-transitions>{{ STATE_LABELS[status.state] }}</el-tag>
        </div>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="开始时间">{{ formatTime(status.from) }}</el-descriptions-item>
          <el-descriptions-item label="结束时间">{{ formatTime(status.to) }}</el-descriptions-item>
          <el-descriptions-item label="提交于">{{ formatTime(status.startedAt) }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ elapsed }}</el-descriptions-item>
        </el-descriptions>
        <div class="stats">
          <div class="stat"><span class="stat-label">读取</span><span class="stat-value">{{ formatCount(status.scanned) }}</span></div>
          <div class="stat"><span class="stat-label">写入</span><span class="stat-value">{{ formatCount(status.written) }}</span></div>
          <div class="stat"><span class="stat-label">跳过</span><span class="stat-value">{{ formatCount(status.skipped) }}</span></div>
          <div class="stat"><span class="stat-label">校验剔除</span><span class="stat-value">{{ formatCount(status.rejected) }}</span></div>
        </div>
        <el-alert v-if="status.error" :title="status.error" type="error" :closable="false" show-icon />
      </template>
    </div>
  </div>
</template>

<style scoped>
.form {
  margin-bottom: var(--cd-space-4);
}
.hint {
  margin-left: var(--cd-space-2);
  color: var(--cd-text-muted);
  font-size: 12px;
}
.task-head {
  display: flex;
  align-items: center;
  gap: var(--cd-space-2);
  margin-bottom: var(--cd-space-3);
}
.task-title {
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.stats {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: var(--cd-space-3);
  margin-top: var(--cd-space-4);
}
.stat {
  display: flex;
  flex-direction: column;
  gap: var(--cd-space-1);
}
.stat-label {
  color: var(--cd-text-muted);
  font-size: 12px;
}
.stat-value {
  color: var(--el-text-color-primary);
  font-size: 22px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
</style>
