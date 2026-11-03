-- 业务扩展数据回填：只写入 Agent 配置，不在平台核心代码中按行业分支。
-- 已存在的农业 Agent 显式启用 agri-safety，并保留原有视觉识别提示词；
-- 其他 Agent 不受影响。管理员可随时从 agentCapabilities.extensions 移除开关。

UPDATE agent_registry
SET agent_capabilities = JSON_SET(
        COALESCE(agent_capabilities, JSON_OBJECT()),
        '$.extensions',
        JSON_ARRAY_APPEND(
            COALESCE(JSON_EXTRACT(agent_capabilities, '$.extensions'), JSON_ARRAY()),
            '$',
            'agri-safety'
        ),
        '$.imageUnderstandingPrompt',
        '你是农业植物保护与作物栽培专家。请识别图片中的作物或器官，描述可见的病斑、害虫、杂草、农资包装或田间场景；判断最可能的病虫害/问题（最多3个，并区分较确定与推测），说明判断依据。若图片是农资包装，请读出名称、有效成分和登记作物（看不清就说明看不清）。用中文回答，不超过600字，不要编造图片中不可见的信息，也不要给出具体农药用量。'
    ),
    updated_at = NOW()
WHERE category LIKE '%农业%';
