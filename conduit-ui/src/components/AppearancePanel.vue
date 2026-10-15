<script setup lang="ts">
/**
 * 外观设置面板（顶栏「外观」按钮弹出）：主题 浅色 / 深色 / 跟随系统，侧栏 浅色 / 深色。
 * 选择即时生效（主题由 useTheme 监听 store 应用）并写入外观偏好。两组单选都带 aria-label，并用 aria-labelledby 关联可见的「主题」「侧栏」标签。
 */
import { useUiStore, type SidebarStyle, type ThemePref } from '@/stores/ui'

const ui = useUiStore()

const THEMES: { value: ThemePref; label: string }[] = [
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
  { value: 'system', label: '跟随系统' },
]
const SIDEBAR_STYLES: { value: SidebarStyle; label: string }[] = [
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
]

/** 主题单选变化 */
function onTheme(value: unknown) {
  ui.setTheme(value as ThemePref)
}

/** 侧栏风格单选变化 */
function onSidebarStyle(value: unknown) {
  ui.setSidebarStyle(value as SidebarStyle)
}
</script>

<template>
  <div class="appearance">
    <div class="group">
      <div id="appearance-theme-label" class="label">主题</div>
      <el-radio-group :model-value="ui.theme" size="small" aria-label="主题" aria-labelledby="appearance-theme-label" @change="onTheme">
        <el-radio-button v-for="t in THEMES" :key="t.value" :value="t.value">{{ t.label }}</el-radio-button>
      </el-radio-group>
    </div>
    <div class="group">
      <div id="appearance-sidebar-label" class="label">侧栏</div>
      <el-radio-group :model-value="ui.sidebarStyle" size="small" aria-label="侧栏风格" aria-labelledby="appearance-sidebar-label" @change="onSidebarStyle">
        <el-radio-button v-for="s in SIDEBAR_STYLES" :key="s.value" :value="s.value">{{ s.label }}</el-radio-button>
      </el-radio-group>
    </div>
  </div>
</template>

<style scoped>
.appearance {
  display: flex;
  flex-direction: column;
  gap: var(--cd-space-3);
}
.label {
  margin-bottom: var(--cd-space-1);
  color: var(--cd-text-muted);
  font-size: 12px;
}
</style>
