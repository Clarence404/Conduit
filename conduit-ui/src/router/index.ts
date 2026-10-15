/**
 * 路由表与登录守卫。除 meta.public 的登录页外，所有页面都挂在 AppLayout 下并要求已登录；
 * 每个路由带 meta（页面名称、面包屑、菜单归属，见 router/meta.ts）；未匹配的地址在布局内显示 404 页。
 */

import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ROUTE_META } from './meta'

/** 路由表；导出供测试使用。路由名称与 meta 与 test/viewTestUtils.ts 的内存路由保持一致。 */
export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: ROUTE_META.login,
  },
  {
    path: '/',
    component: () => import('@/layouts/AppLayout.vue'),
    redirect: '/devices',
    children: [
      {
        path: 'products',
        name: 'products',
        component: () => import('@/views/ProductsView.vue'),
        meta: ROUTE_META.products,
      },
      {
        path: 'products/:productKey',
        name: 'product-detail',
        component: () => import('@/views/ProductDetailView.vue'),
        props: true,
        meta: ROUTE_META['product-detail'],
      },
      {
        path: 'devices',
        name: 'devices',
        component: () => import('@/views/DevicesView.vue'),
        meta: ROUTE_META.devices,
      },
      {
        path: 'devices/:deviceKey',
        name: 'device-detail',
        component: () => import('@/views/DeviceDetailView.vue'),
        props: true,
        meta: ROUTE_META['device-detail'],
      },
      {
        path: ':pathMatch(.*)*',
        name: 'not-found',
        component: () => import('@/views/NotFoundView.vue'),
        meta: ROUTE_META['not-found'],
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
