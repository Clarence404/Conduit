<script setup lang="ts">
/**
 * 设备列表页：筛选栏（产品、关键字，回车即查询）+ 服务端分页（useTable，默认每页 10 条）；在线状态用圆点，启用状态用标签，
 * 最后上报显示相对时间。支持新建设备、启用/禁用、重置密钥、删除，行内可跳到设备详情与所属产品。
 * 创建设备和重置密钥的响应里有密钥明文，通过 SecretDialog 只展示一次。首次加载显示骨架屏，没有设备时引导新建，加载失败时改为「加载失败，点击重试」。
 * 查询只使用「已应用」的筛选条件（点查询/回车时才从输入框复制）：翻页、刷新、新建默认产品、空态判断都以它为准，不受尚未提交的输入影响。
 */

import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { deviceApi } from '@/api/devices'
import { productApi } from '@/api/products'
import { problemMessage } from '@/api/errors'
import type { DeviceStatus, DeviceView, ProductView } from '@/api/types'
import { PAGE_SIZES } from '@/utils/pagination'
import { useTable } from '@/composables/useTable'
import PageHeader from '@/components/PageHeader.vue'
import FilterBar from '@/components/FilterBar.vue'
import OnlineDot from '@/components/OnlineDot.vue'
import RelativeTime from '@/components/RelativeTime.vue'
import SecretDialog from '@/components/SecretDialog.vue'

const router = useRouter()

const products = ref<ProductView[]>([])
const productNames = computed(() => new Map(products.value.map((p) => [p.productKey, p.name])))
// 筛选栏里正在编辑的输入；点「查询」才复制到 applied
const filters = reactive({ productKey: '', keyword: '' })
// 最近一次查询所用的筛选条件：翻页、刷新、空态与新建默认产品都读它
const applied = reactive({ productKey: '', keyword: '' })
const filtered = computed(() => Boolean(applied.productKey || applied.keyword))

const { items, total, page, size, loading, loaded, failed, load, search: searchPage, onSizeChange, onPageChange } = useTable<DeviceView>(
  (p, s) =>
    deviceApi.list({
      productKey: applied.productKey || undefined,
      keyword: applied.keyword || undefined,
      page: p,
      size: s,
    }),
)

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ productKey: '', name: '' })

const secretVisible = ref(false)
const secret = reactive({ deviceKey: '', value: '' })

// 行级操作进行中的设备 Key，防止重复触发
const busy = reactive(new Set<string>())

/** 加载产品列表，供筛选下拉、新建表单和产品名称显示使用（最多取 100 个）。 */
async function loadProducts() {
  try {
    products.value = (await productApi.list(1, 100)).items
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

/** 应用输入框里的筛选条件（关键字去首尾空格）并回到第 1 页查询。 */
function search() {
  applied.productKey = filters.productKey
  applied.keyword = filters.keyword.trim()
  return searchPage()
}

/** 清空筛选条件（输入与已应用的）并回到第 1 页查询。 */
function reset() {
  filters.productKey = ''
  filters.keyword = ''
  void search()
}

/** 打开新建对话框：产品默认取已应用筛选的产品，没有筛选则取第一个产品。 */
function openCreate() {
  createForm.productKey = applied.productKey || products.value[0]?.productKey || ''
  createForm.name = ''
  createVisible.value = true
}

/** 创建设备：成功后关闭对话框、展示一次性密钥并刷新列表。创建中忽略重复点击。 */
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

/** 弹出密钥对话框，展示设备密钥（只此一次）。 */
function showSecret(deviceKey: string, value: string) {
  secret.deviceKey = deviceKey
  secret.value = value
  secretVisible.value = true
}

/**
 * 启用/禁用设备（操作列按钮）：切换为相反状态，成功后用服务端返回的状态回写该行；失败时提示，该行保持不变。
 * 同一设备操作进行中时忽略重复触发。
 */
async function toggleStatus(row: DeviceView) {
  if (busy.has(row.deviceKey)) {
    return
  }
  busy.add(row.deviceKey)
  const next: DeviceStatus = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  try {
    const updated = await deviceApi.update(row.deviceKey, row.name, next)
    row.status = updated.status
    ElMessage.success(updated.status === 'ENABLED' ? '已启用' : '已禁用')
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    busy.delete(row.deviceKey)
  }
}

/** 重置设备密钥：先确认（旧密钥立即失效），确认后展示新密钥。用户取消则不请求。同一设备操作进行中时忽略重复触发。 */
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

/** 删除设备：先确认再请求；成功后重新加载（删掉当前页最后一条时 useTable 自动退到上一页）。 */
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
      await load()
    } catch (e) {
      ElMessage.error(problemMessage(e))
    }
  } finally {
    busy.delete(row.deviceKey)
  }
}

