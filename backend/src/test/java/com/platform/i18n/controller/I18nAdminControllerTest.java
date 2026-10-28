package com.platform.i18n.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.i18n.entity.I18nMessage;
import com.platform.i18n.service.I18nService;
import com.platform.security.JwtPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 控制器装配层测试（规矩：改完测试必须跟上——@AuthenticationPrincipal 漏注解这类
 * MVC 绑定 bug 单测层抓不到，曾在页面全 400 后才被发现）。
 *
 * <p>standalone MockMvc + AuthenticationPrincipalArgumentResolver + SecurityContextHolder：
 * 不起 Spring 上下文，速度快；鉴权解析链路与生产一致。
 * 若控制器方法漏写 @AuthenticationPrincipal，JwtPrincipal 会走模型属性绑定
 * （platformAdmin null→boolean 转换失败），本测试会以 400/500 而非预期结果失败。</p>
 */
class I18nAdminControllerTest {

    private final I18nService i18nService = mock(I18nService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new I18nAdminController(i18nService))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final JwtPrincipal ADMIN = new JwtPrincipal(1L, null, true, List.of(), List.of());
    private static final JwtPrincipal TENANT_OWNER = new JwtPrincipal(2L, 1L, false, List.of("OWNER"), List.of());

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ADMIN, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminSeesPageList() throws Exception {
        Page<I18nMessage> page = new Page<>(1, 20);
        page.setRecords(List.of(message("zh-CN", "menu.agents", "Agent 列表")));
        page.setTotal(2690);
        when(i18nService.page(eq("zh-CN"), org.mockito.ArgumentMatchers.isNull(), anyLong(), anyLong()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/i18n/admin/messages")
                        .param("lang", "zh-CN").param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2690))
                .andExpect(jsonPath("$.data.records[0].messageKey").value("menu.agents"));
    }

    @Test
    void adminUpsertDelegatesWithUpdatedBy() throws Exception {
        mockMvc.perform(post("/api/v1/i18n/admin/messages")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lang", "zh-CN", "key", "menu.agents",
                                "content", "代理列表", "description", "菜单文案"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(i18nService).upsert("zh-CN", "menu.agents", "代理列表", "菜单文案", 1L);
    }

    @Test
    void adminImportReturnsCounts() throws Exception {
        when(i18nService.importMessages(eq("ja-JP"), eq("json"), anyString(), eq(1L)))
                .thenReturn(Map.of("total", 2, "inserted", 2, "updated", 0, "skipped", 0));
        mockMvc.perform(post("/api/v1/i18n/admin/import")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lang", "ja-JP", "format", "json",
                                "content", "{\"app.name\":\"プラットフォーム\"}"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inserted").value(2));
    }

    @Test
    void tenantOwnerForbidden() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(TENANT_OWNER, null));
        expectForbidden(() -> mockMvc.perform(get("/api/v1/i18n/admin/messages")));
    }

    @Test
    void anonymousForbidden() throws Exception {
        SecurityContextHolder.clearContext();
        expectForbidden(() -> mockMvc.perform(get("/api/v1/i18n/admin/messages")));
    }

    /**
     * standalone 无异常处理器，未处理异常在 perform() 抛 NestedServletException：
     * 断言异常链内为 BusinessException(403)（生产环境由 GlobalExceptionHandler 转 HTTP 403）。
     */
    private static void expectForbidden(ThrowingCallable call) {
        try {
            call.run();
            org.junit.jupiter.api.Assertions.fail("应拒绝非平台管理员访问");
        } catch (jakarta.servlet.ServletException e) {
            Throwable t = e;
            while (t != null) {
                if (t instanceof com.platform.common.exception.BusinessException be) {
                    org.junit.jupiter.api.Assertions.assertEquals(403, be.getCode(), be.getMessage());
                    return;
                }
                t = t.getCause();
            }
            throw new AssertionError("异常链中无 BusinessException: " + e.getCause());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private interface ThrowingCallable {
        void run() throws Exception;
    }

    @Test
    void deleteDelegates() throws Exception {
        mockMvc.perform(delete("/api/v1/i18n/admin/messages/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private I18nMessage message(String lang, String key, String content) {
        I18nMessage m = new I18nMessage();
        m.setLang(lang);
        m.setMessageKey(key);
        m.setContent(content);
        return m;
    }
}
