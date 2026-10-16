/**
 * 路由访问规则（身份与页面是否匹配）：未代入的平台管理员进入租户页面时带到租户管理；其他身份进入平台页面时带到首页；
 * 不能管理用户的身份进入用户管理时带到首页。路由守卫与 AppLayout（会话身份变化时）共用。只做体验上的引导，权限以后端为准。
 */
import type { RouteLocationRaw, RouteMeta } from 'vue-router'
import type { UserView } from '@/api/types'
import { permissionsOf } from '@/utils/permissions'

/**
 * 计算应当改去的地址。
 * @param to 目标路由（只用到 meta）
 * @param user 当前用户
 * @returns 需要改去的地址；可以访问时为 null
 */
export function accessRedirect(to: { meta: RouteMeta }, user: UserView | null): RouteLocationRaw | null {
  const p = permissionsOf(user)
  if (to.meta.scope === 'platform' && !p.isPlatformAdmin) {
    return { name: 'home' }
  }
  if (to.meta.scope === 'tenant' && p.isPlatformAdmin) {
    return { name: 'tenants' }
  }
  if (to.meta.requires === 'manageUsers' && !p.canManageUsers) {
    return { name: 'home' }
  }
  return null
}
