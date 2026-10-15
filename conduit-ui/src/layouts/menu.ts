/** 侧栏菜单定义：分组、菜单项（路径、名称、图标），以及尚未实现、显示为禁用的项（disabledNote 为标注文字）。 */
import type { Component } from 'vue'
import { Box, Cpu, House, OfficeBuilding, User } from '@element-plus/icons-vue'

/** 菜单项；path 同时是 el-menu 的 index 与跳转地址 */
export interface MenuItem {
  path: string
  label: string
  icon: Component
  /** 有值时菜单项禁用，并显示这段标注（如「V0.2-C」） */
  disabledNote?: string
}

/** 菜单分组 */
export interface MenuGroup {
  title: string
  items: MenuItem[]
}

/**
 * 侧栏菜单的全部分组，按显示顺序排列：概览、设备接入、系统管理（用户与租户尚未实现，带 disabledNote 显示为禁用）。
 * 菜单项 path 与路由 meta.menu 对应，用来在详情页高亮所属菜单。
 */
export const MENU_GROUPS: MenuGroup[] = [
  {
    title: '概览',
    items: [{ path: '/home', label: '首页', icon: House }],
  },
  {
    title: '设备接入',
    items: [
      { path: '/products', label: '产品', icon: Box },
      { path: '/devices', label: '设备', icon: Cpu },
    ],
  },
  {
    title: '系统管理',
    items: [
      { path: '/users', label: '用户', icon: User, disabledNote: 'V0.2-C' },
      { path: '/tenants', label: '租户', icon: OfficeBuilding, disabledNote: 'V0.2-C' },
    ],
  },
]
