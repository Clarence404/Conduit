<script setup lang="ts">
/** 在线状态：在线为绿点 +「在线」，离线为灰点 +「离线」。 */
defineProps<{ online: boolean }>()
</script>

<template>
  <span class="online-dot" :class="{ 'is-online': online }">
    <i class="dot" aria-hidden="true" />{{ online ? '在线' : '离线' }}
  </span>
</template>

<style scoped>
.online-dot {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--cd-text-muted);
}
.online-dot.is-online {
  color: var(--el-text-color-regular);
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--cd-offline);
}
.is-online .dot {
  background: var(--cd-online);
}
</style>
