<script setup lang="ts">
/** 相对时间：显示「3 分钟前」，悬停（title）显示完整时间，每分钟自动更新；空值显示「—」且没有悬停提示。 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { formatDateTime } from '@/utils/format'
import { relativeTime } from '@/utils/relativeTime'

const props = defineProps<{ value: string | number | null | undefined }>()

const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | undefined

const text = computed(() => relativeTime(props.value, now.value))
const full = computed(() => (props.value === null || props.value === undefined || props.value === '' ? undefined : formatDateTime(props.value)))

onMounted(() => {
  timer = setInterval(() => {
    now.value = Date.now()
  }, 60_000)
})
onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <span class="relative-time" :title="full">{{ text }}</span>
</template>
