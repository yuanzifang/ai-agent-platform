/**
 * PWA Service Worker（仅生产构建注册，见 main.ts）。
 *
 * 策略：
 * - 导航请求：网络优先，失败回退缓存的 index.html（离线可打开外壳）
 * - 静态资源（带 hash 的 js/css/图片/字体）：缓存优先，命中即返回
 * - /api/ 一律不经过 SW，避免登录态、SSE 流式输出被缓存干扰
 */
const CACHE = 'aap-static-v1'
const PRECACHE = ['/', '/manifest.webmanifest', '/icons/icon-192.png', '/icons/icon-512.png']

self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE)
      .then(cache => cache.addAll(PRECACHE))
      .catch(() => undefined)
      .then(() => self.skipWaiting())
  )
})

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(key => key !== CACHE).map(key => caches.delete(key))))
      .then(() => self.clients.claim())
  )
})

self.addEventListener('fetch', event => {
  const request = event.request
  if (request.method !== 'GET') return

  const url = new URL(request.url)
  if (url.origin !== self.location.origin) return
  if (url.pathname.startsWith('/api/')) return

  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request)
        .then(response => {
          const copy = response.clone()
          caches.open(CACHE).then(cache => cache.put('/index.html', copy)).catch(() => undefined)
          return response
        })
        .catch(() => caches.match('/index.html').then(hit => hit || caches.match('/')))
    )
    return
  }

  if (/\.(?:js|css|png|jpe?g|svg|webp|ico|woff2?|ttf)$/i.test(url.pathname)) {
    event.respondWith(
      caches.match(request).then(hit => {
        if (hit) return hit
        return fetch(request).then(response => {
          if (response.ok) {
            const copy = response.clone()
            caches.open(CACHE).then(cache => cache.put(request, copy)).catch(() => undefined)
          }
          return response
        })
      })
    )
  }
})
