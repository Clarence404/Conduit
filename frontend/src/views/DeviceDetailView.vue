<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { deviceApi } from '@/api/devices'
import { problemMessage } from '@/api/errors'
import type { DeviceView, PointView } from '@/api/types'
import { buildConnectionInfo, formatValue } from '@/utils/connection'
import { formatDateTime } from '@/utils/format'
import { usePolling } from '@/composables/usePolling'

// 不能叫 key：key 是 Vue 保留属性，不会作为 prop 传入
const props = defineProps<{ deviceKey: string }>()

const device = ref<DeviceView | null>(null)
const values = ref<PointView[]>([])
const lastRefreshed = ref<number | null>(null)

const connection = computed(() =>
  device.value ? buildConnectionInfo(device.value.productKey, device.value.deviceKey, window.location.hostname) : null,
)

async function loadDevice() {
  try {
    device.value = await deviceApi.get(props.deviceKey)
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

async function loadLatest() {
  const res = await deviceApi.latest(props.deviceKey)
  values.value = res.values
  lastRefreshed.value = Date.now()
}

onMounted(loadDevice)
usePolling(loadLatest, 5000)
</script>

<template>
  <div v-if="device" class="detail">
    <h2>{{ device.name }}</h2>

    <el-card header="基本信息">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="设备 Key">{{ device.deviceKey }}</el-descriptions-item>
        <el-descriptions-item label="产品 Key">{{ device.productKey }}</el-descriptions-item>
        <el-descriptions-item label="在线状态">
          <el-tag :type="device.online ? 'success' : 'info'">{{ device.online ? '在线' : '离线' }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="启用状态">{{ device.status === 'ENABLED' ? '启用' : '禁用' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatDateTime(device.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="最后上报">{{ formatDateTime(device.lastReportAt) }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card v-if="connection" header="连接参数">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="地址">{{ connection.host }}</el-descriptions-item>
        <el-descriptions-item label="端口">{{ connection.port }}</el-descriptions-item>
        <el-descriptions-item label="clientId">{{ connection.clientId }}</el-descriptions-item>
        <el-descriptions-item label="username">{{ connection.username }}</el-descriptions-item>
        <el-descriptions-item label="password">设备密钥（创建或重置时显示）</el-descriptions-item>
        <el-descriptions-item label="上报 topic">{{ connection.topic }}</el-descriptions-item>
      </el-descriptions>
      <p class="label">payload 示例</p>
      <pre class="payload">{{ connection.payloadExample }}</pre>
    </el-card>

    <el-card>
      <template #header>
        <div class="card-header">
          <span>最新值</span>
          <span class="hint">每 5 秒刷新 · {{ formatDateTime(lastRefreshed) }}</span>
        </div>
      </template>
      <el-table :data="values" border empty-text="暂无数据">
        <el-table-column prop="name" label="测点" width="200" />
        <el-table-column label="值">
          <template #default="{ row }">{{ formatValue(row.value) }}</template>
        </el-table-column>
        <el-table-column label="采样时间" width="200">
          <template #default="{ row }">{{ formatDateTime(row.ts) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.detail h2 {
  margin: 0;
}
.label {
  margin: 16px 0 4px;
  color: var(--el-text-color-secondary);
}
.payload {
  margin: 0;
  padding: 12px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}
.card-header {
  display: flex;
  justify-content: space-between;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
