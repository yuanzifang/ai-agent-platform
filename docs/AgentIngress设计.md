# Agent Ingress 外部访问设计

日期：2026-11-02

## 结论

Agent 外部入口暂采用 **Ingress 按 Agent 独立域名**：

```text
https://agent-<id>.agents.example.com/v1/*
  → Ingress
  → Service/aap-agent-<id>:80
  → Deployment/aap-agent-<id> (nginx)
  → 平台网关/对话链路
```

- Service 保持 `ClusterIP`，不直接暴露 NodePort。
- 每个 Agent 一个 Ingress，域名由模板生成。
- TLS 使用通配符证书，例如 `*.agents.example.com`。
- 默认关闭，等目标环境有 Ingress Controller + DNS + 证书后再启用。

## 为什么不走路径前缀

路径方案例如：

```text
https://agents.example.com/agent-1/v1/*
```

需要额外 rewrite 去掉 `/agent-1`，且容易影响 SSE、重定向和客户端 base URL。按 Agent 独立域名不需要 rewrite，Agent 的 `/v1/models`、`/v1/chat/completions`、`/stats` 路径保持不变。

## 配置

```yaml
agentDeploy:
  ingress:
    enabled: true
    className: nginx          # 或 traefik
    hostTemplate: "agent-{agentId}.agents.example.com"
    tlsSecret: "agents-wildcard-tls"
    externalPort: ""          # 非 443 测试端口时填写，如 19004
```

对应后端环境变量：

```text
AGENT_DEPLOY_K8S_INGRESS_ENABLED=true
AGENT_DEPLOY_K8S_INGRESS_CLASS=nginx
AGENT_DEPLOY_K8S_INGRESS_HOST_TEMPLATE=agent-{agentId}.agents.example.com
AGENT_DEPLOY_K8S_INGRESS_TLS_SECRET=agents-wildcard-tls
AGENT_DEPLOY_K8S_INGRESS_EXTERNAL_PORT=   # 生产 443 留空
```

`{agentId}` 会被替换为数字 ID，`{name}` 会被替换为 `aap-agent-<id>`。

## 创建的资源

启用后，`KubernetesRuntime` 在创建 Deployment/Service/ConfigMap 的同时创建：

```text
Ingress/aap-agent-<id>
  ingressClassName: <configured>
  host: agent-<id>.agents.example.com
  path: /
  pathType: Prefix
  backend: Service/aap-agent-<id>:80
  tls: <tlsSecret>（配置时）
```

注解：

```text
nginx.ingress.kubernetes.io/proxy-body-size: 2m
nginx.ingress.kubernetes.io/proxy-buffering: off
nginx.ingress.kubernetes.io/proxy-read-timeout: 300
nginx.ingress.kubernetes.io/proxy-send-timeout: 300
```

这些注解兼容 nginx ingress；Traefik 会忽略不支持的注解，但 SSE 仍由 Agent nginx 的 `proxy_buffering off` 保证。

## DNS / TLS

目标环境需要：

1. Ingress Controller（nginx 或 Traefik），并存在对应 IngressClass。
2. 泛域名 DNS：

```text
*.agents.example.com → Ingress Controller 外部地址
```

3. 泛域名证书 Secret：

```text
kubectl -n aap create secret tls agents-wildcard-tls \
  --cert=wildcard.crt --key=wildcard.key
```

4. 防火墙只开放 Ingress Controller 的 80/443。

## RBAC

`agent-runtime-rbac.yaml` 已增加：

```text
apiGroups: ["networking.k8s.io"]
resources: ["ingresses"]
verbs: get/list/watch/create/update/patch/delete
```

ServiceAccount 默认不自动挂载 token；backend Pod 仅在 `agentDeploy.enabled=true` 时显式挂载，frontend 始终不挂载。

## 安全边界

- 只有 Agent nginx 的 80 端口暴露给 Ingress。
- Agent nginx 只反代平台运行时 `/v1` 和 `/stats`；`/healthz` 本地返回。
- `/actuator/**`、`/internal/**` 不在 Agent nginx 路由内。
- 调用方仍需 `Authorization: Bearer <部署令牌/调用方 Key>`。
- 平台网关继续执行限流、日配额、用量统计；Ingress 只做 L7 入口。
- 建议在 Ingress 层额外配置请求体大小、超时、WAF/限流（如控制器支持）。

## 当前状态

- 代码已支持按需创建 Ingress，默认 `enabled=false`。
- 当前测试集群已安装 Traefik，IngressClass `traefik` 已就绪。
- 已用临时 `sslip.io` 域名和自签通配符证书完成真实外部 HTTPS 验证：

```text
https://agent-1.192-168-64-2.sslip.io:19004/v1
```

- 验证通过：

```text
/healthz 200
/v1/models 无 token 401
/v1/models 有部署令牌 200
/stats 200
```

- 当前入口端口：

```text
http:  19003 -> Traefik 30090
https: 19004 -> Traefik 30443
```

- 生产环境仍需替换为真实泛域名 DNS、CA 证书和 443 入口。

## 管理接口

部署状态里的 `endpoint` 会自动切换：

```text
未启用 Ingress：http://aap-agent-<id>.<ns>.svc.cluster.local/v1
启用 Ingress：  https://agent-<id>.agents.example.com/v1
```

`/stats`、`/healthz` 使用同一 host；客户端 base URL 保持 `/v1` 不变。
