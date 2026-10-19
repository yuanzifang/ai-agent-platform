-- 混合检索：知识分段全文索引（MySQL 8 ngram 分词，支持中文）
ALTER TABLE knowledge_chunk ADD FULLTEXT INDEX ft_knowledge_chunk_content (content) WITH PARSER ngram;
