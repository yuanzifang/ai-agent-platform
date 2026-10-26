-- 解析告警：OCR 超页、图表压缩/丢弃等非致命问题，文档仍可 DONE 但需要提示用户
ALTER TABLE knowledge_document
    ADD COLUMN parse_warning VARCHAR(1024) NULL COMMENT '解析告警（OCR 覆盖不足、图表压缩等）'
    AFTER error_message;
