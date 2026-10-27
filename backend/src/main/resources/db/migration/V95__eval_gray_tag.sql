-- 灰度评测打标（12 步对照 07/12）：评测任务携带灰度标记，轨迹 tags 记 gray:<tag>，
-- 支撑模型切换后的 A/B 对比（GrayCompareService 按任务 gray_tag 聚合得分/通过率）
ALTER TABLE eval_task ADD COLUMN gray_tag VARCHAR(64) NULL;
