/**
 * 面包屑与浏览器标题：由当前路由的 meta.breadcrumb / meta.title 生成；详情页通过 useBreadcrumb().setName()
 * 追加对象名称（面包屑最后一级，并替换标题中的页面名）。对象名称在设置它的页面卸载时清除。
 */
import { computed, onBeforeUnmount, ref, type ComputedRef } from 'vue'
import { useRoute } from 'vue-router'
import type { BreadcrumbItem } from '@/router/meta'

export const APP_NAME = 'Conduit'

const objectName = ref<string | null>(null)

/**
 * 生成面包屑：meta 中的各级，再追加对象名称（有时）；最后一级是当前页，不可点击（去掉 to）。
 * @param base 路由 meta.breadcrumb；没有时视为空
 * @param name 详情页的对象名称；没有时为 null
 */
export function buildBreadcrumb(base: readonly BreadcrumbItem[] | undefined, name: string | null): BreadcrumbItem[] {
  const items: BreadcrumbItem[] = [...(base ?? [])]
  if (name) {
    items.push({ label: name })
  }
  return items.map((item, i) => (i === items.length - 1 ? { label: item.label } : item))
}

/**
 * 浏览器标签标题：「对象名称 · Conduit」，没有对象名称时「页面名 · Conduit」，都没有时只有 Conduit。
 */
export function documentTitle(pageTitle: string | undefined, name: string | null): string {
  const head = name || pageTitle
  return head ? `${head} · ${APP_NAME}` : APP_NAME
}

/**
 * 详情页使用：设置面包屑最后一级与标题中的对象名称。组件卸载时自动清除；卸载之后才到达的 setName（如慢请求）被忽略。
 */
export function useBreadcrumb(): { setName(name: string | null): void } {
  let alive = true
  onBeforeUnmount(() => {
    alive = false
    objectName.value = null
  })
  return {
    setName(name: string | null) {
      if (alive) {
        objectName.value = name
      }
    },
  }
}

/** 布局与根组件使用：当前路由的面包屑与浏览器标题 */
export function useCurrentPage(): { breadcrumb: ComputedRef<BreadcrumbItem[]>; title: ComputedRef<string> } {
  const route = useRoute()
  return {
    breadcrumb: computed(() => buildBreadcrumb(route.meta.breadcrumb, objectName.value)),
    title: computed(() => documentTitle(route.meta.title, objectName.value)),
  }
}
