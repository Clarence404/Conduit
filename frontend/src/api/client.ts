import { createHttp } from './http'
import { useAuthStore } from '@/stores/auth'
import router from '@/router'

export const http = createHttp({
  getToken: () => useAuthStore().token,
  setToken: (token) => {
    useAuthStore().token = token
  },
  onAuthFailed: () => {
    useAuthStore().clear()
    void router.push({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
  },
})
