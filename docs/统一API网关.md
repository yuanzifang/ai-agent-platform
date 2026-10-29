# 统一 API 网关

## MVP（2026-10-24）

- V61 `gateway_route` / `gateway_request_log`；
- 租户维度路由：名称、主上游、备用上游、路由 Key、API Key（SHA-256 存储，仅创建/轮换返回一次）；
- 公开入口：

```text
ANY /api/v1/gateway/call/{routeKey}/{path...}
X-Gateway-Key: gw_xxx
```

- 转发到：

```text
{targetUrl}/{path...}?{query}
```

- 支持方法：GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS；
- 请求体上限 5MB；缓冲响应上游上限 10MB；SSE 流式响应不占缓冲上限；
- 超时 1–120 秒；GET/HEAD/OPTIONS 可重试 0–3 次（5xx/IO）；SSE 流最长 300 秒；
- 路由级 Redis 固定窗口限流（0=不限）；
- 主上游 5xx/连接失败自动切备用上游；另支持最多 5 个额外上游，按顺序故障切换，日志记录 fallback；
- **上游权重**：`targetWeight` / `fallbackWeight` / `upstreamWeights`（URL->权重），默认主上游 100、备用/额外 0；
  权重 >0 的上游按权重随机决定首跳，失败后按剩余顺序切换；全部权重为 0 时保持原有顺序；
- 最小 WAF：限制代理方法、超长 URI/query、CRLF 和编码目录穿越，拒绝时记录请求日志；
- **上游轻量熔断**：同一路由+上游 2 分钟内连续 3 次 5xx/IO 失败后打开 60 秒，期间直接跳过主上游走备用；
  无备用时返回 503；Redis 故障 fail-open；路由删除时清理熔断键；
- **主动健康检查**：默认每 30 秒探测启用路由的每个上游；`healthPath` 默认 `/`，可用 `GET/POST /api/v1/gateway/routes/{id}/health[/check]` 查看/立即检查；
  2xx/3xx/4xx 视为可达，5xx/连接失败标记 DOWN；存在健康备用时跳过 DOWN 上游，全部 DOWN 时 fail-open 继续尝试；
  状态写入 Redis（约 90 秒 TTL），路由删除时清理；管理页支持查看和立即检查；
- **SSE 流式透传**：请求头 `Accept: text/event-stream` 时使用 `StreamingResponseBody`，上游响应逐块 flush，
  返回 `Transfer-Encoding: chunked`，并设置 `X-Accel-Buffering: no`；不做整包缓冲；
- 普通响应也通过 `StreamingResponseBody` 异步写出，缓冲响应保留 `Content-Length`；
- 请求日志：方法、路径、状态、上游状态、耗时、错误；
- 管理页：路由 CRUD、启停、Key 轮换、调用地址/Key 一次性展示、请求日志；
- 权限：`tenant:manage`；
- 上游地址通过 `NetworkPolicyService` 校验；生产应设 `SECURITY_NETWORK_ALLOW_PRIVATE=false` 阻断内网 SSRF。

## 已验证

- E2E：创建路由 → 上游 `/actuator/health` 200 → 错误 Key 401 → 日志 → 轮换 Key（旧 401/新 200）→ 停用 503 → 限流 429；
- E2E：`Accept: text/event-stream` → mock 上游 3 个 SSE chunk + `[DONE]`，网关返回 200、`text/event-stream`、`chunked`，内容完整；
- E2E：主上游连续 3 次 500 → 第 4 次不再调用主上游，直接走备用并返回 200；
- 2026-10-29 本机 WebSocket echo 手工验证：创建路由 → `ws://127.0.0.1:8080/api/v1/gateway/ws/{routeKey}?key=...` → 发送 `hello-websocket`，收到 `echo:hello-websocket`；错误 key 不返回业务消息；测试路由/echo 服务已清理；
- 2026-10-29 WebSocket 连接配额手工验证：`wsMaxConnections=1`，首个连接 echo 正常；第二个连接握手后收到 close(1008, connection limit)；首个连接关闭后第三个连接恢复 echo；测试路由/echo 服务已清理；
- 2026-10-29 WebSocket 上游掉线切换手工验证：主 A `18101`、备 B `18102`；连接后发送 `one` 收到 `A:one`，杀掉 A 后再发送 `two` 收到 `B:two`；测试路由/echo 服务已清理；
- 2026-10-29 主动健康检查手工验证：主上游 `127.0.0.1:19999` DOWN、备用 `127.0.0.1:18080` UP；代理请求自动跳过主上游，返回 200 且日志记录 `fallback upstream`；测试路由已清理；
- 2026-10-29 权重手工验证：主/备 A/B，`0/100` 三次均 B，`100/0` 三次均 A，`50/50` 20 次采样 A 7/B 13；测试路由和本地服务已清理；
- 完整 E2E 46/46。

## 未完成

- 有序故障切换已支持 target + fallback + 最多 5 个额外 upstream；主动健康检查已落地；仍缺完整 WAF 规则；
- Header 改写、路径重写、请求/响应体转换；
- OAuth2/JWT 上游鉴权、IP 白名单；
- WebSocket 透传已支持最小双向代理：`/api/v1/gateway/ws/{routeKey}?key=<apiKey>`，targetUrl 使用 http(s)，运行时转换为 ws(s)，文本/二进制帧双向转发；服务端定时 Ping 客户端/上游并按 `gateway.ws.idle-timeout-ms` 回收半开连接；支持 fallback/额外 upstream 建连及已建立连接掉线切换；路由级 `wsMaxConnections`（0=不限，Redis 全局统计，Redis 故障回退实例统计）；
- 管理端审计报表/用量计费；
- WAF、防重放、签名；
- 生产级 Key 生命周期（过期、scope、多 Key）。
