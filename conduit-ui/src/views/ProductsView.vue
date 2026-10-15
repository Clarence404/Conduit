<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { productApi } from '@/api/products'
import { codecApi } from '@/api/codecs'
import { problemMessage } from '@/api/errors'
import type { CodecView, ProductView } from '@/api/types'
import { formatDateTime } from '@/utils/format'
import { codecLabel } from '@/utils/thingModel'
import { DEFAULT_PAGE_SIZE, PAGE_SIZES } from '@/utils/pagination'
import { tabQuery, type ProductTab } from '@/utils/productTabs'

const router = useRouter()

const items = ref<ProductView[]>([])
const codecs = ref<CodecView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(DEFAULT_PAGE_SIZE)
const loading = ref(false)

const dialogVisible = ref(false)
const editingKey = ref<string | null>(null)
const saving = ref(false)
const removing = ref(false)
const form = reactive({ name: '', description: '', codec: 'standard-json' })

async function load() {
  loading.value = true
  try {
    const res = await productApi.list(page.value, size.value)
    items.value = res.items
    total.value = res.total
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    loading.value = false
  }
}

async function loadCodecs() {
  try {
    codecs.value = await codecApi.list()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

function openDetail(row: ProductView, tab: ProductTab = 'basic') {
  void router.push({ name: 'product-detail', params: { productKey: row.productKey }, query: tabQuery({}, tab) })
}

function onSizeChange() {
  page.value = 1
  void load()
}

function openCreate() {
  editingKey.value = null
  form.name = ''
  form.description = ''
  form.codec = 'standard-json'
  dialogVisible.value = true
}

function openEdit(row: ProductView) {
  editingKey.value = row.productKey
  form.name = row.name
  form.description = row.description ?? ''
  form.codec = row.codec
  dialogVisible.value = true
}

async function save() {
  if (saving.value) return
  if (!form.name.trim()) {
    ElMessage.warning('请输入产品名称')
    return
  }
  saving.value = true
  const req = { name: form.name.trim(), description: form.description.trim() || null }
  try {
    if (editingKey.value) {
      await productApi.update(editingKey.value, req)
    } else {
      await productApi.create({ ...req, codec: form.codec })
    }
    dialogVisible.value = false
    ElMessage.success('已保存')
    await load()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    saving.value = false
  }
}

async function remove(row: ProductView) {
  if (removing.value) return
  try {
    await ElMessageBox.confirm(`确定删除产品「${row.name}」？`, '删除产品', { type: 'warning' })
  } catch {
    return
  }
  removing.value = true
  try {
    await productApi.remove(row.productKey)
    ElMessage.success('已删除')
    if (items.value.length === 1 && page.value > 1) {
      page.value--
    }
    await load()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    removing.value = false
  }
}

onMounted(() => {
  void load()
  void loadCodecs()
})
</script>

<template>
  <div>
    <div class="toolbar">
      <h2>产品</h2>
      <el-button type="primary" @click="openCreate">新建产品</el-button>
    </div>

    <el-table v-loading="loading" :data="items" border>
      <el-table-column prop="productKey" label="产品 Key" width="200" />
      <el-table-column label="名称">
        <template #default="{ row }">
          <router-link :to="{ name: 'product-detail', params: { productKey: row.productKey } }">{{ row.name }}</router-link>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" />
      <el-table-column label="解码器" width="120">
        <template #default="{ row }">{{ codecLabel(codecs, row.codec) }}</template>
      </el-table-column>
      <el-table-column prop="propertyCount" label="属性数" width="90" />
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="230">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button link type="primary" @click="openDetail(row, 'model')">物模型</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="page"
      v-model:page-size="size"
      class="pager"
      layout="total, sizes, prev, pager, next"
      :page-sizes="PAGE_SIZES"
      :total="total"
      @current-change="load"
      @size-change="onSizeChange"
    />

    <el-dialog
      v-model="dialogVisible"
      :title="editingKey ? '编辑产品' : '新建产品'"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-form label-width="80px" @submit.prevent="save">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" maxlength="512" />
        </el-form-item>
        <el-form-item v-if="!editingKey" label="解码器">
          <el-select v-model="form.codec">
            <el-option v-for="c in codecs" :key="c.name" :label="c.displayName" :value="c.name" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.toolbar h2 {
  margin: 0;
}
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
