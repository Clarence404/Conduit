<script setup lang="ts">
/**
 * 设备详情页：上方为基本信息；下方「最新值 | 历史」两个标签页，标签与路由 query ?tab= 同步（用 router.replace，
 * 不产生历史记录）。最新值标签含每 5 秒轮询刷新的最新值表格与 MQTT 连接参数；历史曲线由 HistoryPanel 负责，只在历史标签激活时挂载。
 * 设备信息只在进入时加载一次；产品名称取不到时退回显示产品 Key。
 */

import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { deviceApi } from '@/api/devices'
import { productApi } from '@/api/products'
import { problemMessage } from '@/api/errors'
import type { DeviceView, PointView } from '@/api/types'
import { buildConnectionInfo } from '@/utils/connection'
import { formatPointValue, pointLabel } from '@/utils/pointValue'
import { formatDateTime } from '@/utils/format'
import { deviceTabFromQuery, deviceTabQuery, type DeviceTab } from '@/utils/deviceTabs'
import { usePolling } from '@/composables/usePolling'
import HistoryPanel from '@/components/HistoryPanel.vue'

// 不能叫 key：key 是 Vue 保留属性，不会作为 prop 传入
const props = defineProps<{ deviceKey: string }>()

const route = useRoute()
const router = useRouter()

// 当前标签与 ?tab= 同步；用 replace 切换，不产生历史记录
const activeTab = computed<DeviceTab>({
  get: () => deviceTabFromQuery(route.query.tab),
  set: (tab) => {
    void router.replace({ query: deviceTabQuery(route.query, tab) })
  },
})

/** MQTT 端口，可由构建时环境变量 VITE_MQTT_PORT 覆盖，默认 1883；连接地址用当前页面的主机名。 */
const mqttPort = Number(import.meta.env.VITE_MQTT_PORT) || 1883

const device = ref<DeviceView | null>(null)
const productName = ref<string | null>(null)
const values = ref<PointView[]>([])
const lastRefreshed = ref<number | null>(null)

const connection = computed(() =>
  device.value ? buildConnectionInfo(device.value.productKey, device.value.deviceKey, window.location.hostname, mqttPort) : null,
)

/** 加载设备及其所属产品名称。设备加载失败时提示并终止；产品名称只是展示用，失败时静默。 */
async function loadDevice() {
  try {
    device.value = await deviceApi.get(props.deviceKey)
  } catch (e) {
    ElMessage.error(problemMessage(e))
    return
  }
  try {
    productName.value = (await productApi.get(device.value.productKey)).name
  } catch {
    // 拿不到产品名称时退回显示产品 Key
  }
}

/** 加载最新值并记录刷新时间。不处理错误：由 startPolling 吞掉，下一轮继续重试。 */
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
        <el-descriptions-item label="产品">
          <router-link :to="{ name: 'product-detail', params: { productKey: device.productKey } }">
            {{ productName ?? device.productKey }}
          </router-link>
          <span v-if="productName" class="hint product-key">{{ device.productKey }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="在线状态">
          <el-tag :type="device.online ? 'success' : 'info'">{{ device.online ? '在线' : '离线' }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="启用状态">{{ device.status === 'ENABLED' ? '启用' : '禁用' }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatDateTime(device.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="最后上报">{{ formatDateTime(device.lastReportAt) }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="最新值" name="latest">
        <div class="pane">
          <el-card>
            <template #header>
              <div class="card-header">
                <span>最新值</span>
                <span class="hint">每 5 秒刷新 · {{ formatDateTime(lastRefreshed) }}</span>
              </div>
            </template>
            <el-table :data="values" border empty-text="暂无数据">
              <el-table-column label="测点" width="240">
                <template #default="{ row }">{{ pointLabel(row) }}</template>
              </el-table-column>
              <el-table-column label="值">
                <template #default="{ row }">{{ formatPointValue(row) }}</template>
              </el-table-column>
              <el-table-column label="采样时间" width="200">
                <template #default="{ row }">{{ formatDateTime(row.ts) }}</template>
              </el-table-column>
            </el-table>
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
        </div>
      </el-tab-pane>

      <el-tab-pane label="历史" name="history">
        <HistoryPanel v-if="activeTab === 'history'" :device-key="device.deviceKey" :product-key="device.productKey" />
      </el-tab-pane>
    </el-tabs>
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
.pane {
  display: flex;
  flex-direction: column;
  gap: 16px;
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
.product-key {
  margin-left: 8px;
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
