import { defineStore } from 'pinia'
import http from '../api/http'

export const useUserStore = defineStore('user', {
  state: () => ({
    accessToken: localStorage.getItem('accessToken') || '',
    bootstrapped: false,
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
      this.userInfo = data.user
      this.bootstrapped = true
      localStorage.setItem('accessToken', data.accessToken)
      localStorage.setItem('userInfo', JSON.stringify(data.user))
      // 清理旧版本遗留的 refresh token（现在只在 HttpOnly Cookie）
      localStorage.removeItem('refreshToken')
    },
    /** 页面刷新/新标签页：用 HttpOnly Cookie 换新 access token。 */
    async bootstrap() {
      localStorage.removeItem('refreshToken')
      if (this.accessToken) {
        this.bootstrapped = true
        return
      }
      try {
        await this.doRefresh()
        await this.refreshProfile()
      } catch {
        this.clear()
      } finally {
        this.bootstrapped = true
      }
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
      const data = await http.post('/auth/refresh', {}, { withCredentials: true }) as any
      this.accessToken = data.accessToken
      localStorage.setItem('accessToken', data.accessToken)
      return data.accessToken
    },
    async logout() {
      try {
        await http.post('/auth/logout', {}, { withCredentials: true })
      } catch { /* 忽略 */ }
      this.clear()
    },
    clear() {
      this.accessToken = ''
      this.userInfo = null
      localStorage.removeItem('accessToken')
      localStorage.removeItem('refreshToken')
      localStorage.removeItem('userInfo')
    }
  }
})
