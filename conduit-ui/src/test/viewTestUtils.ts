/** 页面组件测试的公共工具：与真实路由同名、同 meta 的内存路由，以及等待异步渲染完成。 */

import { flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent } from 'vue'
import { ROUTE_META } from '@/router/meta'

const Empty = defineComponent({ template: '<div />' })

/** 与真实路由同名、同 meta 的内存路由，组件全部用空壳，只用来断言跳转、query、面包屑与菜单高亮 */
export function createTestRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: Empty, meta: ROUTE_META.login },
      { path: '/home', name: 'home', component: Empty, meta: ROUTE_META.home },
      { path: '/products', name: 'products', component: Empty, meta: ROUTE_META.products },
      {
        path: '/products/:productKey',
        name: 'product-detail',
        component: Empty,
        props: true,
        meta: ROUTE_META['product-detail'],
      },
      { path: '/devices', name: 'devices', component: Empty, meta: ROUTE_META.devices },
      {
        path: '/devices/:deviceKey',
        name: 'device-detail',
        component: Empty,
        props: true,
        meta: ROUTE_META['device-detail'],
      },
      { path: '/:pathMatch(.*)*', name: 'not-found', component: Empty, meta: ROUTE_META['not-found'] },
    ],
  })
}

/** 等待挂载后的异步加载和渲染都完成 */
export async function settle() {
  await flushPromises()
  await flushPromises()
}
