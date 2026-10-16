<script setup lang="ts">
/**
 * 租户管理（平台管理员）：筛选栏（名称/编码关键字、状态）+ 服务端分页（useTable，默认每页 10 条）；状态用标签。
 * 操作列：进入（确认后以租户管理员身份进入，停用的租户不能进入）、编辑（改名）、用户（抽屉查看用户、重置租户管理员密码）、
 * 停用/启用、删除（默认租户不显示停用/启用与删除）。「新建租户」对话框同时创建首个管理员。
 * 进入页面时从 query keyword 读取初始关键字（平台首页点击租户跳来）。首次加载显示骨架屏，没有租户时引导新建，加载失败时可重试。
 */
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { platformApi } from '@/api/platform'
import { problemMessage } from '@/api/errors'
import type { AccountStatus, TenantView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useTable } from '@/composables/useTable'
import { formatCount, formatDateTime } from '@/utils/format'
import { PAGE_SIZES } from '@/utils/pagination'
import { statusLabel } from '@/utils/permissions'
import PageHeader from '@/components/PageHeader.vue'
import FilterBar from '@/components/FilterBar.vue'
import TenantCreateDialog from '@/components/TenantCreateDialog.vue'
import TenantUsersDrawer from '@/components/TenantUsersDrawer.vue'

/** 默认租户编码：不能停用、启用或删除 */
const DEFAULT_TENANT = 'default'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const filters = reactive({
  keyword: typeof route.query.keyword === 'string' ? route.query.keyword : '',
  status: '' as '' | AccountStatus,
})
/** 已应用的筛选（点查询/回车时才从输入框复制），翻页与刷新都以它为准 */
const applied = ref({ ...filters })

const { items, total, page, size, loading, loaded, failed, load, search: searchTable, onSizeChange, onPageChange } =
  useTable<TenantView>((p, s) =>
    platformApi.listTenants({
      keyword: applied.value.keyword.trim() || undefined,
      status: applied.value.status || undefined,
      page: p,
      size: s,
    }),
  )

const createVisible = ref(false)
const editVisible = ref(false)
const editing = ref<TenantView | null>(null)
const editName = ref('')
const saving = ref(false)
const usersVisible = ref(false)
const usersTenant = ref<TenantView | null>(null)
const busy = ref(new Set<string>())

/** 应用筛选并回到第 1 页 */
function search() {
  applied.value = { ...filters }
  void searchTable()
}

/** 清空筛选并回到第 1 页 */
function reset() {
  filters.keyword = ''
  filters.status = ''
  search()
}

/** 新建成功：提示并回到第 1 页（新租户在最前） */
function onCreated(tenant: TenantView) {
  ElMessage.success(`已创建租户「${tenant.name}」，首个管理员登录后需修改密码`)
  void searchTable()
}

/** 打开改名对话框 */
function openEdit(row: TenantView) {
  editing.value = row
  editName.value = row.name
  editVisible.value = true
}

/** 保存租户名称：去首尾空白后 1～64 个字符；成功后关闭并刷新当前页 */
async function saveEdit() {
  if (saving.value || !editing.value) return
  const name = editName.value.trim()
  if (!name || name.length > 64) {
    ElMessage.warning('租户名称为 1～64 个字符')
    return
  }
  saving.value = true
  try {
    await platformApi.updateTenant(editing.value.code, { name })
    editVisible.value = false
    ElMessage.success('已保存')
    await load()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    saving.value = false
  }
}

/** 打开用户抽屉 */
function openUsers(row: TenantView) {
  usersTenant.value = row
  usersVisible.value = true
}

