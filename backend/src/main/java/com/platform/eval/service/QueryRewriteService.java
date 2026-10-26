package com.platform.eval.service;

import com.platform.model.service.ChatModelRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

/** 查询改写器：可被步骤评测使用；生产对话接入前先在改写黄金集上验收。 */
@Service
@RequiredArgsConstructor
public class QueryRewriteService {

    private final ChatModelRouter chatModelRouter;

    /**
     * 离线确定性 fallback：只去掉“帮我改写查询”等指令壳，保留原查询实体。
     * 不调用任何模型，用于无外网/无模型配置时仍可跑 REWRITE 步骤评测。
     */
    public String rewriteLocal(String query) {
        String original = query == null ? "" : query.strip();
        String text = original.replaceAll("\\s+", " ");
        text = text.replaceFirst(
                "(?i)^(请|请帮我|帮我|帮忙)?\\s*(改写|重写|rewrite)\\s*(一下)?\\s*(查询|问题)?\\s*[：:]?\\s*",
                "");
        text = text.replaceFirst(
                "(?i)[\\s,，。;；]*(请|请帮我|帮我|帮忙)?\\s*(改写|重写|rewrite)\\s*(一下)?\\s*(查询|问题)?\\s*[。！!?？]?$",
                "");
        text = text.strip();
        return text.isBlank() ? original : text;
    }

    public String rewrite(String query, String modelName, String instructions) {
        String model = modelName == null || modelName.isBlank() ? "deepseek-flash" : modelName;
        String guide = instructions == null || instructions.isBlank()
                ? "保留全部实体、约束和意图，只做必要消歧/补全，不要回答问题。"
                : instructions;
        var response = ChatClient.create(chatModelRouter.resolve(model)).prompt()
                .options(chatModelRouter.options(model, null, List.of()))
                .system("你是查询改写器，只输出改写后的查询文本，不要解释。规则：" + guide)
                .user(query == null ? "" : query)
                .call().chatResponse();
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text.trim();
    }
}
