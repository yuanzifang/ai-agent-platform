# Agent 独立部署资源 Profile

Agent 的检索资源和部署资源现在是两套独立 Profile：

- 检索资源 Profile：关键字/向量后端组合，约束知识库；
- 部署资源 Profile：CPU、内存、PID 上限和副本范围，约束 Agent 独立容器。

Agent 部署时只选择部署 Profile，不接触宿主机资源参数或连接地址。

## 数据模型

表 `agent_deploy_profile`：

| 字段 | 说明 |
|---|---|
| `name` | Profile 名称 |
| `cpu_limit` | Docker `--cpus`，如 `0.5`、`1` |
| `cpu_request` | K8s requests.cpu，如 `250m` |
| `memory_limit` | Docker `--memory`，如 `384m`、`1g` |
| `memory_request` | K8s requests.memory，如 `256Mi` |
| `pids_limit` | Docker `--pids-limit`，16-4096 |
| `default_replicas` | 首次部署默认副本数 |
| `max_replicas` | 最大副本数，1-5 |
| `status` | `ENABLED` / `DISABLED` |

迁移同时给 `agent_deployment` 增加 `deploy_profile_id`，记录实际使用的 Profile。

内置默认 Profile：

```text
标准部署：1 CPU / 512m / 256 PID / 1-5 副本
```

## 平台管理

平台管理员页面：

```text
系统设置 → 基础设施 → 部署资源 Profile
```

支持新增、编辑、停用和删除。普通用户只读已启用项：

```http
GET /api/v1/deploy-resource-profiles
```

平台管理员接口：

```http
GET    /api/v1/platform/deploy-resource-profiles
POST   /api/v1/platform/deploy-resource-profiles
PUT    /api/v1/platform/deploy-resource-profiles/{id}
DELETE /api/v1/platform/deploy-resource-profiles/{id}
```

创建示例：

```json
{
  "name": "轻量部署",
  "description": "FAQ Agent",
  "cpuLimit": "0.5",
  "memoryLimit": "384m",
  "pidsLimit": 128,
  "defaultReplicas": 1,
  "maxReplicas": 2,
  "status": "ENABLED"
}
```

## 部署行为

Agent 编辑页 → 部署到容器：

1. 选择部署资源 Profile；
2. 首次部署使用 Profile 的默认副本数；
3. 已有部署沿用上次副本数，但受当前 Profile 最大副本数约束；
4. 多副本仍要求 gateway 运行时和共享 Redis；
5. 容器创建参数使用 Profile 的 `--cpus`、`--memory`、`--pids-limit`；
6. 未选择时使用第一个启用 Profile；没有 Profile 时回退 `agent.deploy.*` 全局默认；
7. 请求副本数超过 Profile 上限直接返回 400。

部署请求：

```http
POST /api/v1/agent/{agentId}/deployment
{
  "deployProfileId": 2,
  "replicas": 2
}
```

## K8s 资源映射

当前容器运行时是 Docker，Docker 使用 Profile 的 CPU/内存 limits；K8s 映射接口：

```http
GET /api/v1/platform/deploy-resource-profiles/{id}/k8s-resources
```

返回示例：

```json
{
  "requests": { "cpu": "250m", "memory": "256Mi" },
  "limits": { "cpu": "500m", "memory": "384Mi" }
}
```

映射规则：

- CPU `0.5` → `500m`，`1` → `1`，`1.5` → `1500m`；
- 内存 `384m` → `384Mi`，`1g` → `1Gi`；
- 已经是 `250m`、`256Mi`、`1Gi` 的数量保持 K8s 语义。

未来接入 K8s ContainerRuntime 时，直接将返回的 `requests/limits` 写入 Pod 的
`resources`；当前 Docker 运行时不会使用 requests。

## 边界

- Profile 只控制容器资源，不包含数据库/Redis/MinIO 地址或密钥；
- 删除或停用已被引用的 Profile 不会自动删除已有容器，但重新部署会要求选择可用 Profile；
- Docker 仍是当前 MVP 运行时，K8s 资源 requests/limits 可在后续 ContainerRuntime 实现中映射。
