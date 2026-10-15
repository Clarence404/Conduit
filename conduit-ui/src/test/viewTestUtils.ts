/** 页面组件测试的公共工具：内存路由与等待异步渲染完成。 */

import { flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent } from 'vue'

const Empty = defineComponent({ template: '<div />' })

/** 与真实路由同名的内存路由，组件全部用空壳，只用来断言跳转和 query */
export function createTestRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/products', name: 'products', component: Empty },
      { path: '/products/:productKey', name: 'product-detail', component: Empty, props: true },
      { path: '/devices', name: 'devices', component: Empty },
      { path: '/devices/:deviceKey', name: 'device-detail', component: Empty, props: true },
    ],
  })
}

/** 等待挂载后的异步加载和渲染都完成 */
export async function settle() {
  await flushPromises()
  await flushPromises()
}
