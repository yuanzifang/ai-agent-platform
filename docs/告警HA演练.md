# 告警多副本与高可用演练

日期：2026-10-28  
范围：本地 Docker/Node/Redis，多副本 relay + 3 节点 Alertmanager；不使用外部账号。

## 1. relay 双副本 + Redis

两个 relay 进程：

```text
relay-a :18101
relay-b :18102
  └─ Redis alert-ha:pending / alert-ha:acked / lock
```

实测：

- A 收到 `ha-1` 后，B 的 `/state` 能看到同一 pending
- 1 秒后升级只投递一次（A/B 都调度，但 Redis `SET NX` 锁只允许一个发送）
- 通过 B ACK `ha-2` 后，A/B 都不再升级
- 杀掉 A 后，B 继续接收 `ha-3` 并维护 pending
- 接收器统计与预期一致：`ha-1` 初始+升级 2 条；`ha-2` ACK 后仅初始 1 条；A 宕机不影响 B

这验证了 relay 多副本共享状态和 ACK 跨副本。

## 2. Alertmanager 3 节点

Docker 三节点：

```text
am1 172.31.50.11:9093  cluster 172.31.50.11:9094
am2 172.31.50.12:9093  cluster 172.31.50.12:9094
am3 172.31.50.13:9093  cluster 172.31.50.13:9094
```

- 三节点 `/api/v2/status` 均显示 peers 3
- 从 am1 投递 `AMHA-1`，relay 收到 1 次初始 + 1 次 1 秒升级
- 停止 am1 后从 am2 投递 `AMHA-2`，am2 状态为 ready、peers 2，relay 仍收到初始+升级
- 未观察到集群重复通知；接收器每轮只有一份初始消息，第二份来自 relay 超时升级（relay 日志 `alert escalated` 对应指纹）

## 3. 结论与边界

本地应用层 HA 演练通过：

- relay 双副本共享状态/ACK/单次升级
- Alertmanager 单节点故障后继续路由
- 后端/前端多副本、HPA、PDB/滚动更新此前已通过

仍不能替代生产 HA：

- Redis Sentinel/Cluster 或云 Redis 故障切换
- MySQL 主从/MGR/云 RDS
- 多节点 K8s、云 LB/WAF、真实 CA/域名
- Prometheus/Alertmanager 持久化存储与跨机房容灾
- 真实值班表、PagerDuty、告警 ACK 权限/审计

## 4. 复现要点

- relay 设置 `REDIS_HOST`、`ALERT_RELAY_REDIS_PREFIX`、`ALERT_RELAY_ACK_TOKEN`
- 两个 relay 使用同一 prefix，不同 HTTP 端口
- Alertmanager `--cluster.advertise-address` 必须是可解析 IP，不能直接写容器主机名；本次用固定 Docker IP
- Alertmanager receiver 指向 relay；relay 警报升级时间设为 1s 仅用于演练
- 演练容器、网络、临时配置、Redis key 均已清理
