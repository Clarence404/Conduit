<script setup lang="ts">
/**
 * 连接参数对话框（设备信息卡的「连接参数」）：设备接入 MQTT 所需的地址、端口、clientId、username、上报 topic 与 payload 示例。
 * 密码是设备密钥，只在创建或重置时显示，这里不出现。地址取当前页面的主机名，端口可由构建时 VITE_MQTT_PORT 覆盖（默认 1883）。
 */
import { computed } from 'vue'
import { buildConnectionInfo } from '@/utils/connection'

const props = defineProps<{ modelValue: boolean; productKey: string; deviceKey: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const mqttPort = Number(import.meta.env.VITE_MQTT_PORT) || 1883
const info = computed(() => buildConnectionInfo(props.productKey, props.deviceKey, window.location.hostname, mqttPort))
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="连接参数"
    width="min(640px, 92vw)"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <el-descriptions :column="1" border>
      <el-descriptions-item label="地址">{{ info.host }}</el-descriptions-item>
      <el-descriptions-item label="端口">{{ info.port }}</el-descriptions-item>
      <el-descriptions-item label="clientId">{{ info.clientId }}</el-descriptions-item>
      <el-descriptions-item label="username">{{ info.username }}</el-descriptions-item>
      <el-descriptions-item label="password">设备密钥（创建或重置时显示）</el-descriptions-item>
      <el-descriptions-item label="上报 topic">{{ info.topic }}</el-descriptions-item>
    </el-descriptions>
    <p class="label">payload 示例</p>
    <pre class="payload">{{ info.payloadExample }}</pre>
    <template #footer>
      <el-button type="primary" @click="emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.label {
  margin: var(--cd-space-4) 0 var(--cd-space-1);
  color: var(--cd-text-muted);
}
.payload {
  margin: 0;
  padding: var(--cd-space-3);
  overflow-x: auto;
  background: var(--el-fill-color-light);
  border-radius: var(--cd-radius-sm);
}
</style>
