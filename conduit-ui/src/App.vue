<script setup lang="ts">
/** 应用根组件：渲染路由出口，并让浏览器标签标题跟随当前页面（「页面名或对象名称 · Conduit」）。 */
import { watchEffect } from 'vue'
import { useCurrentPage } from '@/composables/useBreadcrumb'

const { title } = useCurrentPage()

watchEffect(() => {
  document.title = title.value
})
</script>

<template>
  <router-view />
</template>
