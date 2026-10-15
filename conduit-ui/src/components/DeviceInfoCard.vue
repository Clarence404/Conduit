<script setup lang="ts">
/**
 * 设备信息卡（设备详情页顶部）：图标、名称、在线状态、启用状态标签；下方一行为所属产品（链接）、设备 Key（可复制）、
 * 最后上报（相对时间）、创建日期；右侧「连接参数」「编辑」按钮分别发出 connection、edit。
 */
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Connection, Cpu, DocumentCopy, Edit } from '@element-plus/icons-vue'
import type { DeviceView } from '@/api/types'
import { copyText } from '@/utils/clipboard'
import { formatDateTime } from '@/utils/format'
import OnlineDot from './OnlineDot.vue'
import RelativeTime from './RelativeTime.vue'

const props = defineProps<{ device: DeviceView; productName: string | null }>()
const emit = defineEmits<{ connection: []; edit: [] }>()

/** 创建日期 yyyy-MM-dd */
const createdDate = computed(() => formatDateTime(props.device.createdAt).slice(0, 10))

/** 复制设备 Key 到剪贴板并提示结果 */
async function copyKey() {
  if (await copyText(props.device.deviceKey)) {
    ElMessage.success('已复制')
  } else {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}
</script>

<template>
  <div class="info-card cd-panel">
    <div class="icon"><el-icon :size="28"><Cpu /></el-icon></div>
    <div class="body">
      <div class="title-row">
        <h2 class="name">{{ device.name }}</h2>
        <OnlineDot :online="device.online" />
        <el-tag :type="device.status === 'ENABLED' ? 'success' : 'info'" size="small">
          {{ device.status === 'ENABLED' ? '启用' : '禁用' }}
        </el-tag>
      </div>
      <div class="meta">
        <span>
          所属产品：<router-link :to="{ name: 'product-detail', params: { productKey: device.productKey } }">
            {{ productName ?? device.productKey }}
          </router-link>
        </span>
        <span>
          设备 Key：<code>{{ device.deviceKey }}</code>
          <el-button link type="primary" :icon="DocumentCopy" aria-label="复制设备 Key" @click="copyKey" />
        </span>
        <span>最后上报：<RelativeTime :value="device.lastReportAt" /></span>
        <span>创建日期：{{ createdDate }}</span>
      </div>
    </div>
    <div class="actions">
      <el-button :icon="Connection" @click="emit('connection')">连接参数</el-button>
      <el-button type="primary" :icon="Edit" @click="emit('edit')">编辑</el-button>
    </div>
  </div>
</template>

<style scoped>
.info-card {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--cd-space-4);
}
.icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  border-radius: var(--cd-radius-lg);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.body {
  flex: 1;
  min-width: 240px;
}
.title-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--cd-space-3);
}
.name {
  margin: 0;
  font-size: 20px;
}
.meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--cd-space-1) var(--cd-space-5);
  margin-top: var(--cd-space-2);
  color: var(--cd-text-muted);
  font-size: 13px;
}
.actions {
  display: flex;
  gap: var(--cd-space-2);
}
</style>
