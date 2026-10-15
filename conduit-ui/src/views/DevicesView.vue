<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deviceApi } from '@/api/devices'
import { productApi } from '@/api/products'
import { problemMessage } from '@/api/errors'
import type { DeviceView, ProductView } from '@/api/types'
import { formatDateTime } from '@/utils/format'
import { DEFAULT_PAGE_SIZE, PAGE_SIZES } from '@/utils/pagination'
import { createRequestSeq } from '@/utils/requestSeq'
import SecretDialog from '@/components/SecretDialog.vue'

const router = useRouter()

const products = ref<ProductView[]>([])
const items = ref<DeviceView[]>([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ productKey: '', keyword: '', page: 1, size: DEFAULT_PAGE_SIZE })
const productNames = computed(() => new Map(products.value.map((p) => [p.productKey, p.name])))

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ productKey: '', name: '' })

const secretVisible = ref(false)
const secret = reactive({ deviceKey: '', value: '' })

// 行级操作进行中的设备 Key，防止重复触发
const busy = reactive(new Set<string>())

async function loadProducts() {
  try {
    products.value = (await productApi.list(1, 100)).items
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

const loadSeq = createRequestSeq()

async function load() {
  const id = loadSeq.next()
  loading.value = true
  try {
    const res = await deviceApi.list({
      productKey: query.productKey || undefined,
      keyword: query.keyword || undefined,
      page: query.page,
      size: query.size,
    })
    if (!loadSeq.isLatest(id)) return
    items.value = res.items
    total.value = res.total
  } catch (e) {
    if (loadSeq.isLatest(id)) ElMessage.error(problemMessage(e))
  } finally {
    if (loadSeq.isLatest(id)) loading.value = false
  }
}

function search() {
  query.page = 1
  void load()
}

function onSizeChange() {
  query.page = 1
  void load()
}

function openCreate() {
  createForm.productKey = query.productKey || products.value[0]?.productKey || ''
  createForm.name = ''
  createVisible.value = true
}

async function create() {
  if (creating.value) {
    return
  }
  if (!createForm.productKey || !createForm.name.trim()) {
    ElMessage.warning('请选择产品并填写设备名称')
    return
  }
  creating.value = true
  try {
    const res = await deviceApi.create(createForm.productKey, createForm.name.trim())
    createVisible.value = false
    showSecret(res.device.deviceKey, res.deviceSecret)
    await load()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    creating.value = false
  }
}

function showSecret(deviceKey: string, value: string) {
  secret.deviceKey = deviceKey
  secret.value = value
  secretVisible.value = true
}

async function toggleStatus(row: DeviceView, enabled: boolean) {
  if (busy.has(row.deviceKey)) {
    return
  }
  busy.add(row.deviceKey)
  try {
    const updated = await deviceApi.update(row.deviceKey, row.name, enabled ? 'ENABLED' : 'DISABLED')
    row.status = updated.status
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    busy.delete(row.deviceKey)
  }
}

async function resetSecret(row: DeviceView) {
  if (busy.has(row.deviceKey)) {
    return
  }
  busy.add(row.deviceKey)
  try {
    try {
      await ElMessageBox.confirm(`重置后设备「${row.name}」需要使用新密钥重新连接，确定重置？`, '重置密钥', {
        type: 'warning',
      })
    } catch {
      return
    }
    try {
      const res = await deviceApi.resetSecret(row.deviceKey)
      showSecret(res.deviceKey, res.deviceSecret)
    } catch (e) {
      ElMessage.error(problemMessage(e))
    }
  } finally {
    busy.delete(row.deviceKey)
  }
}

async function remove(row: DeviceView) {
  if (busy.has(row.deviceKey)) {
    return
  }
  busy.add(row.deviceKey)
  try {
    try {
      await ElMessageBox.confirm(`确定删除设备「${row.name}」？历史数据会随保留策略过期。`, '删除设备', {
        type: 'warning',
      })
    } catch {
      return
    }
    try {
      await deviceApi.remove(row.deviceKey)
      ElMessage.success('已删除')
      if (items.value.length === 1 && query.page > 1) {
        query.page -= 1
      }
      await load()
    } catch (e) {
      ElMessage.error(problemMessage(e))
    }
  } finally {
    busy.delete(row.deviceKey)
  }
}

onMounted(async () => {
  await loadProducts()
  await load()
})
</script>

<template>
  <div>
    <div class="toolbar">
      <h2>设备</h2>
      <el-button type="primary" :disabled="products.length === 0" @click="openCreate">新建设备</el-button>
    </div>

    <el-form inline class="filters" @submit.prevent="search">
      <el-form-item label="产品">
        <el-select v-model="query.productKey" clearable placeholder="全部" style="width: 200px">
          <el-option v-for="p in products" :key="p.productKey" :label="p.name" :value="p.productKey" />
        </el-select>
      </el-form-item>
      <el-form-item label="关键字">
        <el-input v-model="query.keyword" placeholder="名称或设备 Key" clearable />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" native-type="submit">查询</el-button>
        <el-button @click="load">刷新</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="items" border>
      <el-table-column prop="deviceKey" label="设备 Key" width="180" />
      <el-table-column prop="name" label="名称" />
      <el-table-column label="产品" width="180">
        <template #default="{ row }">
          <router-link :to="{ name: 'product-detail', params: { productKey: row.productKey } }">
            {{ productNames.get(row.productKey) ?? row.productKey }}
          </router-link>
        </template>
      </el-table-column>
      <el-table-column label="在线" width="90">
        <template #default="{ row }">
          <el-tag :type="row.online ? 'success' : 'info'">{{ row.online ? '在线' : '离线' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="90">
        <template #default="{ row }">
          <el-switch
            :model-value="row.status === 'ENABLED'"
            :loading="busy.has(row.deviceKey)"
            @change="(v: string | number | boolean) => toggleStatus(row, Boolean(v))"
          />
        </template>
      </el-table-column>
      <el-table-column label="最后上报" width="180">
        <template #default="{ row }">{{ formatDateTime(row.lastReportAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button link type="primary" @click="router.push({ name: 'device-detail', params: { deviceKey: row.deviceKey } })">
            详情
          </el-button>
          <el-button link type="warning" @click="resetSecret(row)">重置密钥</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="query.page"
      v-model:page-size="query.size"
      class="pager"
      layout="total, sizes, prev, pager, next"
      :page-sizes="PAGE_SIZES"
      :total="total"
      @current-change="load"
      @size-change="onSizeChange"
    />

    <el-dialog v-model="createVisible" title="新建设备" width="480px" :close-on-click-modal="false">
      <el-form label-width="80px" @submit.prevent="create">
        <el-form-item label="产品" required>
          <el-select v-model="createForm.productKey" style="width: 100%">
            <el-option v-for="p in products" :key="p.productKey" :label="p.name" :value="p.productKey" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="createForm.name" maxlength="128" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="create">创建</el-button>
      </template>
    </el-dialog>

    <SecretDialog v-model="secretVisible" :device-key="secret.deviceKey" :secret="secret.value" />
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
.filters {
  margin-bottom: 8px;
}
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
