# 统一 API 网关

## MVP（2026-10-24）

- V61 `gateway_route` / `gateway_request_log`；
- 租户维度路由：名称、上游地址、路由 Key、API Key（SHA-256 存储，仅创建/轮换返回一次）；
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
- 请求体上限 5MB，上游响应上限 10MB；
- 超时 1–120 秒；GET/HEAD/OPTIONS 可重试 0–3 次（5xx/IO）；
- 路由级 Redis 固定窗口限流（0=不限）；
- 请求日志：方法、路径、状态、上游状态、耗时、错误；
- 管理页：路由 CRUD、启停、Key 轮换、调用地址/Key 一次性展示、请求日志；
- 权限：`tenant:manage`；
- 上游地址通过 `NetworkPolicyService` 校验；生产应设 `SECURITY_NETWORK_ALLOW_PRIVATE=false` 阻断内网 SSRF。

## 已验证

- E2E：创建路由 → 上游 `/actuator/health` 200 → 错误 Key 401 → 日志 → 轮换 Key（旧 401/新 200）→ 停用 503 → 限流 429；
- 完整 E2E 43/43。

## 未完成

- 多上游负载均衡/权重/主动健康检查/熔断；
- Header 改写、路径重写、请求/响应体转换；
- OAuth2/JWT 上游鉴权、IP 白名单；
- WebSocket/SSE 流式透传（当前缓冲响应体）；
- 管理端审计报表/用量计费；
- WAF、防重放、签名；
- 生产级 Key 生命周期（过期、scope、多 Key）。
