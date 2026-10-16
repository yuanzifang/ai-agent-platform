import { defineStore } from 'pinia'
import http from '../api/http'

export const useUserStore = defineStore('user', {
  state: () => ({
    accessToken: localStorage.getItem('accessToken') || '',
    refreshToken: localStorage.getItem('refreshToken') || '',
    userInfo: JSON.parse(localStorage.getItem('userInfo') || 'null') as
      { id: string; displayName: string; isPlatformAdmin: boolean; tenantId: string | null } | null
  }),
  actions: {
    async login(username: string, password: string, tenantKey?: string) {
      const data = await http.post('/auth/login', { username, password, tenantKey }) as any
      this.accessToken = data.accessToken
      this.refreshToken = data.refreshToken
      this.userInfo = data.user
      localStorage.setItem('accessToken', data.accessToken)
      localStorage.setItem('refreshToken', data.refreshToken)
      localStorage.setItem('userInfo', JSON.stringify(data.user))
    },
    async doRefresh() {
      const data = await http.post('/auth/refresh', { refreshToken: this.refreshToken }) as any
      this.accessToken = data.accessToken
      localStorage.setItem('accessToken', data.accessToken)
      return data.accessToken
    },
    async logout() {
      try { await http.post('/auth/logout', { refreshToken: this.refreshToken }) } catch { /* 忽略 */ }
      this.clear()
    },
    clear() {
      this.accessToken = ''
      this.refreshToken = ''
      this.userInfo = null
      localStorage.removeItem('accessToken')
      localStorage.removeItem('refreshToken')
      localStorage.removeItem('userInfo')
    }
  }
})
