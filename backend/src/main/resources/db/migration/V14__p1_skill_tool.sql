-- P1 Agent 能力：Skill 管理 / Tool 管理（需求 3.13 / 3.14、数据字典 6.42–6.46）
CREATE TABLE IF NOT EXISTS skill_registry (
    id BIGINT NOT NULL AUTO_INCREMENT,
    skill_key VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512) NULL,
    icon_url VARCHAR(512) NULL,
    category VARCHAR(64) NULL,
    tags JSON NULL,
    skill_type VARCHAR(16) NOT NULL DEFAULT 'CUSTOM',
    prompt TEXT NULL,
    params JSON NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    is_builtin TINYINT NOT NULL DEFAULT 0,
    tenant_id BIGINT NULL,
    created_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill_key (skill_key),
    KEY idx_skill_tenant (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS skill_dependency (
    id BIGINT NOT NULL AUTO_INCREMENT,
    skill_id BIGINT NOT NULL,
    dep_type VARCHAR(16) NOT NULL,
    dep_id BIGINT NOT NULL,
    tenant_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill_dep (skill_id, dep_type, dep_id),
    KEY idx_skill_dep_skill (skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS skill_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    skill_id BIGINT NOT NULL,
    version VARCHAR(32) NOT NULL,
    snapshot JSON NOT NULL,
    created_by BIGINT NULL,
    tenant_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_skill_version_skill (skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tool_registry (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tool_key VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512) NULL,
    icon_url VARCHAR(512) NULL,
    category VARCHAR(64) NULL,
    tags JSON NULL,
    tool_type VARCHAR(16) NOT NULL DEFAULT 'API',
    api_config JSON NULL,
    mcp_server_id BIGINT NULL,
    timeout_ms INT NOT NULL DEFAULT 10000,
    retry_times INT NOT NULL DEFAULT 2,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    is_builtin TINYINT NOT NULL DEFAULT 0,
    tenant_id BIGINT NULL,
    created_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tool_key (tool_key),
    KEY idx_tool_tenant (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tool_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tool_id BIGINT NOT NULL,
    version VARCHAR(32) NOT NULL,
    snapshot JSON NOT NULL,
    created_by BIGINT NULL,
    tenant_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tool_version_tool (tool_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------- 内置工具（tenant_id 为空 = 全租户可见，只读） ----------
INSERT INTO tool_registry (tool_key, name, description, category, tool_type, api_config,
                           timeout_ms, retry_times, status, is_builtin, tenant_id)
VALUES ('calculator', '计算器', '计算数学表达式（加减乘除、括号、幂、常用函数），用于精确数值计算。',
        '效率', 'BUILTIN',
        '{"inputSchema":{"type":"object","properties":{"expression":{"type":"string","description":"数学表达式，如 (12+3)*4/2 或 sqrt(16)"}},"required":["expression"]}}',
        5000, 0, 'ENABLED', 1, NULL),
       ('current_time', '当前时间', '获取当前日期与时间，可指定 IANA 时区（默认 Asia/Shanghai）。',
        '效率', 'BUILTIN',
        '{"inputSchema":{"type":"object","properties":{"timezone":{"type":"string","description":"IANA 时区，如 Asia/Shanghai、UTC"}}}}',
        3000, 0, 'ENABLED', 1, NULL);

-- ---------- 内置技能（tenant_id 为空 = 全租户可见，只读） ----------
INSERT INTO skill_registry (skill_key, name, description, category, skill_type, prompt, params,
                            status, is_builtin, tenant_id)
VALUES ('builtin_translator', '多语言翻译', '将文本翻译为目标语言，保持术语与语气一致。', '内容', 'BUILTIN',
        '你具备专业多语言翻译能力。用户要求翻译时：先确认目标语言（未指定时中英互译）；保持专有名词、数字、格式不变；只输出译文，不要解释，除非用户要求。',
        '[]', 'ENABLED', 1, NULL),
       ('builtin_summarizer', '内容摘要', '提炼长文本的关键信息，输出结构化摘要。', '内容', 'BUILTIN',
        '你擅长内容摘要。对用户提供的长文本：先用一句话概括主旨，再列出 3-7 条关键要点；保留关键数字与结论；不复述原文。',
        '[]', 'ENABLED', 1, NULL),
       ('builtin_data_analyst', '数据分析', '对数据做统计与计算，可调用计算器工具保证数值准确。', '分析', 'BUILTIN',
        '你是数据分析助手。回答涉及数值计算时优先调用 calculator 工具，避免口算误差；给出结论时说明口径、单位与必要的计算过程。',
        '[]', 'ENABLED', 1, NULL);

INSERT INTO skill_dependency (skill_id, dep_type, dep_id, tenant_id)
SELECT s.id, 'TOOL', t.id, NULL
FROM skill_registry s JOIN tool_registry t ON t.tool_key = 'calculator'
WHERE s.skill_key = 'builtin_data_analyst';
