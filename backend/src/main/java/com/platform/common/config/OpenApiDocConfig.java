package com.platform.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/**
 * 开发者文档（springdoc）：/swagger-ui.html 与 /v3/api-docs。
 * 平台内部接口用 JWT Bearer；开放 API（/api/v1/open）用 ApiKey 头。
 */
@Configuration
public class OpenApiDocConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("AI Agent 平台 API")
                        .description("多租户 AI Agent 平台：内部 API（JWT）与开放 API（ApiKey）")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes("bearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录接口获取 accessToken"))
                        .addSecuritySchemes("apiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key")
                                .description("Agent 集成页生成的开放 API 密钥"))
                )
                .addSecurityItem(new SecurityRequirement()
                        .addList("bearer")
                        .addList("apiKey"));
    }
}
