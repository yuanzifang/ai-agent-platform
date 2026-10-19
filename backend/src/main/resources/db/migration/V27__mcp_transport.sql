-- MCP 传输与自定义请求头（飞书/智谱等外部 MCP 接入）
ALTER TABLE mcp_server
    ADD COLUMN transport VARCHAR(16) NOT NULL DEFAULT 'HTTP' AFTER endpoint,
    ADD COLUMN custom_headers JSON NULL AFTER auth_type;
