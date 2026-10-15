/** 路由表与登录守卫。除 meta.public 的登录页外，所有页面都挂在 AppLayout 下并要求已登录。 */

import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/** 路由表；导出供测试使用。路由名称与 test/viewTestUtils.ts 的内存路由保持一致。 */
export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true },
  },
  {
    path: '/',
    component: () => import('@/layouts/AppLayout.vue'),
    redirect: '/devices',
    children: [
      { path: 'products', name: 'products', component: () => import('@/views/ProductsView.vue') },
      {
        path: 'products/:productKey',
        name: 'product-detail',
        component: () => import('@/views/ProductDetailView.vue'),
        props: true,
      },
      { path: 'devices', name: 'devices', component: () => import('@/views/DevicesView.vue') },
      {
        path: 'devices/:deviceKey',
        name: 'device-detail',
        component: () => import('@/views/DeviceDetailView.vue'),
        props: true,
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

/**
 * 登录守卫：公开页直接放行；未登录时先尝试用 refresh Cookie 恢复会话（页面刷新场景），
 * 恢复失败再跳转登录页并带上 redirect，登录成功后回到原页面。
 */
router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }
  const auth = useAuthStore()
  if (auth.isAuthenticated || (await auth.restore())) {
    return true
  }
  return { name: 'login', query: { redirect: to.fullPath } }
})

export default router
