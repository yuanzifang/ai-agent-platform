-- 模型上下文窗口（tokens，0=未知，运行时回落默认）
ALTER TABLE model ADD COLUMN context_window INT NOT NULL DEFAULT 0 AFTER output_price;
