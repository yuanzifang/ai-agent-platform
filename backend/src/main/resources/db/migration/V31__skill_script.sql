-- 技能脚本：脚本作为工具暴露给 Agent（Python/JavaScript）
ALTER TABLE skill_registry ADD COLUMN script_language VARCHAR(16) NULL AFTER params;
ALTER TABLE skill_registry ADD COLUMN script_code MEDIUMTEXT NULL AFTER script_language;
ALTER TABLE skill_registry ADD COLUMN script_timeout_ms INT NOT NULL DEFAULT 5000 AFTER script_code;
