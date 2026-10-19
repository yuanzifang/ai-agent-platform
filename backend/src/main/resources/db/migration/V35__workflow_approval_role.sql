-- 工作流审批角色限制
ALTER TABLE workflow_approval ADD COLUMN required_role VARCHAR(32) NULL AFTER title;
