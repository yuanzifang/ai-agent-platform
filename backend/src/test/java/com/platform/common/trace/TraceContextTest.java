package com.platform.common.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceContextTest {

    @AfterEach
    void clear() {
        TraceContext.clear();
    }

    @Test
    void usesIncomingTraceparentAndEchoesRequestHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceContext.HEADER_TRACEPARENT,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        request.addHeader(TraceContext.HEADER_REQUEST_ID, "req-abc.123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        TraceContext.Context context = TraceContext.start(request, response);

        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", context.traceId());
        assertEquals("req-abc.123", context.requestId());
        assertEquals(context.traceId(), MDC.get(TraceContext.MDC_TRACE_ID));
        assertEquals(context.traceId(), response.getHeader(TraceContext.HEADER_TRACE_ID));
        assertEquals("req-abc.123", response.getHeader(TraceContext.HEADER_REQUEST_ID));
    }

    @Test
    void invalidTraceparentFallsBackToNewTraceId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceContext.HEADER_TRACEPARENT,
                "00-00000000000000000000000000000000-00f067aa0ba902b7-01");
        MockHttpServletResponse response = new MockHttpServletResponse();

        TraceContext.Context context = TraceContext.start(request, response);

        assertEquals(32, context.traceId().length());
        assertTrue(context.traceId().matches("[0-9a-f]{32}"));
        assertNotEquals("00000000000000000000000000000000", context.traceId());
    }

    @Test
    void unsafeRequestIdIsReplaced() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceContext.HEADER_REQUEST_ID, "bad id with spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        TraceContext.Context context = TraceContext.start(request, response);

        assertNotNull(context.requestId());
        assertNotEquals("bad id with spaces", context.requestId());
        assertEquals(32, context.requestId().length());
    }

    @Test
    void wrappedRunnableRestoresCallerContext() {
        TraceContext.put("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "request-a");
        Runnable wrapped = TraceContext.wrap(() -> {
            assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", TraceContext.currentTraceId());
            assertEquals("request-a", TraceContext.currentRequestId());
            TraceContext.put("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "request-b");
        });

        wrapped.run();

        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", TraceContext.currentTraceId());
        assertEquals("request-a", TraceContext.currentRequestId());
        TraceContext.clear();
        assertNull(TraceContext.currentTraceId());
    }
}
