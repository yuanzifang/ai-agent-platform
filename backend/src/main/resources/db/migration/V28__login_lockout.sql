-- 登录失败锁定（需求 3.25(1)）
ALTER TABLE sys_user ADD COLUMN failed_attempts INT NOT NULL DEFAULT 0 AFTER locked_until;
