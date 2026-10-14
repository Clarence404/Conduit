<script setup lang="ts">
import { ElMessage } from 'element-plus'

const props = defineProps<{ modelValue: boolean; deviceKey: string; secret: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

async function copy() {
  try {
    await navigator.clipboard.writeText(props.secret)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="设备密钥"
    width="520px"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <el-alert type="warning" :closable="false" show-icon title="密钥只显示这一次，关闭后无法再次查看，请立即保存。" />
    <el-descriptions :column="1" border class="info">
      <el-descriptions-item label="设备 Key">{{ deviceKey }}</el-descriptions-item>
      <el-descriptions-item label="设备密钥">
        <code>{{ secret }}</code>
      </el-descriptions-item>
    </el-descriptions>
    <template #footer>
      <el-button @click="copy">复制密钥</el-button>
      <el-button type="primary" @click="emit('update:modelValue', false)">我已保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.info {
  margin-top: 16px;
}
</style>
