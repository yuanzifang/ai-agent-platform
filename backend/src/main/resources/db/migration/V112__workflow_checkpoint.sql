CREATE TABLE IF NOT EXISTS workflow_checkpoint (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    node_id VARCHAR(64),
    next_node_id VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    context_json JSON,
    output_text TEXT,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_workflow_checkpoint_run (run_id, id)
);
