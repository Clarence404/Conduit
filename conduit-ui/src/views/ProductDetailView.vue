<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { productApi } from '@/api/products'
import { deviceApi } from '@/api/devices'
import { codecApi } from '@/api/codecs'
import { thingModelApi } from '@/api/thingModel'
import { problemMessage } from '@/api/errors'
import type { CodecView, DeviceView, ProductView, PropertyView } from '@/api/types'
import { dataTypeLabel, formatRange } from '@/utils/thingModel'
import { formatDateTime } from '@/utils/format'
import { copyText } from '@/utils/clipboard'
import { clampPage, DEFAULT_PAGE_SIZE, PAGE_SIZES, pageSlice } from '@/utils/pagination'
import { tabFromQuery, tabQuery, type ProductTab } from '@/utils/productTabs'
import { downloadJson } from '@/utils/download'
import PropertyDialog from '@/components/PropertyDialog.vue'
import ThingModelImportDialog from '@/components/ThingModelImportDialog.vue'

const props = defineProps<{ productKey: string }>()

const route = useRoute()
const router = useRouter()

// 当前标签与 ?tab= 同步；用 replace 切换，不产生历史记录
const activeTab = computed<ProductTab>({
  get: () => tabFromQuery(route.query.tab),
  set: (tab) => {
    void router.replace({ query: tabQuery(route.query, tab) })
  },
})

const product = ref<ProductView | null>(null)
const codecs = ref<CodecView[]>([])
const properties = ref<PropertyView[]>([])
const propertyPage = ref(1)
const propertySize = ref(DEFAULT_PAGE_SIZE)
const pagedProperties = computed(() => pageSlice(properties.value, propertyPage.value, propertySize.value))
const basic = reactive({ name: '', description: '', codec: '' })
const savingBasic = ref(false)
const loadingProperties = ref(false)
const dialogVisible = ref(false)
const editing = ref<PropertyView | null>(null)
const importVisible = ref(false)

const devices = ref<DeviceView[]>([])
const deviceTotal = ref(0)
const devicePage = ref(1)
const deviceSize = ref(DEFAULT_PAGE_SIZE)
const loadingDevices = ref(false)

// 删除、导入让属性变少后，当前页不能落在末页之后
watch([() => properties.value.length, propertySize], ([total, size]) => {
  propertyPage.value = clampPage(propertyPage.value, total, size)
})

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

/** 属性变化后只刷新属性数，不重置基本信息表单里尚未保存的修改 */
async function refreshPropertyCount() {
  try {
    const fresh = await productApi.get(props.productKey)
    if (product.value) {
      product.value = { ...product.value, propertyCount: fresh.propertyCount }
    }
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

async function copyProductKey() {
  if (await copyText(props.productKey)) {
    ElMessage.success('已复制')
  } else {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}

async function loadDevices() {
  loadingDevices.value = true
  try {
    const res = await deviceApi.list({ productKey: props.productKey, page: devicePage.value, size: deviceSize.value })
    devices.value = res.items
    deviceTotal.value = res.total
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    loadingDevices.value = false
  }
}

function onDeviceSizeChange() {
  devicePage.value = 1
  void loadDevices()
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
  await Promise.all([loadProperties(), refreshPropertyCount()])
}

// 每次切到「设备」标签都重新拉取，在线状态保持最新
watch(
  activeTab,
  (tab) => {
    if (tab === 'devices') {
      void loadDevices()
    }
  },
  { immediate: true },
)

onMounted(() => {
  void loadProduct()
  void loadCodecs()
  void loadProperties()
})
</script>

<template>
  <div class="detail">
    <el-breadcrumb separator="/">
      <el-breadcrumb-item :to="{ name: 'products' }">产品</el-breadcrumb-item>
      <el-breadcrumb-item>{{ product?.name ?? productKey }}</el-breadcrumb-item>
    </el-breadcrumb>
    <div class="header">
      <h2>{{ product?.name ?? productKey }}</h2>
      <span class="key">
        产品 Key：<code>{{ productKey }}</code>
        <el-button link type="primary" @click="copyProductKey">复制</el-button>
      </span>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="基本信息" name="basic">
        <el-form label-width="96px" class="basic" @submit.prevent="saveBasic">
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
        <el-table v-loading="loadingProperties" :data="pagedProperties" border empty-text="暂无属性，未定义物模型时按 V0.1 规则接收数据">
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
        <el-pagination
          v-model:current-page="propertyPage"
          v-model:page-size="propertySize"
          class="pager"
          layout="total, sizes, prev, pager, next"
          :page-sizes="PAGE_SIZES"
          :total="properties.length"
        />
      </el-tab-pane>

      <el-tab-pane label="设备" name="devices">
        <el-table v-loading="loadingDevices" :data="devices" border empty-text="该产品下暂无设备">
          <el-table-column label="名称">
            <template #default="{ row }">
              <router-link :to="{ name: 'device-detail', params: { deviceKey: row.deviceKey } }">{{ row.name }}</router-link>
            </template>
          </el-table-column>
          <el-table-column prop="deviceKey" label="设备 Key" width="200" />
          <el-table-column label="在线" width="90">
            <template #default="{ row }">
              <el-tag :type="row.online ? 'success' : 'info'">{{ row.online ? '在线' : '离线' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="180">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="devicePage"
          v-model:page-size="deviceSize"
          class="pager"
          layout="total, sizes, prev, pager, next"
          :page-sizes="PAGE_SIZES"
          :total="deviceTotal"
          @current-change="loadDevices"
          @size-change="onDeviceSizeChange"
        />
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
.key {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.pager {
  margin-top: 16px;
  justify-content: flex-end;
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
