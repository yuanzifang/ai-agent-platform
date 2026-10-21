-- MCP 服务端 tools/list_changed 通知的持久化标记；多实例/重启后仍提示管理员重新同步。
ALTER TABLE mcp_server ADD COLUMN tools_changed_at DATETIME NULL AFTER last_checked_at;
