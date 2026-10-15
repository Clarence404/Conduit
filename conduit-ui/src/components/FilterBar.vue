<script setup lang="ts">
/**
 * 列表筛选栏：主行放筛选控件（默认插槽）与「查询」「重置」，窄屏自动换行；在输入框中回车即查询（表单提交）。
 * 可选的 more 插槽是第二行筛选：出现该插槽时，按钮区多一个「更多筛选」开关（展开时显示第二行，moreCount 为其中已生效条件数，
 * 大于 0 时开关文案带数量）。是否展开由父组件通过 expanded 控制，点击开关发出 toggle；locked 为 true 表示不能收起，
 * 开关禁用并用提示说明原因。
 */
import { ArrowDown, ArrowUp, RefreshLeft, Search } from '@element-plus/icons-vue'

defineProps<{ expanded?: boolean; moreCount?: number; locked?: boolean }>()
const emit = defineEmits<{ search: []; reset: []; toggle: [] }>()
</script>

<template>
  <el-form inline class="filter-bar" @submit.prevent="emit('search')">
    <slot />
    <el-form-item class="buttons">
      <el-button type="primary" native-type="submit" :icon="Search">查询</el-button>
      <el-button :icon="RefreshLeft" @click="emit('reset')">重置</el-button>
      <el-tooltip v-if="$slots.more" :disabled="!locked" content="有生效的更多筛选条件，无法收起；重置后可收起" placement="top">
        <span class="toggle-wrap">
          <el-button link type="primary" data-test="more-toggle" :disabled="locked" @click="emit('toggle')">
            更多筛选<template v-if="moreCount">({{ moreCount }})</template>
            <el-icon class="el-icon--right"><ArrowUp v-if="expanded" /><ArrowDown v-else /></el-icon>
          </el-button>
        </span>
      </el-tooltip>
    </el-form-item>
    <div v-if="$slots.more && expanded" class="more-row">
      <slot name="more" />
    </div>
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
.more-row {
  display: flex;
  flex: 0 0 100%;
  flex-wrap: wrap;
  row-gap: var(--cd-space-2);
}
</style>
