# 多实例 SSE 验收

目标：验证平台后端多副本下，SSE 流式生成与停止/重新生成/断线续传不依赖 sticky session，并验证生成实例宕机后的清理。

脚本：`scripts/sse-multi-instance-check.py`

## 1. 前置

- 已安装 Java 17+、Python 3.10+、MySQL/Redis（项目 `.env` 可用）。
- 已构建后端 jar：

```bash
cd backend && mvn -q -DskipTests package
```

- 本地 mock 在线（脚本只允许 mock，不调用真实模型）：

```bash
python3 frontend/e2e/mock_servers.py
```

mock 的 OpenAI 兼容端点为 `127.0.0.1:18080`，模型名为 `mock-model`。

## 2. 执行

```bash
python3 scripts/sse-multi-instance-check.py
```

脚本默认：

1. 从 jar 启动 A=`18082`、B=`18083` 两个隔离实例；
2. 创建临时 Provider/Model/Agent/Conversation；
3. 验证：
   - A 生成中，B 调用 stop，A 收到 `done=STOPPED` 且 DB 消息为 STOPPED；
   - A 旧流生成中，B 调用 regenerate，旧流 STOPPED，新流 SUCCESS；
   - A 生成中断开客户端，B 用 offset 续传并收到 SUCCESS；
   - 杀掉生成实例 B，A 上的 resume 在心跳过期后收到 `done=UNKNOWN`，并清理 stale active key；
4. 清理临时会话/Agent/Provider，并停止 A/B。

测试实例使用 `CHAT_STREAM_STALE_MS=8000`（生产默认 30000），因此宕机检测约 10 秒内完成。两个实例必须共享同一 Redis；脚本通过命令行走不同 `--server.port`，不会覆盖 `.env` 中的 Provider 配置。

复用已有实例：

```bash
python3 scripts/sse-multi-instance-check.py --external \
  --a-url http://127.0.0.1:8080 --b-url http://127.0.0.1:18081
```

外部模式不杀进程，因此不执行宕机用例。

## 3. 2026-10-27 实测

```text
PASS  本地 OpenAI Mock 可用（18080，mock-model）
PASS  后端 18082 health=UP
PASS  后端 18083 health=UP
PASS  跨实例停止：A done=STOPPED
PASS  跨实例停止：数据库最新 assistant=STOPPED
PASS  跨实例重生成：旧流=STOPPED
PASS  跨实例重生成：新流=SUCCESS
PASS  跨实例续传：meta.resumed=true
PASS  跨实例续传：done=SUCCESS
PASS  实例 B 已终止（模拟宕机）
PASS  实例宕机清理：resume done=UNKNOWN
结果：PASS=15 FAIL=0
```

耗时约 37 秒。测试使用 mock 模型和临时数据，不调用真实 DeepSeek。

## 4. 实现要点

- `StreamRegistryService` 的 Redis key `chat:active:{conversationId}` 记录流归属实例；停止通过 `chat:stop` Pub/Sub 广播。
- 每个流有 5 秒心跳，刷新 `chat:live:{conversationId}` 的 `updatedAt` 和 active key TTL。
- resume 读取 live 快照；若状态仍为 RUNNING 但 `updatedAt` 超过 `CHAT_STREAM_STALE_MS`，判定生产实例已宕机，返回 `done=UNKNOWN` 并清理 active key。
- 旧实例清理时只删除自己持有的 active key，避免旧流停止时误删新实例刚注册的流。

## 5. 仍建议

- 生产保留 Ingress sticky cookie，作为实例内其他状态（工具审批、临时上下文）的兼容措施；SSE 本身已不依赖 sticky。
- 真实模型下的 stop/regenerate/resume 仍需在获准的测试环境补一次；本轮按用户要求只使用本地 mock。
