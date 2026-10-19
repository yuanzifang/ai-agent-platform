import { defineStore } from 'pinia'
import http from '../api/http'

export const useUserStore = defineStore('user', {
  state: () => ({
    accessToken: localStorage.getItem('accessToken') || '',
    refreshToken: localStorage.getItem('refreshToken') || '',
    userInfo: JSON.parse(localStorage.getItem('userInfo') || 'null') as
      { id: string; displayName: string; isPlatformAdmin: boolean; tenantId: string | null;
      roles?: string[]; permissions?: string[] } | null
  }),
  actions: {
    async login(username: string, password: string, tenantKey?: string) {
      const data = await http.post('/auth/login', { username, password, tenantKey }) as any
      if (data.accessToken) this.applyLogin(data)
      return data
    },
    applyLogin(data: any) {
      this.accessToken = data.accessToken
      this.refreshToken = data.refreshToken
      this.userInfo = data.user
      localStorage.setItem('accessToken', data.accessToken)
      localStorage.setItem('refreshToken', data.refreshToken)
      localStorage.setItem('userInfo', JSON.stringify(data.user))
    },
    async refreshProfile() {
      try {
        const me = await http.get('/auth/me') as any
        if (!me) return
        this.userInfo = {
          ...(this.userInfo || { displayName: '' }),
          id: me.id,
          tenantId: me.tenantId,
          isPlatformAdmin: me.isPlatformAdmin,
          roles: me.roles || [],
          permissions: me.permissions || []
        }
        localStorage.setItem('userInfo', JSON.stringify(this.userInfo))
      } catch {
        // 保留本地登录信息，权限刷新失败不阻断页面
      }
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
