CREATE TABLE IF NOT EXISTS chat_checkpoint (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    trace_id VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    content MEDIUMTEXT,
    message_id BIGINT,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    citations_json TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chat_checkpoint_conversation (conversation_id),
    KEY idx_chat_checkpoint_tenant (tenant_id, updated_at)
);
