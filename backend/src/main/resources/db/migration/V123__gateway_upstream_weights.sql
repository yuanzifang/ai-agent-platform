-- 网关上游权重（JSON URL->weight）；未配置时主上游 100、备用/额外上游 0（保持原有故障切换语义）。
ALTER TABLE gateway_route
    ADD COLUMN upstream_weights TEXT NULL AFTER upstream_urls;
