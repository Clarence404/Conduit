<script setup lang="ts">
/** 404 页：未匹配的地址显示「页面不存在」与可能的原因，提供「回到首页」（跳到根路径，由路由重定向到默认页）。 */
import { useRouter } from 'vue-router'

const router = useRouter()

/** 回到首页 */
function goHome() {
  void router.push('/')
}
</script>

<template>
  <el-result icon="warning" title="页面不存在" sub-title="地址可能输错了，或者页面已被移除。">
    <template #extra>
      <el-button type="primary" @click="goHome">回到首页</el-button>
    </template>
  </el-result>
</template>
