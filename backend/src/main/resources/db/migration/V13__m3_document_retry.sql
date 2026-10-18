-- 文档解析重试次数（需求 3.6.3(2)：失败后可手动重试，默认上限 3 次）
ALTER TABLE knowledge_document
    ADD COLUMN retry_times INT NOT NULL DEFAULT 0 AFTER error_message;
