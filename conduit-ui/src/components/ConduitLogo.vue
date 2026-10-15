<script setup lang="ts">
/**
 * Conduit 品牌标志（字母 C 信号）：圆角深色方块里一个蓝色字母 C，中间穿过绿色的信号折线。
 * size 为边长（px，默认 32）；tone 决定底色：auto 用标准深色底 #16212f（浅色背景上用），
 * dark-bg 用稍亮的 #24364d（放在深色侧栏、登录页深色品牌区上，避免方块与背景融为一体）。
 * 这里的颜色是品牌色，是「组件样式只用设计变量」约定的例外；favicon 见 public/favicon.svg（线宽加粗以便 16px 可辨认）。
 */
withDefaults(defineProps<{ size?: number; tone?: 'auto' | 'dark-bg' }>(), { size: 32, tone: 'auto' })
</script>

<template>
  <svg
    class="conduit-logo"
    :data-tone="tone"
    :width="size"
    :height="size"
    viewBox="0 0 32 32"
    xmlns="http://www.w3.org/2000/svg"
    role="img"
    aria-label="Conduit"
  >
    <rect width="32" height="32" rx="8" :fill="tone === 'dark-bg' ? '#24364d' : '#16212f'" />
    <path d="M22.5 9.5A9 9 0 1 0 22.5 22.5" fill="none" stroke="#5b9bff" stroke-width="3.2" stroke-linecap="round" />
    <path
      d="M11 16h3l2-4 2.5 8 2-4H24"
      fill="none"
      stroke="#3ccf74"
      stroke-width="2.2"
      stroke-linecap="round"
      stroke-linejoin="round"
    />
  </svg>
</template>

<style scoped>
.conduit-logo {
  flex-shrink: 0;
}
</style>
