/**
 * 路由元信息：页面名称（浏览器标题）、面包屑与侧栏菜单归属。
 * 真实路由表（router/index.ts）与测试用内存路由（test/viewTestUtils.ts）共用 ROUTE_META，保证两边一致。
 */
import type { RouteLocationRaw, RouteMeta } from 'vue-router'

/** 面包屑的一级；有 to 时可点击跳转 */
export interface BreadcrumbItem {
  label: string
  to?: RouteLocationRaw
}

declare module 'vue-router' {
  interface RouteMeta {
    /** 不需要登录即可访问（登录页） */
    public?: boolean
    /** 页面名称，用于浏览器标签标题「<页面名> · Conduit」 */
    title?: string
    /** 面包屑（详情页的对象名称不在这里，由 useBreadcrumb 追加为最后一级） */
    breadcrumb?: BreadcrumbItem[]
    /** 侧栏中高亮的菜单路径：列表页与详情页归同一个菜单 */
    menu?: string
  }
}

/** 各路由的元信息，键为路由名称 */
export const ROUTE_META = {
  login: { public: true, title: '登录' },
  products: { title: '产品', menu: '/products', breadcrumb: [{ label: '设备接入' }, { label: '产品' }] },
  'product-detail': {
    title: '产品详情',
    menu: '/products',
    breadcrumb: [{ label: '设备接入' }, { label: '产品', to: { name: 'products' } }],
  },
  devices: { title: '设备', menu: '/devices', breadcrumb: [{ label: '设备接入' }, { label: '设备' }] },
  'device-detail': {
    title: '设备详情',
    menu: '/devices',
    breadcrumb: [{ label: '设备接入' }, { label: '设备', to: { name: 'devices' } }],
  },
  'not-found': { title: '页面不存在', breadcrumb: [{ label: '页面不存在' }] },
} satisfies Record<string, RouteMeta>
