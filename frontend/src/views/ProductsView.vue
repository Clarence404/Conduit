<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { productApi } from '@/api/products'
import { problemMessage } from '@/api/errors'
import type { ProductView } from '@/api/types'
import { formatDateTime } from '@/utils/format'

const items = ref<ProductView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

const dialogVisible = ref(false)
const editingKey = ref<string | null>(null)
const saving = ref(false)
const form = reactive({ name: '', description: '' })

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

function openCreate() {
  editingKey.value = null
  form.name = ''
  form.description = ''
  dialogVisible.value = true
}

function openEdit(row: ProductView) {
  editingKey.value = row.productKey
  form.name = row.name
  form.description = row.description ?? ''
  dialogVisible.value = true
}

async function save() {
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
      await productApi.create(req)
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
  try {
    await ElMessageBox.confirm(`确定删除产品「${row.name}」？`, '删除产品', { type: 'warning' })
  } catch {
    return
  }
  try {
    await productApi.remove(row.productKey)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <h2>产品</h2>
      <el-button type="primary" @click="openCreate">新建产品</el-button>
    </div>

    <el-table v-loading="loading" :data="items" border>
      <el-table-column prop="productKey" label="产品 Key" width="200" />
      <el-table-column prop="name" label="名称" />
      <el-table-column prop="description" label="描述" />
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="page"
      v-model:page-size="size"
      class="pager"
      layout="total, prev, pager, next"
      :total="total"
      @current-change="load"
    />

    <el-dialog v-model="dialogVisible" :title="editingKey ? '编辑产品' : '新建产品'" width="480px">
      <el-form label-width="80px" @submit.prevent="save">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" maxlength="512" />
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
