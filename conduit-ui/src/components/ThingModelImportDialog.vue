<script setup lang="ts">
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

watch(visible, (open) => {
  if (open) {
    mode.value = 'merge'
    parsed.value = null
    errors.value = []
    if (fileInput.value) fileInput.value.value = ''
  }
})

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
