-- 会话置顶（前端会话列表排序）
ALTER TABLE conversation ADD COLUMN pinned TINYINT NOT NULL DEFAULT 0 COMMENT '1=置顶' AFTER title;
