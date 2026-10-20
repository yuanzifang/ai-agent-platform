package com.platform.conversation.service;

import java.util.List;

/**
 * 内容审核扩展点：内置关键词 / 第三方语义服务 / 私有模型均可实现该接口并注册为 Spring Bean。
 * 返回 BLOCK 将阻断，WARN 仅留痕。
 */
public interface ContentModerationProvider {

    String name();

    List<ModerationService.Hit> check(String direction, String content);
}
