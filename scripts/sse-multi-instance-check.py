#!/usr/bin/env python3
"""
多实例 SSE 自动化验收：跨实例停止、重新生成、断线续传、生产实例宕机检测。

默认从本地 jar 启动两个隔离后端（18082/18083），共享同一 MySQL/Redis，并使用
frontend/e2e/mock_servers.py 的 18080 OpenAI Mock。不会调用真实 DeepSeek。

用法：
  # 先构建：cd backend && mvn -q -DskipTests package
  python3 scripts/sse-multi-instance-check.py

  # 复用已有实例（必须已配置 CHAT_STREAM_STALE_MS=8000 或更短）
  python3 scripts/sse-multi-instance-check.py --external \
    --a-url http://127.0.0.1:8080 --b-url http://127.0.0.1:18081
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MOCK = "http://127.0.0.1:18080"
DEFAULT_JAR = ROOT / "backend/target/ai-agent-platform-backend-0.1.0-SNAPSHOT.jar"
PASS = 0
FAIL = 0


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def ok(msg: str) -> None:
    global PASS
    PASS += 1
    log(f"PASS  {msg}")


def bad(msg: str) -> None:
    global FAIL
    FAIL += 1
    log(f"FAIL  {msg}")


def check(cond: bool, msg: str) -> None:
    (ok if cond else bad)(msg)


def load_dotenv() -> None:
    path = ROOT / ".env"
    if not path.is_file():
        return
    for raw in path.read_text(errors="ignore").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        if line.startswith("export "):
            line = line[7:].strip()
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        os.environ.setdefault(key, value)


def http(method: str, url: str, token: str | None = None, body: dict | None = None,
         timeout: float = 30) -> tuple[int, bytes]:
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception as e:
        raise RuntimeError(f"{method} {url} 失败：{e}") from e


def api(base: str, method: str, path: str, token: str | None = None, body: dict | None = None,
        timeout: float = 30) -> dict:
    code, raw = http(method, base.rstrip("/") + path, token, body, timeout)
    try:
        data = json.loads(raw.decode() or "{}")
    except Exception as e:
        raise RuntimeError(f"{method} {path} 返回非 JSON（HTTP {code}）：{raw[:300]!r}") from e
    if code >= 400:
        raise RuntimeError(f"{method} {path} HTTP {code}：{data}")
    return data


def wait_health(base: str, timeout: float = 120) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            code, raw = http("GET", base.rstrip("/") + "/actuator/health", timeout=3)
            if code == 200 and b'"UP"' in raw:
                return True
        except Exception:
            pass
        time.sleep(1)
    return False


def port_free(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(("127.0.0.1", port))
            return True
        except OSError:
            return False


class SseStream:
    """读取一个 SSE 响应；累计 delta、记录 done/error。"""

    def __init__(self, url: str, token: str, body: dict | None = None, method: str = "GET",
                 timeout: float = 60):
        headers = {"Accept": "text/event-stream", "Authorization": "Bearer " + token}
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode()
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        self.resp = urllib.request.urlopen(req, timeout=timeout)
        self.events: list[tuple[str, dict]] = []
        self.content = ""
        self.done: dict | None = None
        self.error: dict | None = None
        self.exception: Exception | None = None
        self.meta: dict | None = None
        self.closed = threading.Event()
        self._lock = threading.Lock()
        self._thread = threading.Thread(target=self._read, daemon=True)
        self._thread.start()

    def _read(self) -> None:
        event = "message"
        try:
            while True:
                line = self.resp.readline()
                if not line:
                    break
                text = line.decode("utf-8", errors="replace").rstrip("\r\n")
                if text == "":
                    event = "message"
                    continue
                if text.startswith("event:"):
                    event = text[6:].strip()
                elif text.startswith("data:"):
                    raw = text[5:].strip()
                    try:
                        data = json.loads(raw) if raw else {}
                    except Exception:
                        data = {"raw": raw}
                    with self._lock:
                        self.events.append((event, data))
                        if event == "delta":
                            self.content += str(data.get("content") or "")
                        elif event == "revision":
                            # revision 是纠正后的全文；保持累计内容与最终落库一致
                            self.content = str(data.get("content") or self.content)
                        elif event == "meta":
                            self.meta = data
                        elif event == "done":
                            self.done = data
                        elif event == "error":
                            self.error = data
        except Exception as e:
            self.exception = e
        finally:
            self.closed.set()
            try:
                self.resp.close()
            except Exception:
                pass

    def wait_for(self, predicate, timeout: float, label: str = "") -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            with self._lock:
                if predicate(self):
                    return True
            if self.closed.is_set():
                return predicate(self)
            time.sleep(0.1)
        log(f"WARN  等待超时：{label}")
        return False

    def wait_done(self, timeout: float = 45) -> bool:
        return self.wait_for(lambda s: s.done is not None or s.error is not None or s.exception is not None,
                             timeout, "done")

    def close(self) -> None:
        try:
            self.resp.close()
        except Exception:
            pass


def cleanup_provider(provider_id: int | None, model_name: str | None) -> None:
    """测试 Provider 没有删除 API；用最小 SQL 清理本次创建的测试数据。"""
    if provider_id is None:
        return
    mysql = shutil.which("mysql")
    if not mysql:
        return
    db = os.environ.get("MYSQL_DATABASE", "ai_agent_platform")
    env = os.environ.copy()
    password = (os.environ.get("MYSQL_BACKUP_PASSWORD") or os.environ.get("MYSQL_ROOT_PASSWORD")
                or os.environ.get("MYSQL_PASSWORD") or "")
    if not password:
        return
    env["MYSQL_PWD"] = password
    host = os.environ.get("MYSQL_HOST", "127.0.0.1")
    port = os.environ.get("MYSQL_PORT", "3306")
    user = os.environ.get("MYSQL_BACKUP_USER") or os.environ.get("MYSQL_USERNAME", "root")
    sql = (
        f"DELETE FROM `{db}`.provider_credential WHERE provider_id={int(provider_id)};"
        f"DELETE FROM `{db}`.model WHERE provider_id={int(provider_id)};"
        f"DELETE FROM `{db}`.provider WHERE id={int(provider_id)};"
    )
    subprocess.run([mysql, "--protocol=TCP", "-h", host, "-P", port, "-u", user],
                   input=sql, text=True, env=env, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL, check=False)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--external", action="store_true", help="测试已有实例，不启动进程")
    parser.add_argument("--a-url", default="http://127.0.0.1:18082")
    parser.add_argument("--b-url", default="http://127.0.0.1:18083")
    parser.add_argument("--a-port", type=int, default=18082)
    parser.add_argument("--b-port", type=int, default=18083)
    parser.add_argument("--jar", default=os.environ.get("BACKEND_JAR", str(DEFAULT_JAR)))
    parser.add_argument("--stale-ms", type=int, default=8000)
    parser.add_argument("--keep-logs", action="store_true")
    args = parser.parse_args()

    load_dotenv()
    a_base = args.a_url.rstrip("/")
    b_base = args.b_url.rstrip("/")
    token = None
    agent_id = None
    conversation_id = None
    provider_id = None
    procs: list[subprocess.Popen] = []
    log_dir = Path(tempfile.mkdtemp(prefix="sse-multi-"))
    logs: list = []
    started = time.time()

    try:
        # Mock 必须在线；这里只允许本地 mock，禁止真实模型。
        try:
            code, raw = http("GET", MOCK + "/v1/models", timeout=5)
            if code != 200 or b"mock-model" not in raw:
                raise RuntimeError("mock 模型列表不匹配")
            ok("本地 OpenAI Mock 可用（18080，mock-model）")
        except Exception as e:
            raise RuntimeError(f"请先启动本地 mock：python3 frontend/e2e/mock_servers.py（{e}）") from e

        if not args.external:
            if not Path(args.jar).is_file():
                raise RuntimeError(f"后端 jar 不存在：{args.jar}；先执行 cd backend && mvn -q -DskipTests package")
            if not port_free(args.a_port) or not port_free(args.b_port):
                raise RuntimeError(f"测试端口被占用：{args.a_port}/{args.b_port}")

        def start_instance(port: int) -> subprocess.Popen:
            env = os.environ.copy()
            env["CHAT_STREAM_STALE_MS"] = str(args.stale_ms)
            env["OTEL_ENABLED"] = "false"
            env.setdefault("JAVA_TOOL_OPTIONS", "")
            out = open(log_dir / f"backend-{port}.log", "wb")
            logs.append(out)
            proc = subprocess.Popen(
                ["java", "-Xmx768m", "-jar", args.jar, f"--server.port={port}",
                 "--spring.main.banner-mode=off"],
                cwd=str(ROOT), env=env, stdout=out, stderr=subprocess.STDOUT,
                start_new_session=True,
            )
            procs.append(proc)
            base = f"http://127.0.0.1:{port}"
            if not wait_health(base, 150):
                raise RuntimeError(f"后端 {port} 启动失败，日志：{out.name}")
            ok(f"后端 {port} health=UP")
            return proc

        if args.external:
            if not wait_health(a_base, 20) or not wait_health(b_base, 20):
                raise RuntimeError("外部实例健康检查失败")
            ok(f"复用实例 A={a_base} B={b_base}（不执行宕机杀进程测试）")
            crash_available = False
        else:
            log(f"启动隔离实例 A={args.a_port}，B={args.b_port}；jar={args.jar}")
            start_instance(args.a_port)
            proc_b = start_instance(args.b_port)
            # 两实例使用同一 DB/Redis，端口不同；A 已先完成 Flyway。
            crash_available = True

        # 登录
        login = api(a_base, "POST", "/api/v1/auth/login", body={
            "username": os.environ.get("E2E_USER", "dev"),
            "password": os.environ.get("E2E_PASSWORD", "Dev@123456"),
            "tenantKey": os.environ.get("E2E_TENANT", "dev"),
        })
        token = (login.get("data") or {}).get("accessToken")
        if not token:
            raise RuntimeError(f"登录失败：{login}")
        ok("登录成功")

        suffix = str(int(time.time()))
        provider = api(a_base, "POST", "/api/v1/provider", token=token, body={
            "name": f"E2E SSE Mock {suffix}",
            "providerType": "OPENAI_COMPATIBLE",
            "baseUrl": MOCK,
            "timeoutMs": 15000,
        })
        provider_id = int(provider["data"]["id"])
        api(a_base, "POST", f"/api/v1/provider/{provider_id}/models/sync", token=token)
        models = api(a_base, "GET", "/api/v1/provider/models", token=token).get("data") or []
        model = next((m for m in models
                      if m.get("providerId") == provider_id and m.get("modelName") == "mock-model"), None)
        if model is None:
            api(a_base, "POST", f"/api/v1/provider/{provider_id}/models", token=token, body={
                "modelName": "mock-model", "displayName": "E2E SSE Mock",
                "purpose": "CHAT", "contextWindow": 8192,
            })
            models = api(a_base, "GET", "/api/v1/provider/models", token=token).get("data") or []
            model = next((m for m in models
                          if m.get("providerId") == provider_id and m.get("modelName") == "mock-model"), None)
        if model is None:
            raise RuntimeError("无法创建/同步 mock-model")
        ok(f"测试 Provider/Model：provider={provider_id} model=mock-model")

        agent = api(a_base, "POST", "/api/v1/agent", token=token, body={
            "name": f"E2E SSE 多实例 {suffix}",
            "description": "跨实例 SSE 自动验收",
            "systemPrompt": "你是测试助手。",
            "modelConfig": {"modelName": "mock-model", "temperature": 0.2},
            "visibility": "PRIVATE",
        })
        agent_id = int(agent["data"]["id"])
        published = api(a_base, "POST", f"/api/v1/agent/{agent_id}/publish", token=token,
                        body={"force": True})
        if published.get("code") != 0:
            raise RuntimeError(f"Agent 发布失败：{published}")
        conv = api(a_base, "POST", "/api/v1/conversations", token=token,
                   body={"agentId": agent_id, "title": "SSE 多实例"})
        conversation_id = int(conv["data"]["id"])
        ok("测试 Agent/Conversation 已创建并发布")

        def detail() -> dict:
            return api(a_base, "GET", f"/api/v1/conversations/{conversation_id}", token=token)

        def latest_assistant() -> dict | None:
            msgs = [m for m in (detail().get("data") or {}).get("messages", [])
                    if m.get("role") == "ASSISTANT"]
            return msgs[-1] if msgs else None

        def stream(base: str, path: str, body: dict | None = None, method: str = "GET") -> SseStream:
            return SseStream(base + path, token, body=body, method=method, timeout=90)

        def send_slow(base: str, text: str = "长回答") -> SseStream:
            return stream(base, f"/api/v1/conversations/{conversation_id}/messages",
                          body={"message": text, "stream": True}, method="POST")

        # 1) 跨实例停止：A 生成，B 发送停止
        first = send_slow(a_base)
        if not first.wait_for(lambda s: s.meta is not None and len(s.content) >= 20, 20, "首段内容"):
            raise RuntimeError("首段流没有收到 meta/delta")
        user_mid = int(first.meta.get("userMessageId"))
        stop = api(b_base, "POST", f"/api/v1/conversations/{conversation_id}/messages/{user_mid}/stop",
                   token=token)
        if not first.wait_done(25):
            raise RuntimeError("跨实例停止后 A 未收到 done")
        check(first.done and first.done.get("status") == "STOPPED",
              f"跨实例停止：A done={first.done.get('status') if first.done else None}")
        time.sleep(0.5)
        stopped = latest_assistant()
        check(bool(stopped and stopped.get("status") == "STOPPED"),
              "跨实例停止：数据库最新 assistant=STOPPED")
        target_id = int(stopped["id"]) if stopped else None
        first.close()

        # 2) 跨实例重新生成：A 旧流运行中，B 触发 regenerate，旧流应停止，新流成功
        if target_id is None:
            raise RuntimeError("没有可用于重新生成的 assistant 消息")
        old = send_slow(a_base, "长回答 regen")
        if not old.wait_for(lambda s: s.meta is not None and len(s.content) >= 20, 20, "regen 旧流"):
            raise RuntimeError("regen 旧流没有内容")
        regen = stream(b_base, f"/api/v1/conversations/{conversation_id}/messages/{target_id}/regenerate",
                       method="POST")
        if not old.wait_done(30):
            raise RuntimeError("regenerate 后旧流未结束")
        check(old.done and old.done.get("status") == "STOPPED",
              f"跨实例重生成：旧流={old.done.get('status') if old.done else None}")
        if not regen.wait_done(60):
            raise RuntimeError("regenerate 新流未完成")
        check(regen.done and regen.done.get("status") == "SUCCESS",
              f"跨实例重生成：新流={regen.done.get('status') if regen.done else None}")
        old.close()
        regen.close()

        # 3) 跨实例续传：A 生成，客户端断开，B 用 offset 继续读
        resumed_source = send_slow(a_base, "长回答 resume")
        if not resumed_source.wait_for(lambda s: s.meta is not None and len(s.content) >= 36, 20, "resume 前段"):
            raise RuntimeError("续传源流没有足够内容")
        offset = len(resumed_source.content)
        initial = resumed_source.content
        resumed_source.close()  # 模拟页面/网络断开，不发送 stop
        time.sleep(0.3)
        resume = stream(b_base,
                        f"/api/v1/conversations/{conversation_id}/resume?offset={offset}")
        if not resume.wait_for(lambda s: s.meta is not None, 20, "resume meta"):
            raise RuntimeError("跨实例续传没有收到 meta")
        check(bool(resume.meta and resume.meta.get("resumed") is True),
              "跨实例续传：meta.resumed=true")
        if not resume.wait_done(60):
            raise RuntimeError("跨实例续传未收到 done")
        check(resume.done and resume.done.get("status") == "SUCCESS",
              f"跨实例续传：done={resume.done.get('status') if resume.done else None}")
        check(len(initial) >= 36 and len(resume.content) > 0,
              f"跨实例续传：offset={offset}，续传增量={len(resume.content)}")
        resume.close()

        # 4) 实例宕机：B 生成中杀进程，A 上 resume 应在 stale 阈值后返回 UNKNOWN
        if crash_available:
            crash = send_slow(b_base, "长回答 crash")
            if not crash.wait_for(lambda s: s.meta is not None and len(s.content) >= 20, 20, "crash 前段"):
                raise RuntimeError("crash 流没有内容")
            crash_offset = len(crash.content)
            crash.close()
            try:
                os.killpg(os.getpgid(proc_b.pid), signal.SIGTERM)
            except Exception:
                proc_b.terminate()
            try:
                proc_b.wait(timeout=15)
            except subprocess.TimeoutExpired:
                proc_b.kill()
            ok("实例 B 已终止（模拟宕机）")
            time.sleep(max(args.stale_ms / 1000.0, 1.0) + 1.0)
            dead = stream(a_base,
                          f"/api/v1/conversations/{conversation_id}/resume?offset={crash_offset}")
            if not dead.wait_done(45):
                raise RuntimeError("宕机后续传未结束")
            check(dead.done and dead.done.get("status") == "UNKNOWN",
                  f"实例宕机清理：resume done={dead.done.get('status') if dead.done else None}")
            dead.close()
        else:
            log("SKIP  外部实例模式不执行 kill；宕机路径由单元/手工覆盖")

        log(f"结果：PASS={PASS} FAIL={FAIL}")
        return 0 if FAIL == 0 else 1
    except Exception as e:
        log(f"ERROR: {e}")
        bad(str(e))
        return 1
    finally:
        if conversation_id:
            try:
                api(a_base, "DELETE", f"/api/v1/conversations/{conversation_id}", token=token)
            except Exception:
                pass
        if agent_id:
            try:
                api(a_base, "DELETE", f"/api/v1/agent/{agent_id}", token=token)
            except Exception:
                pass
        cleanup_provider(provider_id, None)
        if not args.external:
            for proc in reversed(procs):
                try:
                    if proc.poll() is None:
                        os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
                        proc.wait(timeout=10)
                except Exception:
                    try:
                        proc.kill()
                    except Exception:
                        pass
        for f in logs:
            try:
                f.close()
            except Exception:
                pass
        if args.keep_logs:
            log(f"日志目录：{log_dir}")
        else:
            shutil.rmtree(log_dir, ignore_errors=True)
        log(f"总耗时 {int(time.time() - started)}s")


if __name__ == "__main__":
    sys.exit(main())
