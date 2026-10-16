<script setup lang="ts">
/** 首页入口（/home）：未代入的平台管理员显示平台首页，其他身份（含代入租户的平台管理员）显示租户首页。 */
import { usePermission } from '@/composables/usePermission'
import HomeView from './HomeView.vue'
import PlatformHomeView from './PlatformHomeView.vue'

const { isPlatformAdmin } = usePermission()
</script>

<template>
  <PlatformHomeView v-if="isPlatformAdmin" />
  <HomeView v-else />
</template>
