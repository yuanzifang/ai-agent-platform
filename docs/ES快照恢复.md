# Elasticsearch 快照与恢复

本文记录本地 ES 8.15 的快照仓库、创建/恢复演练，以及生产恢复建议。

## 1. 本地快照仓库

本地容器 `aap-es-local` 使用：

- 数据卷：`aap-es-data` → `/usr/share/elasticsearch/data`
- 快照卷：`aap-es-snapshots` → `/usr/share/elasticsearch/snapshots`
- 启动参数：`-Epath.repo=/usr/share/elasticsearch/snapshots`
- `restart=always`

重建容器时保留两个卷：

```bash
docker volume create aap-es-snapshots
docker run -d --name aap-es-local --restart=always --memory=1g \
  -p 9200:9200 \
  -e discovery.type=single-node \
  -e xpack.security.enabled=false \
  -e 'ES_JAVA_OPTS=-Xms512m -Xmx512m' \
  -v aap-es-data:/usr/share/elasticsearch/data \
  -v aap-es-snapshots:/usr/share/elasticsearch/snapshots \
  docker.elastic.co/elasticsearch/elasticsearch:8.15.0 \
  eswrapper -Epath.repo=/usr/share/elasticsearch/snapshots
```

首次挂载后确保容器内快照目录可写：

```bash
docker exec -u 0 aap-es-local \
  chown -R elasticsearch:elasticsearch /usr/share/elasticsearch/snapshots
```

> 注意：命令必须带 `eswrapper`。官方 entrypoint 只有在第一个参数是 `eswrapper` 或 `elasticsearch` 时才把后面的 `-E...` 传给 ES。

## 2. 使用脚本

脚本：`scripts/es-snapshot.sh`

```bash
# 注册/检查文件系统仓库 aap_local
scripts/es-snapshot.sh setup

# 创建快照（默认 aap-knowledge-YYYYmmdd-HHMMSS）
scripts/es-snapshot.sh create

# 指定名称
scripts/es-snapshot.sh create aap-knowledge-before-upgrade

# 列出快照
scripts/es-snapshot.sh list

# 恢复演练：把源索引恢复到临时索引，校验后自动删除，不影响线上
scripts/es-snapshot.sh restore-drill aap-knowledge-before-upgrade
```

默认源索引 `aap_knowledge_chunks_v1`，可用环境变量覆盖：

```bash
ES_SNAPSHOT_SOURCE=aap_knowledge_chunks_v2 \
  scripts/es-snapshot.sh restore-drill aap-knowledge-before-upgrade
```

脚本恢复到的临时索引默认是 `aap_knowledge_chunks_restore_test`，也可用
`ES_SNAPSHOT_RESTORE_INDEX` 覆盖。

## 3. 定时任务

单机 systemd 方案已提供：

```text
deploy/systemd/ai-agent-es-snapshot.service
deploy/systemd/ai-agent-es-snapshot.timer
```

每天 03:10（随机延迟 10 分钟）创建快照，并调用 `prune 14` 只保留最近 14 个
`aap-knowledge-*` 成功快照。安装方式见 `deploy/systemd/README.md`。

```bash
sudo systemctl enable --now ai-agent-es-snapshot.timer
journalctl -u ai-agent-es-snapshot.service -n 100 --no-pager
```

Kubernetes 环境建议改用 CronJob 调用同一个脚本，并把 `aap-es-snapshots` 换成共享存储。

## 4. 生产恢复建议

MySQL 仍是知识库分段的权威数据源；ES 索引可以从 MySQL 重新回填。快照用于缩短恢复时间。

推荐恢复到新索引，而不是覆盖线上索引：

1. 暂停知识库写入/解析和 ES outbox 消费；
2. 创建或选择一个快照；
3. 把快照中的索引恢复为新索引，例如：

```http
POST /_snapshot/aap_local/<snapshot>/_restore?wait_for_completion=true
{
  "indices": "aap_knowledge_chunks_v1",
  "include_global_state": false,
  "rename_pattern": "aap_knowledge_chunks_v1",
  "rename_replacement": "aap_knowledge_chunks_restore_20261028"
}
```

4. 用 `_count`、抽样查询和 alias 检查校验新索引；
5. 更新平台基础设施页的 `ELASTICSEARCH_INDEX` 为新索引，重启后端；
6. 后端启动时会把 alias `aap_knowledge_chunks` 原子切到新索引；
7. 恢复 outbox 消费，执行必要的 `search-index/reindex` 补齐快照之后产生的分段；
8. 保留旧索引一段时间，确认回滚窗口后再删除。

不要在线上索引仍处于 open 状态时直接覆盖同名索引；优先使用“新索引 + alias 切换”。

## 5. 验证记录

本地实际演练结果：

- 注册仓库 `aap_local` 成功；
- 创建快照 `aap-drill-100146`，包含 2 个索引，状态 `SUCCESS`；
- 从快照恢复临时索引，`_count=1`；
- 删除临时索引成功；
- 数据卷 `aap-es-snapshots` 中生成快照文件；
- 容器重建后 `aap-es-data` 和 `aap-es-snapshots` 均保留。
