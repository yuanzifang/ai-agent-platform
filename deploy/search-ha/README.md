# 检索 HA 实跑记录

更新时间：2026-10-29  
环境：macOS + Docker（colima），3 节点 Elasticsearch 8.15 + 3 节点 Qdrant。

## 启动

本机 Docker 约 4GB 内存，演练前建议停掉单机 ES/观测容器：

```bash
docker stop aap-es-local aap-prometheus aap-grafana aap-loki aap-promtail || true
docker compose -f deploy/search-ha/docker-compose.yml up -d
```

等待：

```bash
curl -s 'http://127.0.0.1:9201/_cluster/health?pretty'
curl -s http://127.0.0.1:6333/cluster
```

## 应用配置

```text
ELASTICSEARCH_URL=http://es-1:9200,http://es-2:9200,http://es-3:9200
ELASTICSEARCH_REPLICAS=1

QDRANT_URL=http://qdrant-1:6333,http://qdrant-2:6333,http://qdrant-3:6333
QDRANT_REPLICATION_FACTOR=2
QDRANT_SHARD_NUMBER=3
```

单机默认值保持 `ELASTICSEARCH_REPLICAS=0`、Qdrant 1 分片 1 副本，避免本地单节点变黄。

## 本次实测结果

### ES

```text
启动：status=green, nodes=3
写入 aap-ha-check（1 shard / 1 replica）
停止 es-2：status=yellow, nodes=2
从 es-1 读取 doc：ha-check
恢复 es-2：status=green, nodes=3
```

### Qdrant

```text
启动：3 peers，1 Leader + 2 Follower
停止 qdrant-1（Leader）
从 qdrant-2 查询：3 peers，Leader 已重新选出
恢复 qdrant-1：集群恢复 3 peers
```

## 清理

```bash
docker compose -f deploy/search-ha/docker-compose.yml stop
docker stop aap-qdrant-ha-1 aap-qdrant-ha-2 aap-qdrant-ha-3 || true
docker start aap-es-local aap-prometheus aap-grafana aap-loki aap-promtail || true
```

数据卷保留用于重复演练；需要彻底清理时使用 `down -v`。

## 边界

- 这是单机 Docker 内多节点，不是跨可用区/跨物理机；
- 未做生产规模容量、网络分区、脑裂和真实 CA/TLS；
- ES 索引副本、Qdrant replication/shard 配置已补入应用配置，但生产需结合节点数规划；
- 快照仓库在 HA 集群中仍需共享存储或对象存储。
