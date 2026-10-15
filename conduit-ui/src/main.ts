/**
 * 前端入口：创建应用并安装 Pinia、路由与 Element Plus（中文语言包）；引入 Element Plus 暗色变量与应用设计变量，
 * 并在挂载前按外观偏好应用主题（避免首屏闪烁），最后挂载到 #app。
 */

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/tokens.css'
import App from './App.vue'
import router from './router'
import { useUiStore } from './stores/ui'
import { installTheme } from './composables/useTheme'

const pinia = createPinia()
const app = createApp(App).use(pinia)
const ui = useUiStore(pinia)
installTheme(() => ui.theme)
app.use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
