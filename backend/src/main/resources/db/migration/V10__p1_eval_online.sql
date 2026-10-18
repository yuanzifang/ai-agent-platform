-- P1 在线监控（需求 3.5）：trace 评分回填列
ALTER TABLE agent_trace ADD COLUMN score DECIMAL(6,4) NULL COMMENT '在线监控评测回填分数';
