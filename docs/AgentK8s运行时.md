# Agent K8s 运行时

日期：2026-11-02

## 目标

后端不再执行 `docker run`。在 K8s 集群内，后端通过 Kubernetes API 创建每个 Agent 的运行时资源，由 K8s 负责调度和拉起 nginx。

```text
client
  → Service/<agent>
  → Deployment/<agent> (nginx)
  → Spring 平台网关 /api/v1/gateway/agent-runtime/{agentId}/v1/*
  → /api/v1/open 对话链路
```

## 运行时选择

```text
AGENT_DEPLOY_RUNTIME=auto        # 默认：集群内用 K8s，否则 Docker
AGENT_DEPLOY_RUNTIME=kubernetes  # 强制 K8s
AGENT_DEPLOY_RUNTIME=docker      # 本机 Docker 开发
```

实现：

```text
KubernetesRuntime      -> Fabric8 Kubernetes Client
DockerCliRuntime       -> 本机 docker CLI（开发回退）
AgentRuntimeConfig     -> 选择主 ContainerRuntime
```

## 每个 Agent 创建的资源

| 资源 | 名称 | 说明 |
| --- | --- | --- |
| Deployment | `aap-agent-<id>` | nginx 入口，副本数由部署参数控制 |
| Service | `aap-agent-<id>` | ClusterIP:80，内部地址 `http://aap-agent-<id>.<ns>.svc.cluster.local/v1` |
| ConfigMap | `aap-agent-<id>-nginx` | `default.conf.template`，由 nginx 官方 entrypoint envsubst |

Agent Deployment：

- 镜像：`AGENT_DEPLOY_IMAGE`，默认 `nginx:1.27-alpine`
- 不挂载 ServiceAccount Token（`automountServiceAccountToken=false`）
- 只挂载 nginx 模板 ConfigMap
- CPU/内存从部署资源 Profile 转换：Docker 的 `512m` → K8s 的 `512Mi`
- 支持 `AGENT_DEPLOY_K8S_NODE_SELECTOR`，格式 `key=value,key2=value2`

## 生命周期

| 操作 | K8s 行为 |
| --- | --- |
| 部署/重新部署 | createOrReplace Deployment + Service + ConfigMap |
| 停用 | `spec.replicas=0` |
| 启动 | 从 annotation `aap.agent.replicas` 恢复副本数 |
| 删除 | 删除 Deployment/Service/ConfigMap |
| 状态 | 读取 Deployment conditions/readyReplicas |
| 日志 | 按 `aap.agent.resource=<name>` 找 Pod 并读日志 |

## Helm RBAC

`deploy/helm/ai-agent-platform/templates/agent-runtime-rbac.yaml` 创建：

```text
Role/RoleBinding: <release>-agent-runtime
```

权限：

- `apps/deployments`: get/list/watch/create/update/patch/delete
- `services/configmaps`: get/list/watch/create/update/patch/delete
- `pods`: get/list/watch
- `pods/log`: get

ServiceAccount 默认 `automountServiceAccountToken=false`；backend Pod 仅在 `agentDeploy.enabled=true` 时显式挂载 token 以调用 K8s API，frontend 始终不挂载。

## 自动化验证

- `KubernetesRuntimeTest`：Docker 内存单位转换 `512m -> 512Mi`
- `AgentRuntimeConfigTest`：auto/kubernetes/docker 选择
- 后端全量单测：570/570
- `ArchitectureTest`：3/3

## 真实 k3d 验证

真实 k3d 验证：

```text
Deployment/aap-agent-1   1/1 Ready
Service/aap-agent-1      ClusterIP
ConfigMap/aap-agent-1-nginx
```

接口：

```text
/healthz -> 200
/v1/models 无 token -> 401
/v1/models 有部署令牌 -> 200
/stats -> 200, store=redis
```

生命周期：

```text
stop -> Deployment replicas=0
start -> Deployment replicas=1, Ready
```

## 注意

- Service 默认 ClusterIP；外部入口采用可选 Ingress，设计见 `docs/AgentIngress设计.md`。当前集群没有 IngressClass/Controller，默认关闭。
- Agent nginx 不再需要 Docker socket。
- 本机开发仍可选择 Docker 模式，不影响 K8s 部署。
