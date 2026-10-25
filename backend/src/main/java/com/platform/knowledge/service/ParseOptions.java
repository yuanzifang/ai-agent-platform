package com.platform.knowledge.service;

/**
 * 解析生效配置（需求 3.6/评审设计）：解析链 env → 平台(sys_config) → 租户(sys_tenant_config)
 * → 知识库(parse_config) → 文档(上传时选择) 逐级覆盖后的最终值。
 * 安全边界：ocrCommand/visionEndpoint 只来自服务端 env 与平台管理员配置，
 * 不开放给知识库/文档级覆盖，避免命令注入与 SSRF。
 */
public record ParseOptions(
        boolean ocrEnabled,
        String ocrCommand,
        String ocrLanguages,
        int ocrDpi,
        int ocrMaxPages,
        boolean visionEnabled,
        String visionEndpoint,
        String visionModel,
        int visionMaxImages) {

    public static ParseOptions defaults(boolean ocrEnabled, String ocrCommand, String ocrLanguages,
                                        int ocrDpi, int ocrMaxPages, boolean visionEnabled,
                                        String visionEndpoint, String visionModel, int visionMaxImages) {
        return new ParseOptions(ocrEnabled, ocrCommand, ocrLanguages, ocrDpi, ocrMaxPages,
                visionEnabled, visionEndpoint, visionModel, visionMaxImages);
    }
}
