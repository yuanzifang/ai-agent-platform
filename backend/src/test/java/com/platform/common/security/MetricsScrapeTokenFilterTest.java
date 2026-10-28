package com.platform.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.servlet.FilterChain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MetricsScrapeTokenFilterTest {

    private final FilterChain chain = mock(FilterChain.class);

    @Test
    void tokenOptionalForBackwardCompatibility() throws Exception {
        MetricsScrapeTokenFilter filter = filter("");
        MockHttpServletRequest request = request("/actuator/prometheus");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        verify(chain).doFilter(request, response);
    }

    @Test
    void tokenRequiredWhenConfigured() throws Exception {
        MetricsScrapeTokenFilter filter = filter("secret");
        MockHttpServletRequest request = request("/actuator/prometheus");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void acceptsHeaderAndBearerToken() throws Exception {
        MetricsScrapeTokenFilter filter = filter("secret");
        MockHttpServletRequest headerRequest = request("/actuator/prometheus");
        headerRequest.addHeader("X-Metrics-Token", "secret");
        MockHttpServletResponse headerResponse = new MockHttpServletResponse();
        filter.doFilter(headerRequest, headerResponse, chain);
        assertEquals(200, headerResponse.getStatus());

        MockHttpServletRequest bearerRequest = request("/actuator/prometheus");
        bearerRequest.addHeader("Authorization", "Bearer secret");
        MockHttpServletResponse bearerResponse = new MockHttpServletResponse();
        filter.doFilter(bearerRequest, bearerResponse, chain);
        assertEquals(200, bearerResponse.getStatus());
    }

    @Test
    void healthEndpointIsNotFiltered() throws Exception {
        MetricsScrapeTokenFilter filter = filter("secret");
        MockHttpServletRequest request = request("/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        verify(chain).doFilter(request, response);
    }

    private MetricsScrapeTokenFilter filter(String token) {
        MetricsScrapeTokenFilter filter = new MetricsScrapeTokenFilter();
        ReflectionTestUtils.setField(filter, "scrapeToken", token);
        return filter;
    }

    private MockHttpServletRequest request(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }
}
