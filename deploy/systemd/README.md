# systemd 定时任务（无 Kubernetes 环境）

适用于单机 Linux VPS。假设代码在 `/opt/ai-agent-platform`，运行用户 `aiagent`。

## 安装

```bash
sudo useradd --system --home /opt/ai-agent-platform --shell /usr/sbin/nologin aiagent || true
sudo chown -R aiagent:aiagent /opt/ai-agent-platform/backups
sudo cp deploy/systemd/*.service deploy/systemd/*.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now ai-agent-backup.timer
sudo systemctl enable --now ai-agent-binlog-ship.timer
sudo systemctl enable --now ai-agent-restore-drill.timer
sudo systemctl enable --now ai-agent-es-snapshot.timer
```

## 任务

| Timer | 频率 | 内容 |
|---|---|---|
| `ai-agent-backup.timer` | 每天 02:30 + 随机 15m | `backup.sh --prune --retention-days 14` |
| `ai-agent-binlog-ship.timer` | 每分钟 | `binlog-ship.sh`，实现低 RPO |
| `ai-agent-restore-drill.timer` | 每周日 04:00 + 随机 30m | `restore-drill.sh` |
| `ai-agent-es-snapshot.timer` | 每天 03:10 + 随机 10m | ES 快照 `create`，并保留最近 14 个 |

## 检查

```bash
systemctl list-timers 'ai-agent-*'
journalctl -u ai-agent-backup.service -n 100 --no-pager
journalctl -u ai-agent-binlog-ship.service -n 100 --no-pager
journalctl -u ai-agent-es-snapshot.service -n 100 --no-pager
```

ES 快照要求本地 Elasticsearch 已配置 `path.repo` 并挂载快照卷，详见
`docs/ES快照恢复.md`。服务内默认 `ELASTICSEARCH_URL=http://127.0.0.1:9200`，
可用 `.env` 覆盖。

## 注意

- `.env` 必须由 `aiagent` 可读；备份账号建议只读/最小权限。
- 异地备份密钥文件不要放在被备份的目录；`BACKUP_ENCRYPT_KEY_FILE` 指向独立安全路径。
- 首次启用前手动执行一次三个脚本，确认权限和路径。
- 若使用 Compose/Helm，优先用其 CronJob；本目录是单机 systemd 方案。
