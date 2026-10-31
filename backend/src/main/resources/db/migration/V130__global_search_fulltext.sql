-- 全局搜索：名称/描述/问题文本的 MySQL InnoDB 全文索引（ngram 支持中文）。
-- 统一搜索仍复用各模块的权限过滤，只把关键词匹配从 LIKE 升级为 FULLTEXT。
ALTER TABLE agent_registry
    ADD FULLTEXT INDEX ft_agent_search (name, description) WITH PARSER ngram;

ALTER TABLE knowledge_base
    ADD FULLTEXT INDEX ft_kb_search (name, description) WITH PARSER ngram;

ALTER TABLE test_case
    ADD FULLTEXT INDEX ft_case_search (question, expected_answer) WITH PARSER ngram;

ALTER TABLE tool_registry
    ADD FULLTEXT INDEX ft_tool_search (name, description) WITH PARSER ngram;
