-- P1 MFA（TOTP，需求 3.25(5)）
CREATE TABLE IF NOT EXISTS mfa_recovery_code (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    code_hash CHAR(64) NOT NULL,
    used TINYINT NOT NULL DEFAULT 0,
    tenant_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mfa_recovery (user_id, code_hash),
    KEY idx_mfa_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
