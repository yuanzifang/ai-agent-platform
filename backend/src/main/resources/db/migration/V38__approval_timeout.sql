-- 工作流审批超时
ALTER TABLE workflow_approval ADD COLUMN expire_at DATETIME NULL AFTER required_role;
