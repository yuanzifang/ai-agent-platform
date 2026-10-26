package com.platform.eval.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryRewriteServiceTest {

    private final QueryRewriteService service = new QueryRewriteService(null);

    @Test
    void localRewriteStripsInstructionShellAndKeepsEntities() {
        assertEquals("用户说退款太慢", service.rewriteLocal("用户说退款太慢，帮我改写查询"));
        assertEquals("退款怎么申请", service.rewriteLocal("请帮我改写查询：退款怎么申请"));
        assertEquals("退款怎么申请", service.rewriteLocal("退款怎么申请"));
    }

    @Test
    void localRewriteFallsBackToOriginalWhenInstructionOnly() {
        assertEquals("帮我改写查询", service.rewriteLocal("帮我改写查询"));
    }
}
