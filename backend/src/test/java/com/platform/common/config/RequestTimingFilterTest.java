package com.platform.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestTimingFilterTest {

    @Test
    void preservesResponseWhileRecordingFirstByte() throws Exception {
        RequestTimingFilter filter = new RequestTimingFilter();
        ReflectionTestUtils.setField(filter, "enabled", true);
        ReflectionTestUtils.setField(filter, "slowMs", 0L);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                res.getOutputStream().write("ok".getBytes(StandardCharsets.UTF_8)));

        assertEquals("ok", response.getContentAsString());
    }
}
