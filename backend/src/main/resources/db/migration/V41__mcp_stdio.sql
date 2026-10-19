-- MCP stdio 传输：启动命令 / 参数 / 环境变量（AES-GCM 加密存储）
ALTER TABLE mcp_server
    ADD COLUMN command VARCHAR(512) NULL COMMENT 'STDIO 启动命令（如 npx / python3）' AFTER transport,
    ADD COLUMN args JSON NULL COMMENT 'STDIO 启动参数（JSON 数组）' AFTER command,
    ADD COLUMN env_encrypted TEXT NULL COMMENT 'STDIO 环境变量（AES-GCM 密文 JSON）' AFTER args;
