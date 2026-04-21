# Login 403 & Access Denied Bugfix Design

## Overview

Users cannot log in on production because the `api-gateway` has two interrelated code-level bugs:

1. **CORS rejection** — `SecurityConfig.corsConfigurationSource()` hard-codes `setAllowedOrigins` to `http://localhost:3000` and `http://127.0.0.1:3000`. The production origin `https://vibhanshu-ai-portfolio.dev` is not in the list, so the browser blocks the preflight and reports a 403.
2. **JWT filter rejection** — `JwtAuthenticationFilter` only skips `/actuator/**` and `/api/portfolio/health`. The `permitAll()` auth endpoints (`/api/auth/**`) are not in the skip list, so the filter's `switchIfEmpty` branch returns 401 for requests that have no JWT principal. This bug is currently masked by the CORS failure.

The fix externalizes CORS origin patterns into profile-specific YAML, switches from `setAllowedOrigins` to `setAllowedOriginPatterns` (required when `allowCredentials=true` and pattern matching is needed), and adds `/api/auth/**` to the JWT filter's skip list while preserving the `X-User-Id` header-stripping security guardrail on the new skip path.

## Glossary

- **Bug_Condition (C)**: The union of two conditions — (a) a request arrives from a production origin not in the hard-coded CORS allow-list, OR (b) a request hits `/api/auth/**` without a JWT and the JWT filter rejects it
- **Property (P)**: The desired behavior — production origins receive valid CORS headers, and `/api/auth/**` requests pass through the JWT filter without a 401
- **Preservation**: Existing behaviors that must remain unchanged — localhost CORS acceptance, JWT validation on protected endpoints, `X-User-Id` stripping on all paths, `allowCredentials=true`
- **SecurityConfig**: The Spring Security configuration class in `api-gateway/src/main/java/com/wealth/gateway/SecurityConfig.java` that defines the filter chain, CORS policy, and authorization rules
- **JwtAuthenticationFilter**: The `GlobalFilter` in `api-gateway/src/main/java/com/wealth/gateway/JwtAuthenticationFilter.java` that strips spoofed `X-User-Id` headers, extracts the JWT `sub` claim, and injects `X-User-Id` for downstream services
- **setAllowedOriginPatterns**: Spring's `CorsConfiguration` method that supports both exact origins and glob patterns (e.g. `https://*.example.com`) and is compatible with `allowCredentials=true`

## Bug Details

### Bug Condition

The bug manifests in two stages. First, any request from the production origin is rejected by CORS because the allow-list is hard-coded to localhost values. Second, once CORS is fixed, requests to `/api/auth/**` endpoints (login, register) are rejected by the JWT filter because those paths are not in its skip list and `permitAll()` paths carry no JWT principal.

**Formal Specification:**

```
FUNCTION isBugCondition(input)
  INPUT: input of type HttpRequest with fields {origin, path, hasJwt}
  OUTPUT: boolean

  corsCondition :=
    input.origin NOT IN ["http://localhost:3000", "http://127.0.0.1:3000"]
    AND input.origin MATCHES a legitimate production pattern
    AND request requires CORS validation (Origin header present)

  jwtFilterCondition :=
    input.path STARTS_WITH "/api/auth/"
    AND input.hasJwt = false

  RETURN corsCondition OR jwtFilterCondition
END FUNCTION
```

### Examples

- **CORS bug**: `POST /api/auth/login` with `Origin: https://vibhanshu-ai-portfolio.dev` → browser receives no `Access-Control-Allow-Origin` header → preflight fails → 403 reported to user
- **CORS bug (subdomain)**: `GET /api/portfolio` with `Origin: https://app.vibhanshu-ai-portfolio.dev` → same CORS rejection
- **JWT filter bug**: `POST /api/auth/login` with no `Authorization` header (after CORS is fixed) → `JwtAuthenticationFilter.switchIfEmpty` fires → 401 returned instead of passing through to the auth service
- **JWT filter bug (register)**: `POST /api/auth/register` with no `Authorization` header → same 401 rejection
- **Edge case (not a bug)**: `GET /api/portfolio` with no JWT → correctly returns 401 (this is expected behavior, not a bug)

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**

