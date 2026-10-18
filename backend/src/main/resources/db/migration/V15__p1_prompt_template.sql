-- P1 Prompt 模板管理（需求 3.16、数据字典 6.48）
CREATE TABLE IF NOT EXISTS prompt_template (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    category VARCHAR(64) NULL,
    content TEXT NOT NULL,
    variables JSON NULL,
    tenant_id BIGINT NOT NULL,
    created_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_prompt_template_tenant_name (tenant_id, name),
    KEY idx_prompt_template_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
