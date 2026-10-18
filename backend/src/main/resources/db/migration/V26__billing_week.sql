-- 周账单：账期可能为 yyyy-Www（8 字符）
ALTER TABLE billing_bill MODIFY COLUMN period VARCHAR(16) NOT NULL;
