package com.wealth.portfolio.seed;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Servlet-level tests for {@link InternalApiKeyFilter}.
 *
 * <p>Runs the filter directly with Spring's mock HTTP objects; no Spring context is required.
 * Covers the five observable states from {@code doFilterInternal}: pass-through on non-internal
 * paths, 503 when the configured secret is blank, 403 on missing header, 403 on mismatch, and
 * pass-through on a correct header.
 */
class InternalApiKeyFilterTest {

    private static final String KEY = "s3cret-internal-key";
    private static final String INTERNAL_PATH = "/api/internal/portfolio/seed";

    @Test
    void nonInternalPathPassesThrough() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter(KEY);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/portfolio/summary");
        req.setRequestURI("/api/portfolio/summary");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void blankConfiguredKeyYields503OnInternalPath() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter("");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", INTERNAL_PATH);
        req.setRequestURI(INTERNAL_PATH);
        req.addHeader("X-Internal-Api-Key", KEY); // even a "correct" key is rejected when unconfigured
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getContentAsString()).contains("internal_api_key_not_configured");
    }

    @Test
    void missingHeaderYields403() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter(KEY);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", INTERNAL_PATH);
        req.setRequestURI(INTERNAL_PATH);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("invalid_internal_api_key");
    }

    @Test
    void wrongKeyYields403() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter(KEY);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", INTERNAL_PATH);
        req.setRequestURI(INTERNAL_PATH);
        req.addHeader("X-Internal-Api-Key", "wrong-key");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, never()).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    void correctKeyPassesThrough() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter(KEY);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", INTERNAL_PATH);
        req.setRequestURI(INTERNAL_PATH);
        req.addHeader("X-Internal-Api-Key", KEY);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void lengthMismatchRejectsWithoutLeakingLengthAsTiming() throws Exception {
        // MessageDigest.isEqual is constant-time only over equal-length inputs, but it
        // returns false on any length mismatch. Both short and long wrong-length keys
        // must be rejected with 403, not thrown or 500'd.
        InternalApiKeyFilter filter = new InternalApiKeyFilter(KEY);
        for (String attempt : new String[]{"x", KEY + "-extra-bytes", " "}) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", INTERNAL_PATH);
            req.setRequestURI(INTERNAL_PATH);
            req.addHeader("X-Internal-Api-Key", attempt);
            MockHttpServletResponse res = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(req, res, chain);

            verify(chain, never()).doFilter(req, res);
            assertThat(res.getStatus()).isEqualTo(403);
        }
    }
}