- `http://localhost:3000` and `http://127.0.0.1:3000` must continue to be accepted as valid CORS origins in the local profile
- Origins not in the allow-list (e.g. `https://evil.com`) must continue to be rejected with no `Access-Control-Allow-Origin` header
- `allowCredentials=true` must remain in CORS responses
- Authenticated endpoints (`/api/portfolio/**`, `/api/market/**`, etc.) must continue to require a valid JWT and inject `X-User-Id` from the `sub` claim
- Unauthenticated requests to protected endpoints must continue to return 401
- `/actuator/**` and `/api/portfolio/health` must continue to skip JWT processing
- `X-User-Id` header stripping must continue on ALL paths — including the newly skipped `/api/auth/**` paths (security guardrail)
- `CloudFrontOriginVerifyFilter` behavior is unchanged (no modifications to that filter)

**Scope:**
All inputs that do NOT involve (a) a production-origin CORS request or (b) a request to `/api/auth/**` should be completely unaffected by this fix. This includes:

- Requests from localhost origins in local profile
- Requests to authenticated endpoints with valid JWTs
- Requests to `/actuator/**` and `/api/portfolio/health`
- Requests with no `Origin` header (non-CORS, e.g. server-to-server)

## Hypothesized Root Cause

Based on the bug description and code analysis, the root causes are:

1. **Hard-coded CORS origins in SecurityConfig**: `corsConfigurationSource()` calls `config.setAllowedOrigins(List.of("http://localhost:3000", "http://127.0.0.1:3000"))` with no mechanism to inject production origins. The production profile (`application-prod.yml`) has no `app.cors.*` properties, so the hard-coded values are always used.

2. **`setAllowedOrigins` incompatible with pattern matching**: Spring's `setAllowedOrigins` does not support glob patterns like `https://*.vibhanshu-ai-portfolio.dev`. When `allowCredentials=true`, the `*` wildcard is also rejected. The method `setAllowedOriginPatterns` is required for both pattern support and credentials compatibility.

3. **Incomplete JWT filter skip list**: `JwtAuthenticationFilter.filter()` only checks `path.startsWith("/actuator") || path.equals("/api/portfolio/health")`. The `/api/auth/**` paths declared as `permitAll()` in `SecurityConfig` are missing from this skip list. Since `permitAll()` paths have no JWT principal on the exchange, the filter's `switchIfEmpty` branch fires and returns 401.

4. **No profile-aware CORS configuration**: The CORS configuration is entirely in Java code with no externalization. There is no `@ConfigurationProperties` or `@Value` injection that would allow profile-specific YAML to override origins per environment.

## Correctness Properties

Property 1: Bug Condition — Production CORS Origins Accepted

_For any_ request where the `Origin` header matches a configured allowed-origin pattern (e.g. `https://vibhanshu-ai-portfolio.dev` or `https://*.vibhanshu-ai-portfolio.dev`), the fixed `SecurityConfig` SHALL return proper CORS headers (`Access-Control-Allow-Origin` matching the request origin, `Access-Control-Allow-Credentials: true`) in the response.

**Validates: Requirements 2.1, 2.2, 2.3, 2.4**

Property 2: Bug Condition — Auth Endpoints Skip JWT Filter

_For any_ request where the path starts with `/api/auth/` and no JWT is present, the fixed `JwtAuthenticationFilter` SHALL skip JWT principal extraction, strip any caller-supplied `X-User-Id` header, and pass the request through to the downstream filter chain without returning 401.

**Validates: Requirements 2.5, 2.6**

Property 3: Preservation — Localhost CORS Origins Still Accepted

_For any_ request where the `Origin` header is `http://localhost:3000` or `http://127.0.0.1:3000` in the local profile, the fixed code SHALL produce the same CORS response headers as the original code, preserving local development access.

**Validates: Requirements 3.1, 3.2, 3.8**

Property 4: Preservation — Disallowed Origins Still Rejected

_For any_ request where the `Origin` header does NOT match any configured allowed-origin pattern, the fixed code SHALL produce the same rejection behavior as the original code (no `Access-Control-Allow-Origin` header in the response).

**Validates: Requirements 3.3**

Property 5: Preservation — Authenticated Endpoint Behavior Unchanged

_For any_ request to a protected endpoint (`/api/portfolio/**`, `/api/market/**`, etc.) with a valid JWT, the fixed `JwtAuthenticationFilter` SHALL continue to extract the `sub` claim and inject the `X-User-Id` header exactly as the original code does. For requests without a valid JWT, the filter SHALL continue to return 401.

