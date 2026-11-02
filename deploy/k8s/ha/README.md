# 数据库 HA

本目录提供 UTM 单机 K3s 上的数据库高可用组件：

```text
MySQL MGR         3 个 StatefulSet Pod，分布在 3 个节点
ProxySQL          2 个读写入口 Pod
Redis Sentinel    3 个 Redis Pod + 每个 Pod 内的 Sentinel sidecar
```

PostgreSQL/pgvector 目前仍是单实例，不在本目录范围内。
Ollama 仍是外部原生服务。

## 1. Secret

```bash
kubectl create namespace aap-ha

kubectl -n aap-ha create secret generic aap-ha-secrets \
  --from-literal=MYSQL_ROOT_PASSWORD='***' \
  --from-literal=MYSQL_REPL_PASSWORD='***' \
  --from-literal=REDIS_PASSWORD='***'
```

`MYSQL_REPL_PASSWORD` 建议不超过 32 字符，这是 MySQL replication
recovery password 的限制。

## 2. MySQL MGR

```bash
kubectl apply -f deploy/k8s/ha/mysql-mgr.yaml
kubectl apply -f deploy/k8s/ha/mysql-member-aliases.yaml
kubectl -n aap-ha get pods,pvc -o wide
```

首次建立复制组需要执行一次初始化（每个 Pod 都要创建 `repl` 用户并配置
group replication 参数）。在 `mysql-ha-0` 上 bootstrap：

```sql
SET GLOBAL group_replication_bootstrap_group=ON;
START GROUP_REPLICATION;
SET GLOBAL group_replication_bootstrap_group=OFF;
```

然后在 `mysql-ha-1`、`mysql-ha-2` 上执行：

```sql
START GROUP_REPLICATION;
```

验证：

```sql
SELECT MEMBER_HOST, MEMBER_STATE, MEMBER_ROLE
FROM performance_schema.replication_group_members;
```

Pod 重建后需要在对应实例执行 `START GROUP_REPLICATION`。当前版本不内置 rejoin sidecar，避免它和 Group Replication 的启动状态竞争；生产可另做独立 rejoin 控制器。

## 3. ProxySQL

ProxySQL 负责把应用写流量固定到 MGR primary，并自动跟随 failover。

```bash
kubectl apply -f deploy/k8s/ha/proxysql.yaml
kubectl -n aap-ha get pods,svc
```

应用连接：

```text
mysql-proxy.aap-ha.svc.cluster.local:6033
```

ProxySQL 监控 MGR，并将 primary 放入 writer hostgroup `10`，replica 放入
reader hostgroup `30`。

## 4. Redis Sentinel

```bash
kubectl apply -f deploy/k8s/ha/redis-sentinel.yaml
kubectl -n aap-ha get pods,pvc -o wide
```

每个 Redis Pod 包含：

- Redis 6379
- Sentinel 26379 sidecar

应用使用 Sentinel 配置：

```text
SPRING_DATA_REDIS_SENTINEL_MASTER=mymaster
SPRING_DATA_REDIS_SENTINEL_NODES=redis-ha-0.redis-headless.aap-ha.svc.cluster.local:26379,redis-ha-1.redis-headless.aap-ha.svc.cluster.local:26379,redis-ha-2.redis-headless.aap-ha.svc.cluster.local:26379
```

Sentinel 主动 failover：

```bash
redis-cli -p 26379 SENTINEL failover mymaster
redis-cli -p 26379 SENTINEL get-master-addr-by-name mymaster
```

## 5. 应用切换

使用：

```text
deploy/k8s/values-utm-ha.example.yaml
```

它会同时指向：

- MySQL：`mysql-proxy.aap-ha.svc.cluster.local:6033`
- Redis：3 个 Sentinel 节点

## 注意

- MySQL MGR 的首次 bootstrap 仍需一次性初始化；Pod 重建后需执行 `START GROUP_REPLICATION`。
- 所有数据仍使用 local-path PVC；这是单节点存储，不是跨节点共享存储。
- ProxySQL 和 Sentinel 解决的是数据库角色切换，不解决底层存储节点故障后的数据可用性。
- 生产环境建议使用托管数据库，或使用对应 Operator + 分布式存储。

## 当前 UTM 状态

HA 验证完成后已恢复为 MySQL/Redis 单实例运行：

- `aap-data/mysql`、`aap-data/redis` 为应用当前依赖
- `aap-ha/mysql-ha`、`aap-ha/proxysql`、`aap-ha/redis-ha` 已缩容到 0
- HA PVC、Secret、清单和迁移备份保留，可重新扩容做 HA 演练

