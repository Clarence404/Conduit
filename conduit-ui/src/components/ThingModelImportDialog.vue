<script setup lang="ts">
/**
 * 物模型导入对话框：选择 JSON 文件，按「合并」或「替换」方式整体导入到产品。
 * 文件在浏览器端只做 JSON 语法解析，内容校验由服务端完成；校验失败时在对话框内列出逐项错误，导入不生效。
 * 导入成功后关闭并触发 imported 事件，由父组件刷新。
 */

import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { thingModelApi } from '@/api/thingModel'
import { problemErrors, problemMessage, type ProblemFieldError } from '@/api/errors'
import type { ImportMode } from '@/api/types'

const props = defineProps<{ productKey: string }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ imported: [] }>()

const mode = ref<ImportMode>('merge')
const parsed = ref<unknown>(null)
const errors = ref<ProblemFieldError[]>([])
const importing = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

/** 每次打开对话框时清空上一次的文件、方式和错误。 */
watch(visible, (open) => {
  if (open) {
    mode.value = 'merge'
    parsed.value = null
    errors.value = []
    if (fileInput.value) fileInput.value.value = ''
  }
})

/** 读取所选文件并解析为 JSON 暂存；不是合法 JSON 时提示并清空，保持「导入」按钮不可用。 */
async function onFileChange(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  errors.value = []
  parsed.value = null
  if (!file) return
  try {
    parsed.value = JSON.parse(await file.text())
  } catch {
    ElMessage.error('文件不是合法的 JSON')
  }
}

/**
 * 提交导入。替换模式会删除文件中没有的属性，先二次确认；用户取消则不发请求。
 * 失败时把服务端返回的逐项错误显示在对话框中。
 */
async function confirmImport() {
  if (importing.value || parsed.value === null) return
  if (mode.value === 'replace') {
    try {
      await ElMessageBox.confirm('替换模式会删除文件中没有的属性，确定继续？', '导入物模型', { type: 'warning' })
    } catch {
      return
    }
  }
  importing.value = true
  try {
    await thingModelApi.importModel(props.productKey, parsed.value, mode.value)
    visible.value = false
    ElMessage.success('导入成功')
    emit('imported')
  } catch (e) {
    errors.value = problemErrors(e)
    ElMessage.error(problemMessage(e))
  } finally {
    importing.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="导入物模型" width="600px" :close-on-click-modal="false">
    <el-form label-width="80px">
      <el-form-item label="文件">
        <input ref="fileInput" type="file" accept=".json,application/json" @change="onFileChange" />
      </el-form-item>
      <el-form-item label="方式">
        <el-radio-group v-model="mode">
          <el-radio value="merge">合并：新增或更新文件中的属性，保留其他属性</el-radio>
          <el-radio value="replace">替换：以文件为准，删除文件中没有的属性</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <el-alert v-if="errors.length" type="error" :closable="false" title="导入未生效，请修正以下错误">
      <ul class="errors">
        <li v-for="(err, i) in errors" :key="i">{{ err.identifier ? `${err.identifier}：` : '' }}{{ err.message }}</li>
      </ul>
    </el-alert>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="importing" :disabled="parsed === null" @click="confirmImport">导入</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.errors {
  margin: 4px 0 0;
  padding-left: 18px;
}
</style>
