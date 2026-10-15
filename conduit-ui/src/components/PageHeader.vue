<script setup lang="ts">
/** 统一页头：标题、可选副标题（一句说明，可用 subtitle 插槽放富内容）与右侧操作按钮插槽 actions；窄屏时操作区换行。 */
defineProps<{ title: string; subtitle?: string }>()
</script>

<template>
  <div class="page-header">
    <div class="text">
      <h2 class="title">{{ title }}</h2>
      <p v-if="subtitle || $slots.subtitle" class="subtitle">
        <slot name="subtitle">{{ subtitle }}</slot>
      </p>
    </div>
    <div v-if="$slots.actions" class="actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--cd-space-3);
  margin-bottom: var(--cd-space-4);
}
.title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.subtitle {
  margin: var(--cd-space-1) 0 0;
  color: var(--cd-text-muted);
  font-size: 13px;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--cd-space-2);
}
</style>
