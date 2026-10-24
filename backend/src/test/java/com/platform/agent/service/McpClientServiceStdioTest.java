package com.platform.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.agent.entity.McpServer;
import com.platform.common.security.CryptoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MCP stdio 传输单元测试：使用 python3 起的假 MCP Server 验证
 * 会话复用（同进程）、structuredContent/annotations 解析、命令注入防护与环境变量注入。
 */
class McpClientServiceStdioTest {

    private static final String SCRIPT = """
            import sys, json, os

            def send(obj):
                sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\\n")
                sys.stdout.flush()

            def main():
                pid = os.getpid()
                for line in sys.stdin:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        msg = json.loads(line)
                    except Exception:
                        continue
                    if "id" not in msg:
                        continue
                    rid = msg["id"]
                    method = msg.get("method", "")
                    if method == "initialize":
                        send({"jsonrpc": "2.0", "id": rid, "result": {"protocolVersion": "2025-03-26",
                              "capabilities": {"tools": {}}, "serverInfo": {"name": "stdio-mock", "version": "1.0"}}})
                    elif method == "tools/list":
                        send({"jsonrpc": "2.0", "id": rid, "result": {"tools": [{"name": "echo",
                              "description": "回显输入",
                              "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]},
                              "outputSchema": {"type": "object", "properties": {"echo": {"type": "string"}}},
                              "annotations": {"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True}}]}})
                    elif method == "tools/call":
                        params = msg.get("params") or {}
                        if params.get("name") == "slow":
                            import time
                            time.sleep(10)
                        args = params.get("arguments") or {}
                        text = str(args.get("text", ""))
                        send({"jsonrpc": "2.0", "id": rid, "result": {
                              "content": [{"type": "text", "text": "echo: " + text}],
                              "structuredContent": {"echo": text, "pid": pid, "env": os.environ.get("MCP_TEST_TOKEN", "")}}})
                    else:
                        send({"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": "method not found"}})

            if __name__ == "__main__":
                main()
            """;

    @TempDir
    Path tempDir;

    private CryptoService cryptoService;
    private ObjectMapper objectMapper;
    private McpClientService client;
    private McpServer server;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(pythonAvailable(), "需要 python3 运行 stdio 假 Server");
        cryptoService = mock(CryptoService.class);
        objectMapper = new ObjectMapper();
        client = new McpClientService(cryptoService, objectMapper,
                mock(org.springframework.data.redis.core.StringRedisTemplate.class),
                mock(McpOAuthService.class),
                mock(com.platform.agent.mapper.McpServerMapper.class));
        Path script = tempDir.resolve("mcp_stdio_mock.py");
        Files.writeString(script, SCRIPT);
        server = new McpServer();
        server.setId(9001L);
        server.setName("stdio-mock");
        server.setTransport("STDIO");
        server.setCommand("python3");
        server.setArgs(objectMapper.writeValueAsString(List.of(script.toString())));
        server.setStatus("ENABLED");
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.shutdown();
    }

    @Test
    void listsToolsWithAnnotationsAndOutputSchema() {
        List<McpClientService.McpTool> tools = client.listTools(server);
        assertEquals(1, tools.size());
        assertEquals("echo", tools.get(0).name());
        assertNotNull(tools.get(0).outputSchema());
        assertTrue(tools.get(0).annotations().contains("readOnlyHint"));
        assertTrue(tools.get(0).annotations().contains("destructiveHint"));
    }

    @Test
    void reusesSessionAndParsesStructuredContent() throws Exception {
        when(cryptoService.decrypt("enc")).thenReturn("{\"MCP_TEST_TOKEN\":\"hello\"}");
        server.setEnvEncrypted("enc");
        McpClientService.McpCallResult first = client.callTool(server, "echo", Map.of("text", "hi"));
        assertTrue(first.text().startsWith("echo: hi"), first.text());
        assertNotNull(first.structuredContent());
        McpClientService.McpCallResult second = client.callTool(server, "echo", Map.of("text", "again"));
        JsonNode firstNode = objectMapper.readTree(first.structuredContent());
        JsonNode secondNode = objectMapper.readTree(second.structuredContent());
        assertEquals(firstNode.path("pid").asInt(), secondNode.path("pid").asInt(), "两次调用应复用同一 stdio 进程");
        assertEquals("hello", secondNode.path("env").asText(), "环境变量应注入子进程");
    }

    @Test
    void cancellationKillsStdioProcessAndAllowsReconnect() {
        long start = System.currentTimeMillis();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.callTool(server, "slow", Map.of(), 
                        () -> System.currentTimeMillis() - start > 300));
        assertTrue(e.getMessage().contains("取消"), e.getMessage());
        assertTrue(System.currentTimeMillis() - start < 3000, "stdio 取消应在轮询周期内生效");
        // 会话已终止，下一次调用应自动重建进程
        McpClientService.McpCallResult again = client.callTool(server, "echo", Map.of("text", "after"));
        assertTrue(again.text().contains("after"), again.text());
    }

    @Test
    void rejectsShellCommand() {
        server.setCommand("bash");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> client.listTools(server));
        assertTrue(e.getMessage().contains("禁止以 shell"), e.getMessage());
    }

    @Test
    void rejectsCommandWithShellMetacharacters() {
        server.setCommand("python3; rm -rf /");
        assertThrows(IllegalStateException.class, () -> client.listTools(server));
    }

    private boolean pythonAvailable() {
        try {
            Process p = new ProcessBuilder("python3", "--version").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
