<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { productApi } from '@/api/products'
import { codecApi } from '@/api/codecs'
import { thingModelApi } from '@/api/thingModel'
import { problemMessage } from '@/api/errors'
import type { CodecView, ProductView, PropertyView } from '@/api/types'
import { dataTypeLabel, formatRange } from '@/utils/thingModel'
import { downloadJson } from '@/utils/download'
import PropertyDialog from '@/components/PropertyDialog.vue'
import ThingModelImportDialog from '@/components/ThingModelImportDialog.vue'

const props = defineProps<{ productKey: string }>()

const product = ref<ProductView | null>(null)
const codecs = ref<CodecView[]>([])
const properties = ref<PropertyView[]>([])
const activeTab = ref('basic')
const basic = reactive({ name: '', description: '', codec: '' })
const savingBasic = ref(false)
const loadingProperties = ref(false)
const dialogVisible = ref(false)
const editing = ref<PropertyView | null>(null)
const importVisible = ref(false)

async function loadProduct() {
  try {
    product.value = await productApi.get(props.productKey)
    basic.name = product.value.name
    basic.description = product.value.description ?? ''
    basic.codec = product.value.codec
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

async function loadCodecs() {
  try {
    codecs.value = await codecApi.list()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

async function loadProperties() {
  loadingProperties.value = true
  try {
    properties.value = await thingModelApi.list(props.productKey)
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    loadingProperties.value = false
  }
}

async function saveBasic() {
  if (savingBasic.value) return
  if (!basic.name.trim()) {
    ElMessage.warning('请输入产品名称')
    return
  }
  savingBasic.value = true
  try {
    product.value = await productApi.update(props.productKey, {
      name: basic.name.trim(),
      description: basic.description.trim() || null,
      codec: basic.codec,
    })
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    savingBasic.value = false
  }
}

function openCreate() {
  editing.value = null
  dialogVisible.value = true
}

function openEdit(row: PropertyView) {
  editing.value = row
  dialogVisible.value = true
}

async function remove(row: PropertyView) {
  try {
    await ElMessageBox.confirm(`确定删除属性「${row.name}（${row.identifier}）」？历史数据不会删除。`, '删除属性', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await thingModelApi.remove(props.productKey, row.identifier)
    ElMessage.success('已删除')
    await onPropertiesChanged()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

async function exportModel() {
  try {
    downloadJson(`${props.productKey}.thing-model.json`, await thingModelApi.exportModel(props.productKey))
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

async function onPropertiesChanged() {
  await Promise.all([loadProperties(), loadProduct()])
}

onMounted(() => {
  void loadProduct()
  void loadCodecs()
  void loadProperties()
})
</script>

<template>
  <div class="detail">
    <div class="header">
      <router-link :to="{ name: 'products' }">← 产品</router-link>
      <h2>{{ product?.name ?? productKey }}</h2>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="基本信息" name="basic">
        <el-form label-width="96px" class="basic" @submit.prevent="saveBasic">
          <el-form-item label="产品 Key">{{ productKey }}</el-form-item>
          <el-form-item label="名称" required>
            <el-input v-model="basic.name" maxlength="128" />
          </el-form-item>
          <el-form-item label="描述">
            <el-input v-model="basic.description" type="textarea" maxlength="512" />
          </el-form-item>
          <el-form-item label="解码器">
            <el-select v-model="basic.codec">
              <el-option v-for="c in codecs" :key="c.name" :label="c.displayName" :value="c.name" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="savingBasic" @click="saveBasic">保存</el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <el-tab-pane label="物模型" name="model">
        <div class="toolbar">
          <span class="hint">共 {{ properties.length }} 个属性（上限 200）</span>
          <div>
            <el-button @click="importVisible = true">导入</el-button>
            <el-button @click="exportModel">导出</el-button>
            <el-button type="primary" @click="openCreate">新增属性</el-button>
          </div>
        </div>
        <el-table v-loading="loadingProperties" :data="properties" border empty-text="暂无属性，未定义物模型时按 V0.1 规则接收数据">
          <el-table-column prop="identifier" label="标识" width="160" />
          <el-table-column prop="name" label="名称" />
          <el-table-column label="类型" width="100">
            <template #default="{ row }">{{ dataTypeLabel(row.dataType) }}</template>
          </el-table-column>
          <el-table-column label="单位" width="90">
            <template #default="{ row }">{{ row.unit ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="范围" width="160">
            <template #default="{ row }">{{ formatRange(row.minValue, row.maxValue) }}</template>
          </el-table-column>
          <el-table-column label="小数位数" width="100">
            <template #default="{ row }">{{ row.decimals ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="140">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <PropertyDialog v-model="dialogVisible" :product-key="productKey" :property="editing" @saved="onPropertiesChanged" />
    <ThingModelImportDialog v-model="importVisible" :product-key="productKey" @imported="onPropertiesChanged" />
  </div>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.header {
  display: flex;
  align-items: baseline;
  gap: 16px;
}
.header h2 {
  margin: 0;
}
.basic {
  max-width: 560px;
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
