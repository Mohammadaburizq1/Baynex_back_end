package com.byonix.shoplink.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class RateLimitFilterTest {
    @Test
    void customerLoginPathIsRateLimitedAfterTenRequests() throws Exception {
        RateLimitFilter filter = new RateLimitFilter();
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 10; i++) {
            filter.doFilter(loginRequest("203.0.113.10"), new MockHttpServletResponse(), chain);
        }

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(loginRequest("203.0.113.10"), blocked, chain);

        assertEquals(429, blocked.getStatus());
    }

    @Test
    void customerRegisterPathIsRateLimitedAfterFiveRequests() throws Exception {
        RateLimitFilter filter = new RateLimitFilter();
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 5; i++) {
            filter.doFilter(registerRequest("203.0.113.11"), new MockHttpServletResponse(), chain);
        }

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(registerRequest("203.0.113.11"), blocked, chain);

        assertEquals(429, blocked.getStatus());
    }

    @Test
    void merchantGoogleLoginPathIsRateLimitedAfterTenRequests() throws Exception {
        RateLimitFilter filter = new RateLimitFilter();
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 10; i++) {
            filter.doFilter(googleLoginRequest("203.0.113.12"), new MockHttpServletResponse(), chain);
        }

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(googleLoginRequest("203.0.113.12"), blocked, chain);

        assertEquals(429, blocked.getStatus());
    }

    private MockHttpServletRequest loginRequest(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/public/auth/login");
        request.setRemoteAddr(ip);
        return request;
    }

    private MockHttpServletRequest googleLoginRequest(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/google");
        request.setRemoteAddr(ip);
        return request;
    }

    private MockHttpServletRequest registerRequest(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/public/auth/register");
        request.setRemoteAddr(ip);
        return request;
    }
}
