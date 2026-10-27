-- 国际化字段映射表（需求：前端 i18n 字段改从后端获取，支持多语言/导入/导出/编辑）
-- 前端静态语言包打底，本表条目 merge 覆盖；管理端（平台管理员）可编辑/导入/导出
CREATE TABLE i18n_message (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    lang        VARCHAR(16)  NOT NULL,
    message_key VARCHAR(255) NOT NULL,
    content     TEXT         NOT NULL,
    description VARCHAR(500) NULL,
    updated_by  BIGINT       NULL,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_i18n_lang_key (lang, message_key),
    KEY idx_i18n_lang (lang)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
