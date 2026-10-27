-- 门禁裁判模型可在系统设置中切换；空值回落到启动参数 eval.gate-judge-model。
INSERT INTO sys_config (config_key, config_value, description)
VALUES ('eval.gate-judge-model', '', '门禁裁判模型；留空使用启动参数/默认 deepseek-flash；可填平台本地模型，如 qwen2.5:7b')
ON DUPLICATE KEY UPDATE description = VALUES(description);