/** 进入页面：产品列表与设备列表并行加载（产品名称只用于显示，晚到也会更新）。 */
onMounted(() => {
  void loadProducts()
  void load()
})
</script>

<template>
  <div>
    <PageHeader title="设备" subtitle="接入平台的设备：查看在线状态与最后上报，管理启用状态与密钥。">
      <template #actions>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" :disabled="products.length === 0" @click="openCreate">新建设备</el-button>
      </template>
    </PageHeader>

    <div class="cd-panel">
      <FilterBar @search="search" @reset="reset">
        <el-form-item label="产品">
          <el-select v-model="filters.productKey" clearable placeholder="全部" class="product-select">
            <el-option v-for="p in products" :key="p.productKey" :label="p.name" :value="p.productKey" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="filters.keyword" class="keyword" placeholder="名称或设备 Key" clearable />
        </el-form-item>
      </FilterBar>

      <el-skeleton v-if="!loaded" :rows="5" animated />
      <template v-else>
        <el-table v-loading="loading" :data="items">
          <el-table-column label="名称" min-width="200">
            <template #default="{ row }">
              <router-link :to="{ name: 'device-detail', params: { deviceKey: row.deviceKey } }">{{ row.name }}</router-link>
              <div class="cd-muted">{{ row.deviceKey }}</div>
            </template>
          </el-table-column>
          <el-table-column label="产品" min-width="160">
            <template #default="{ row }">
              <router-link :to="{ name: 'product-detail', params: { productKey: row.productKey } }">
                {{ productNames.get(row.productKey) ?? row.productKey }}
              </router-link>
            </template>
          </el-table-column>
          <el-table-column label="在线" width="90">
            <template #default="{ row }"><OnlineDot :online="row.online" /></template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">
                {{ row.status === 'ENABLED' ? '启用' : '禁用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最后上报" width="120">
            <template #default="{ row }"><RelativeTime :value="row.lastReportAt" /></template>
          </el-table-column>
          <el-table-column label="操作" width="230">
            <template #default="{ row }">
              <el-button link type="primary" @click="router.push({ name: 'device-detail', params: { deviceKey: row.deviceKey } })">
                详情
              </el-button>
              <el-button link :type="row.status === 'ENABLED' ? 'warning' : 'success'" :loading="busy.has(row.deviceKey)" @click="toggleStatus(row)">
                {{ row.status === 'ENABLED' ? '禁用' : '启用' }}
              </el-button>
              <el-button link type="warning" @click="resetSecret(row)">重置密钥</el-button>
              <el-button link type="danger" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty v-if="failed" description="加载失败，点击重试">
              <el-button type="primary" @click="load">重试</el-button>
            </el-empty>
            <el-empty v-else-if="filtered" description="没有符合条件的设备">
              <el-button @click="reset">重置筛选</el-button>
            </el-empty>
            <el-empty v-else :description="products.length === 0 ? '还没有产品，先到「产品」页创建产品' : '还没有设备，先新建一台设备'">
              <el-button v-if="products.length === 0" type="primary" @click="router.push({ name: 'products' })">去创建产品</el-button>
              <el-button v-else type="primary" @click="openCreate">新建设备</el-button>
            </el-empty>
          </template>
        </el-table>

        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          class="cd-pager"
          layout="total, sizes, prev, pager, next"
          :page-sizes="PAGE_SIZES"
          :total="total"
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </template>
    </div>

    <el-dialog v-model="createVisible" title="新建设备" width="min(480px, 92vw)" :close-on-click-modal="false">
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
.product-select {
  width: 200px;
}
</style>
