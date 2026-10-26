ALTER TABLE knowledge_document
    ADD COLUMN upgrade_attempts INT NOT NULL DEFAULT 0 COMMENT '存量升级已提交解析次数' AFTER upgrade_status;
