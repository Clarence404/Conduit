<script setup lang="ts">
/** 列表筛选栏：一行放筛选控件（默认插槽）与「查询」「重置」按钮，窄屏自动换行；在输入框中回车即查询（表单提交）。 */
import { RefreshLeft, Search } from '@element-plus/icons-vue'

const emit = defineEmits<{ search: []; reset: [] }>()
</script>

<template>
  <el-form inline class="filter-bar" @submit.prevent="emit('search')">
    <slot />
    <el-form-item class="buttons">
      <el-button type="primary" native-type="submit" :icon="Search">查询</el-button>
      <el-button :icon="RefreshLeft" @click="emit('reset')">重置</el-button>
    </el-form-item>
  </el-form>
</template>

<style scoped>
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  row-gap: var(--cd-space-2);
  margin-bottom: var(--cd-space-3);
}
.filter-bar :deep(.el-form-item) {
  margin-bottom: 0;
}
</style>