/** 进入租户：确认后换成代入令牌并去首页（此时首页显示该租户的数据）；取消则什么也不做 */
async function enter(row: TenantView) {
  try {
    await ElMessageBox.confirm(`将以租户管理员权限操作租户「${row.name}」的数据，所有修改都会被记录`, '进入租户', {
      type: 'warning',
      confirmButtonText: '进入',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await auth.enterTenant(row.code)
    await router.push({ name: 'home' })
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

/** 停用（先确认）或启用租户，成功后刷新当前页；操作中的行按钮显示加载 */
async function toggleStatus(row: TenantView) {
  const disabling = row.status === 'ACTIVE'
  if (disabling) {
    try {
      await ElMessageBox.confirm(`停用后，租户「${row.name}」的用户无法登录、设备无法接入。确定停用？`, '停用租户', {
        type: 'warning',
      })
    } catch {
      return
    }
  }
  busy.value.add(row.code)
  try {
    await (disabling ? platformApi.disableTenant(row.code) : platformApi.enableTenant(row.code))
    ElMessage.success(disabling ? '已停用' : '已启用')
    await load()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    busy.value.delete(row.code)
  }
}

/** 删除租户（先确认）：其用户一并删除；租户下仍有产品或设备时服务端返回 409 并提示 */
async function remove(row: TenantView) {
  try {
    await ElMessageBox.confirm(
      `确定删除租户「${row.name}」？其用户将一并删除；租户下仍有产品或设备时不能删除。`,
      '删除租户',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await platformApi.removeTenant(row.code)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div>
    <PageHeader title="租户" subtitle="平台上的租户：各租户的产品、设备与用户相互隔离。">
      <template #actions>
        <el-button type="primary" :icon="Plus" @click="createVisible = true">新建租户</el-button>
      </template>
    </PageHeader>

    <div class="cd-panel">
      <FilterBar @search="search" @reset="reset">
        <el-form-item label="关键字">
          <el-input v-model="filters.keyword" placeholder="名称或编码" clearable />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status" placeholder="全部" clearable class="status-select">
            <el-option label="启用" value="ACTIVE" />
            <el-option label="停用" value="DISABLED" />
          </el-select>
        </el-form-item>
      </FilterBar>

      <el-skeleton v-if="!loaded" :rows="5" animated />
      <template v-else>
        <el-table v-loading="loading" :data="items">
          <el-table-column label="名称" min-width="180">
            <template #default="{ row }">
              {{ row.name }}
              <div class="cd-muted">{{ row.code }}</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
                {{ statusLabel(row.status, 'tenant') }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="用户（管理员）" width="130">
            <template #default="{ row }">{{ row.userCount }}（{{ row.adminCount }}）</template>
          </el-table-column>
          <el-table-column prop="productCount" label="产品" width="80" />
          <el-table-column label="设备（在线/总数）" width="150">
            <template #default="{ row }">
              <span class="device-count">{{ formatCount(row.onlineCount) }} / {{ row.deviceCount }}</span>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="180">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="300">
            <template #default="{ row }">
              <el-button link type="primary" :disabled="row.status !== 'ACTIVE'" @click="enter(row)">进入</el-button>
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="primary" @click="openUsers(row)">用户</el-button>
              <template v-if="row.code !== DEFAULT_TENANT">
                <el-button
                  link
                  :type="row.status === 'ACTIVE' ? 'warning' : 'success'"
                  :loading="busy.has(row.code)"
                  @click="toggleStatus(row)"
                >
                  {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
                </el-button>
                <el-button link type="danger" @click="remove(row)">删除</el-button>
              </template>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty v-if="failed" description="加载失败，点击重试">
              <el-button type="primary" @click="load">重试</el-button>
            </el-empty>
            <el-empty v-else-if="applied.keyword || applied.status" description="没有符合条件的租户">
              <el-button @click="reset">重置筛选</el-button>
            </el-empty>
            <el-empty v-else description="还没有租户，先新建一个租户">
              <el-button type="primary" @click="createVisible = true">新建租户</el-button>
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

    <TenantCreateDialog v-model="createVisible" @created="onCreated" />

    <el-dialog v-model="editVisible" title="编辑租户" width="min(420px, 92vw)" :close-on-click-modal="false">
      <el-form label-width="80px" @submit.prevent="saveEdit">
        <el-form-item label="编码">
          <el-input :model-value="editing?.code" disabled />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="editName" maxlength="64" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <TenantUsersDrawer v-model="usersVisible" :tenant="usersTenant" />
  </div>
</template>

<style scoped>
.status-select {
  width: 120px;
}
</style>