**Validates: Requirements 3.4, 3.5, 3.6, 3.7**

## Fix Implementation

### Changes Required

Assuming our root cause analysis is correct:

**File**: `api-gateway/src/main/java/com/wealth/gateway/SecurityConfig.java`

**Specific Changes**:

1. **Inject CORS patterns from configuration**: Add a constructor parameter or `@Value` injection for `app.cors.allowed-origin-patterns` (a `List<String>`) so origins are read from profile-specific YAML instead of hard-coded.
2. **Switch to `setAllowedOriginPatterns`**: Replace `config.setAllowedOrigins(...)` with `config.setAllowedOriginPatterns(...)` to support glob patterns and `allowCredentials=true` simultaneously.
3. **Remove hard-coded origins**: The hard-coded `List.of("http://localhost:3000", "http://127.0.0.1:3000")` moves to `application-local.yml` (and `application.yml` as a sensible default for local dev).

**File**: `api-gateway/src/main/java/com/wealth/gateway/JwtAuthenticationFilter.java`

**Function**: `filter(ServerWebExchange, GatewayFilterChain)`

**Specific Changes**: 4. **Add `/api/auth/` to the skip-path check**: Extend the existing `if` condition to include `path.startsWith("/api/auth/")`. This path MUST go through the same header-stripping branch as `/actuator` and `/api/portfolio/health` — the `X-User-Id` header is stripped before passing through. This is the critical security guardrail: a malicious actor must not be able to inject a spoofed `X-User-Id` on a public auth endpoint that might be forwarded internally.

**File**: `api-gateway/src/main/resources/application.yml`

**Specific Changes**: 5. **Add default CORS patterns**: Add `app.cors.allowed-origin-patterns` with localhost defaults so the application starts without requiring profile-specific overrides:

```yaml
app:
  cors:
    allowed-origin-patterns:
      - "http://localhost:3000"
      - "http://127.0.0.1:3000"
```

**File**: `api-gateway/src/main/resources/application-prod.yml`

**Specific Changes**: 6. **Add production CORS patterns**: Add `app.cors.allowed-origin-patterns` with production origins:

```yaml
app:
  cors:
    allowed-origin-patterns:
      - "https://vibhanshu-ai-portfolio.dev"
      - "https://*.vibhanshu-ai-portfolio.dev"
```

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate the bug on unfixed code, then verify the fix works correctly and preserves existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bug BEFORE implementing the fix. Confirm or refute the root cause analysis. If we refute, we will need to re-hypothesize.

**Test Plan**: Write integration tests that send requests with production origins and to `/api/auth/**` paths without JWTs. Run these tests on the UNFIXED code to observe failures and confirm the root cause.

**Test Cases**:

