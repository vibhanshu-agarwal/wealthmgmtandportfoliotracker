package com.wealth.portfolio.seed;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Gates every {@code /api/internal/**} request behind a shared-secret header.
 *
 * <p>Fail-closed semantics: if {@code app.internal.api-key} is blank (i.e. the Lambda
 * {@code INTERNAL_API_KEY} env var is missing) the filter rejects all internal requests
 * with HTTP 503. A blank configured secret must never be treated as "allow".
 *
 * <p>Header comparison uses {@link MessageDigest#isEqual} on the UTF-8 bytes to avoid
 * timing oracles. This filter is servlet-scoped and runs on portfolio-service's Spring
 * MVC stack; non-{@code /api/internal/} paths pass through untouched.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalApiKeyFilter.class);
    private static final String HEADER = "X-Internal-Api-Key";
    private static final String INTERNAL_PREFIX = "/api/internal/";

    private final byte[] expectedKey;

    public InternalApiKeyFilter(@Value("${app.internal.api-key:}") String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            this.expectedKey = null;
            log.warn("app.internal.api-key is blank — all /api/internal/** requests will be rejected with 503");
        } else {
            this.expectedKey = configuredKey.getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith(INTERNAL_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        if (expectedKey == null) {
            writeError(response, 503, "internal_api_key_not_configured");
            return;
        }

        String provided = request.getHeader(HEADER);
        if (provided == null) {
            writeError(response, 403, "invalid_internal_api_key");
            return;
        }
        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedKey, providedBytes)) {
            writeError(response, 403, "invalid_internal_api_key");
            return;
        }

        chain.doFilter(request, response);
    }

    private static void writeError(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }
}
