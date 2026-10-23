import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'
import router from '../router'

const http = axios.create({ baseURL: '/api/v1', timeout: 30000, withCredentials: true })

http.interceptors.request.use((config) => {
  const user = useUserStore()
  if (user.accessToken) config.headers.Authorization = `Bearer ${user.accessToken}`
  config.headers['Accept-Language'] = localStorage.getItem('locale') === 'en-US' ? 'en-US' : 'zh-CN'
  return config
})

let refreshing: Promise<string> | null = null

http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && body.code !== undefined && body.code !== 0) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(body)
    }
    return body?.data !== undefined ? body.data : body
  },
  async (error) => {
    const user = useUserStore()
    const status = error.response?.status
    const url = String(error.config?.url || '')
    if (status === 401 && !error.config?._retry
        && !url.includes('/auth/login') && !url.includes('/auth/refresh')) {
      // 401 → 用 HttpOnly Refresh Cookie 静默换 access token 后重放一次
      error.config._retry = true
      try {
        refreshing = refreshing ?? user.doRefresh()
        await refreshing
        refreshing = null
        return http.request(error.config)
      } catch {
        refreshing = null
        user.clear()
        router.push('/login')
      }
    } else {
      ElMessage.error(error.response?.data?.message || '网络异常，请重试')
    }
    return Promise.reject(error)
  }
)

export default http