1. **Production Origin CORS Preflight**: Send `OPTIONS /api/portfolio` with `Origin: https://vibhanshu-ai-portfolio.dev` — assert `Access-Control-Allow-Origin` header is present (will fail on unfixed code because origin is not in the hard-coded list)
2. **Subdomain Origin CORS Preflight**: Send `OPTIONS /api/portfolio` with `Origin: https://app.vibhanshu-ai-portfolio.dev` — assert CORS headers (will fail on unfixed code)
3. **Auth Login Without JWT**: Send `POST /api/auth/login` without `Authorization` header — assert response is NOT 401 (will fail on unfixed code because JWT filter rejects it)
4. **Auth Register Without JWT**: Send `POST /api/auth/register` without `Authorization` header — assert response is NOT 401 (will fail on unfixed code)
5. **Auth Path X-User-Id Stripping**: Send `POST /api/auth/login` with a spoofed `X-User-Id: attacker` header and no JWT — assert the `X-User-Id` header is stripped (will fail on unfixed code because the request never reaches the stripping logic — it's rejected with 401 first)

**Expected Counterexamples**:

- CORS preflight returns no `Access-Control-Allow-Origin` for production origins
- `/api/auth/**` requests without JWT return 401 instead of passing through
- Root cause confirmed: hard-coded origins + incomplete skip list

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed functions produce the expected behavior.

**Pseudocode:**

```
FOR ALL input WHERE isBugCondition(input) DO
  IF input.type = CORS THEN
    response := sendRequest(input)
    ASSERT response.headers["Access-Control-Allow-Origin"] = input.origin
    ASSERT response.headers["Access-Control-Allow-Credentials"] = "true"
  ELSE IF input.type = AUTH_PATH THEN
    response := jwtFilter_fixed(input)
    ASSERT response.status != 401
    ASSERT response.headers["X-User-Id"] IS NOT PRESENT  // stripped
  END IF
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed functions produce the same result as the original functions.

**Pseudocode:**

```
FOR ALL input WHERE NOT isBugCondition(input) DO
  ASSERT securityConfig_original(input) = securityConfig_fixed(input)
  ASSERT jwtFilter_original(input) = jwtFilter_fixed(input)
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:

- It generates many test cases automatically across the input domain (various origins, paths, JWT states)
- It catches edge cases that manual unit tests might miss (e.g. unusual origin formats, boundary paths)
- It provides strong guarantees that behavior is unchanged for all non-buggy inputs

**Test Plan**: Observe behavior on UNFIXED code first for localhost origins, authenticated endpoints, and other non-bug inputs, then write property-based tests capturing that behavior.

**Test Cases**:

1. **Localhost CORS Preservation**: Verify `http://localhost:3000` and `http://127.0.0.1:3000` continue to receive valid CORS headers after the fix
2. **Disallowed Origin Preservation**: Verify origins like `https://evil.com` continue to be rejected with no CORS headers
3. **Authenticated Endpoint Preservation**: Verify `/api/portfolio/**` with valid JWT continues to pass through with `X-User-Id` injected
4. **Unauthenticated Rejection Preservation**: Verify `/api/portfolio/**` without JWT continues to return 401
5. **Actuator Skip Preservation**: Verify `/actuator/health` continues to skip JWT processing
6. **X-User-Id Stripping Preservation**: Verify spoofed `X-User-Id` headers are stripped on all paths (public and authenticated)
7. **Credentials Header Preservation**: Verify `Access-Control-Allow-Credentials: true` is present in all CORS responses

### Unit Tests

- Test that `SecurityConfig` reads `app.cors.allowed-origin-patterns` from configuration and applies them via `setAllowedOriginPatterns`
- Test that `JwtAuthenticationFilter` skips JWT processing for `/api/auth/login`, `/api/auth/register`, and any `/api/auth/**` sub-path
- Test that `JwtAuthenticationFilter` strips `X-User-Id` on `/api/auth/**` paths (security guardrail)
- Test edge cases: `/api/auth` without trailing slash, `/api/authentication` (should NOT match), `/api/auth/` with trailing slash

### Reviewer-Identified Edge Cases

The following edge cases were identified during technical review and must be addressed:

1. **Trailing Slash Edge Case**: A request to exactly `/api/auth` (no trailing slash) must also be skipped by the JWT filter. The implementation must use `path.equals("/api/auth") || path.startsWith("/api/auth/")` to cover both forms. The negative case `/api/authentication` must NOT match.
2. **Property Injection Fallback**: The `@Value` annotation for `app.cors.allowed-origin-patterns` must include an inline default (`http://localhost:3000,http://127.0.0.1:3000`) to prevent startup crash if the property is temporarily missing from YAML (e.g. detached config map in Kubernetes).
3. **WebFlux Reactive CORS Plumbing**: The existing `SecurityConfig` already uses the reactive `CorsConfigurationSource` interface (`UrlBasedCorsConfigurationSource` from `org.springframework.web.cors.reactive`). Switching from `setAllowedOrigins` to `setAllowedOriginPatterns` should be transparent in the reactive stack, but integration tests must verify this explicitly.

### Property-Based Tests

- Generate random origins from a mix of allowed patterns and disallowed domains; verify CORS headers are returned only for matching origins
- Generate random paths across `/api/auth/**`, `/api/portfolio/**`, `/actuator/**` with and without JWTs; verify the JWT filter behaves correctly for each category
- Generate random `X-User-Id` header values across all path categories; verify the header is always stripped before forwarding

### Integration Tests

- Full Spring context test: production origin CORS preflight returns correct headers
- Full Spring context test: `/api/auth/login` without JWT passes through JWT filter (non-401 response)
- Full Spring context test: `/api/auth/login` with spoofed `X-User-Id` has the header stripped
- Full Spring context test: `/api/portfolio` with valid JWT still works (preservation)
- Full Spring context test: `/api/portfolio` without JWT still returns 401 (preservation)
