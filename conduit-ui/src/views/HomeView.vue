<script setup lang="ts">
/**
 * 首页总览：4 个指标卡（产品、设备、在线设备、最近 1 小时死信）与「最近上报的设备」「按产品统计」两个列表，点击进入详情。
 * 数据来自 GET /api/v1/overview，每 30 秒自动刷新（页面隐藏时暂停）；服务端 Redis 不可用时在线相关数值为 null，显示「—」。
 * 刷新失败时保留上次数据，同一段连续失败只提示一次。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Box, Connection, Cpu, Warning } from '@element-plus/icons-vue'
import { overviewApi } from '@/api/overview'
import { problemMessage } from '@/api/errors'
import type { OverviewView } from '@/api/types'
import { usePermission } from '@/composables/usePermission'
import { createVisiblePoller } from '@/composables/usePolling'
import { createRequestSeq } from '@/utils/requestSeq'
import { formatCount } from '@/utils/format'
import { deadText, onlineRate } from '@/utils/overview'
import PageHeader from '@/components/PageHeader.vue'
import RelativeTime from '@/components/RelativeTime.vue'

const REFRESH_MS = 30_000

const router = useRouter()
const data = ref<OverviewView | null>(null)
const seq = createRequestSeq()
let errorShown = false

const { canWrite } = usePermission()

/** 在线率；在线数未知时为 null */
const rate = computed(() => (data.value ? onlineRate(data.value.onlineCount, data.value.deviceCount) : null))

/** 加载总览：只有最后发出的请求能写入结果；失败时保留上次数据，连续失败只提示一次 */
async function load() {
  const id = seq.next()
  try {
    const res = await overviewApi.get()
    if (seq.isLatest(id)) {
      data.value = res
      errorShown = false
    }
  } catch (e) {
    if (seq.isLatest(id) && !errorShown) {
      errorShown = true
      ElMessage.error(problemMessage(e))
    }
  }
}

const poller = createVisiblePoller(load, REFRESH_MS)

/** 点击指标卡跳到设备列表；online 为 true 时直接带上「在线」筛选（在线数未知时模板不触发） */
function openDevices(online = false) {
  void router.push(online ? { name: 'devices', query: { online: 'true' } } : { name: 'devices' })
}

/** 进入设备详情 */
function openDevice(deviceKey: string) {
  void router.push({ name: 'device-detail', params: { deviceKey } })
}

/** 进入产品详情 */
function openProduct(productKey: string) {
  void router.push({ name: 'product-detail', params: { productKey } })
}

onMounted(() => poller.start())
onBeforeUnmount(() => {
  poller.dispose()
  seq.next()
})
</script>

<template>
  <div class="home">
    <PageHeader title="首页" subtitle="平台运行概况，每 30 秒自动刷新。" />

    <el-skeleton v-if="!data" :rows="6" animated />
    <template v-else>
      <div class="tiles">
        <div class="tile cd-panel" data-test="tile-products">
          <div class="tile-head"><el-icon><Box /></el-icon>产品</div>
          <div class="tile-value">{{ data.productCount }}</div>
          <div class="tile-sub">共定义 {{ data.propertyCount }} 个属性</div>
        </div>
        <div class="tile cd-panel clickable" data-test="tile-devices" role="link" tabindex="0" @click="openDevices()" @keydown.enter="openDevices()">
          <div class="tile-head"><el-icon><Cpu /></el-icon>设备</div>
          <div class="tile-value">{{ data.deviceCount }}</div>
          <div class="tile-sub">已启用 {{ data.enabledCount }} · 已禁用 {{ data.disabledCount }}</div>
        </div>
        <div
          class="tile cd-panel"
          :class="{ clickable: data.onlineCount !== null }"
          data-test="tile-online"
          :role="data.onlineCount !== null ? 'link' : undefined"
          :tabindex="data.onlineCount !== null ? 0 : undefined"
          @click="data.onlineCount !== null && openDevices(true)"
          @keydown.enter="data.onlineCount !== null && openDevices(true)"
        >
          <div class="tile-head"><el-icon><Connection /></el-icon>在线设备</div>
          <div class="tile-value">{{ formatCount(data.onlineCount) }}</div>
          <el-progress v-if="rate !== null" :percentage="rate" :show-text="false" :stroke-width="6" status="success" />
          <div class="tile-sub">{{ rate === null ? '在线状态暂不可用' : `在线率 ${rate}%` }}</div>
        </div>
        <div class="tile cd-panel" data-test="tile-dead">
          <div class="tile-head"><el-icon><Warning /></el-icon>最近 1 小时死信</div>
          <div class="tile-value">{{ formatCount(data.deadLastHour?.total) }}</div>
          <div class="tile-sub">{{ deadText(data.deadLastHour) }}</div>
        </div>
      </div>

      <div class="lists">
        <div class="list cd-panel recent">
          <div class="list-head">最近上报的设备</div>
          <el-empty v-if="data.recentDevices.length === 0" description="暂无上报" :image-size="64" />
          <ul v-else>
            <li
              v-for="d in data.recentDevices"
              :key="d.deviceKey"
              class="row"
              role="button"
              tabindex="0"
              @click="openDevice(d.deviceKey)"
              @keydown.enter="openDevice(d.deviceKey)"
            >
              <div class="row-main">
                <span class="row-title">{{ d.name }}</span>
                <span class="cd-muted">{{ d.productName ?? d.productKey ?? '—' }}</span>
              </div>
              <RelativeTime :value="d.lastReportAt" class="cd-muted" />
            </li>
          </ul>
        </div>
        <div class="list cd-panel by-product">
          <div class="list-head">按产品统计</div>
          <el-empty v-if="data.products.length === 0" description="还没有产品" :image-size="64">
            <el-button v-if="canWrite" type="primary" @click="router.push({ name: 'products' })">去创建产品</el-button>
          </el-empty>
          <ul v-else>
            <li
              v-for="p in data.products"
              :key="p.productKey"
              class="row"
              role="button"
              tabindex="0"
              @click="openProduct(p.productKey)"
              @keydown.enter="openProduct(p.productKey)"
            >
              <div class="row-main">
                <span class="row-title">{{ p.name }}</span>
                <span class="cd-muted">在线 / 设备</span>
              </div>
              <span class="row-count">{{ formatCount(p.onlineCount) }} / {{ p.deviceCount }}</span>
            </li>
          </ul>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.tiles {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--cd-space-4);
  margin-bottom: var(--cd-space-4);
}
.tile {
  display: flex;
  flex-direction: column;
  gap: var(--cd-space-2);
}
.tile.clickable {
  cursor: pointer;
}
.tile.clickable:hover,
.tile.clickable:focus-visible {
  outline: 1px solid var(--el-color-primary);
}
.tile-head {
  display: flex;
  align-items: center;
  gap: var(--cd-space-2);
  color: var(--cd-text-muted);
  font-size: 13px;
}
.tile-value {
  color: var(--el-text-color-primary);
  font-size: 28px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.tile-sub {
  color: var(--cd-text-muted);
  font-size: 12px;
}
.lists {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: var(--cd-space-4);
}
.list-head {
  margin-bottom: var(--cd-space-2);
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.list ul {
  margin: 0;
  padding: 0;
  list-style: none;
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--cd-space-3);
  padding: var(--cd-space-2) var(--cd-space-2);
  border-radius: var(--cd-radius-sm);
  cursor: pointer;
}
.row:hover,
.row:focus-visible {
  background: var(--el-fill-color-light);
  outline: none;
}
.row-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.row-title {
  overflow: hidden;
  color: var(--el-text-color-primary);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.row-count {
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}
</style>
