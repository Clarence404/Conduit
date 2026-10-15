<script setup lang="ts">
/**
 * 物模型属性的新增/编辑对话框。
 * property 为 null 时新增，否则编辑（此时标识与数据类型不可改）。v-model 控制显隐，每次打开都重置表单；
 * 保存成功后关闭并触发 saved 事件，由父组件刷新列表。
 */

import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { thingModelApi } from '@/api/thingModel'
import { problemMessage } from '@/api/errors'
import type { PropertyRequest, PropertyView } from '@/api/types'
import {
  DATA_TYPES,
  INT_ENUM_HINT,
  emptyForm,
  fieldVisibility,
  formFromProperty,
  toPropertyRequest,
  type PropertyForm,
} from '@/utils/thingModel'

const props = defineProps<{ productKey: string; property: PropertyView | null }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [] }>()

const form = ref<PropertyForm>(emptyForm())
const saving = ref(false)
const editing = computed(() => props.property !== null)
const visibility = computed(() => fieldVisibility(form.value.dataType))

/** 每次打开对话框时重置表单：编辑用当前属性回填，新增用空表单，避免残留上一次的输入。 */
watch(visible, (open) => {
  if (open) {
    form.value = props.property ? formFromProperty(props.property) : emptyForm()
  }
})

function addEnumItem() {
  form.value.enumItems.push({ value: '', label: '' })
}

function removeEnumItem(index: number) {
  form.value.enumItems.splice(index, 1)
}

/**
 * 校验并保存：先把表单转成请求体（格式错误只做提示、不发请求），再按新增/编辑调用接口。
 * 保存中忽略重复点击；接口失败时提示服务端给出的原因并保持对话框打开。
 */
async function save() {
  if (saving.value) return
  let request: PropertyRequest
  try {
    request = toPropertyRequest(form.value)
  } catch (e) {
    ElMessage.warning((e as Error).message)
    return
  }
  saving.value = true
  try {
    if (props.property) {
      await thingModelApi.update(props.productKey, props.property.identifier, request)
    } else {
      await thingModelApi.create(props.productKey, request)
    }
    visible.value = false
    ElMessage.success('已保存')
    emit('saved')
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="editing ? '编辑属性' : '新增属性'" width="560px" :close-on-click-modal="false">
    <el-form label-width="96px" @submit.prevent="save">
      <el-form-item label="标识" required>
        <el-input v-model="form.identifier" :disabled="editing" maxlength="64" placeholder="字母开头，如 Ua" />
      </el-form-item>
      <el-form-item label="名称" required>
        <el-input v-model="form.name" maxlength="64" />
      </el-form-item>
      <el-form-item label="数据类型" required>
        <el-select v-model="form.dataType" :disabled="editing">
          <el-option v-for="t in DATA_TYPES" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
      </el-form-item>
      <el-alert
        v-if="!editing && visibility.intHint"
        :title="INT_ENUM_HINT"
        type="warning"
        :closable="false"
        show-icon
        class="hint"
      />
      <el-form-item label="单位">
        <el-input v-model="form.unit" maxlength="16" />
      </el-form-item>
      <template v-if="visibility.range">
        <el-form-item label="最小值">
          <el-input v-model="form.minValue" placeholder="不限" />
        </el-form-item>
        <el-form-item label="最大值">
          <el-input v-model="form.maxValue" placeholder="不限" />
        </el-form-item>
      </template>
      <el-form-item v-if="visibility.decimals" label="小数位数">
        <el-input v-model="form.decimals" placeholder="0～6，不填则不格式化" />
      </el-form-item>
      <el-form-item v-if="visibility.enumItems" label="枚举项" required>
        <div class="enum-items">
          <div v-for="(item, index) in form.enumItems" :key="index" class="enum-row">
            <el-input v-model="item.value" placeholder="值（整数）" class="enum-value" />
            <el-input v-model="item.label" placeholder="名称" />
            <el-button link type="danger" @click="removeEnumItem(index)">删除</el-button>
          </div>
          <el-button link type="primary" @click="addEnumItem">添加枚举项</el-button>
        </div>
      </el-form-item>
      <el-form-item label="说明">
        <el-input v-model="form.description" type="textarea" maxlength="256" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin-bottom: 18px;
}
.enum-items {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}
.enum-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
.enum-value {
  width: 120px;
  flex: none;
}
</style>
