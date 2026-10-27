CREATE TABLE knowledge_ocr_cache (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NULL,
    cache_key CHAR(64) NOT NULL,
    text LONGTEXT NULL,
    table_html LONGTEXT NULL,
    created_at DATETIME NOT NULL,
    last_used_at DATETIME NOT NULL,
    UNIQUE KEY uk_ocr_cache_tenant_key (tenant_id, cache_key),
    KEY idx_ocr_cache_last_used (last_used_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OCR 结果持久化缓存';
