# CSP 与安全响应头

## 1. 前端 Nginx

`frontend/nginx.conf` 增加：

```nginx
add_header Content-Security-Policy "default-src 'self'; base-uri 'self'; frame-ancestors 'none'; object-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https:; font-src 'self' data:; connect-src 'self' ws: wss:; media-src 'self' blob: data:; form-action 'self'" always;
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "DENY" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
add_header Permissions-Policy "camera=(self), microphone=(self), geolocation=()" always;
```

说明：

- `script-src 'self'`：不允许外部脚本/内联脚本；Vite 生产构建脚本同源。
- `style-src 'unsafe-inline'`：Element Plus/DOM 动态样式需要；这是当前兼容性取舍。
- `img-src https:`：Markdown 外部图片兼容；如需更严格可改为仅 `self data: blob:`。
- `frame-ancestors 'none'` / `object-src 'none'`：防点击劫持和插件内容。
- `/healthz` 单独覆盖了 `add_header`，因此显式补了安全头。

`frontend/index.html` 同时加入 meta CSP，覆盖开发直连/index 被单独托管的情况；`frame-ancestors` 在 meta 中无效，仍由 Nginx 响应头保证。

## 2. 后端

`SecurityHeadersFilter` 对后端所有响应设置：

- `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`
- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `Referrer-Policy: no-referrer`
- `Permissions-Policy: camera=(self), microphone=(self), geolocation=()`

后端主要是 JSON/SSE/文件流，API CSP 采用最严格的 `default-src 'none'`，不影响文件内容本身。

## 3. 已验证

- 后端健康检查响应头实测包含上述 CSP/XCTO/XFO/Referrer/Permissions-Policy。
- 前端加入 meta CSP 后，本地 Vite + Playwright UI 冒烟 4/4 通过（登录、技能、Markdown、变量弹窗）。
- **未验证**：Nginx 容器未实际启动（本机无 Docker），`nginx -t` 未运行；Compose/Helm 实际响应头未验收。

## 4. 边界

- `style-src` 仍允许 `unsafe-inline`，XSS 防护不是最严格形态；后续可评估 Element Plus 的 nonce/hash 方案。
- 外部图片允许 `https:`，可能带来隐私/内容风险。
- CSP 不能替代输出编码、HttpOnly Cookie、权限校验；它是第二道防线。
