# Bugfix Requirements Document

## Introduction

The Wealth Management & Portfolio Tracker app deployed at `vibhanshu-ai-portfolio.dev` has three interrelated code-level bugs in the `api-gateway` module that prevent users from logging in on production. The primary symptom is a 403 on `POST /api/auth/login` caused by CORS rejecting the production origin. A latent 401 from the JWT filter blocking `permitAll` auth endpoints is masked by the CORS failure and will surface once CORS is fixed. The root cause is that CORS origins are hard-coded to localhost values with no production override, and the JWT filter's skip list is incomplete.

This spec covers only the three code-level fixes in `api-gateway`. Infrastructure items (Terraform import, pipeline branch conditions, CloudFront Function rewrites, secret alignment) are out of scope.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN a request arrives from the production origin `https://vibhanshu-ai-portfolio.dev` THEN the system returns HTTP 403 because `SecurityConfig.corsConfigurationSource()` hard-codes `setAllowedOrigins` to `http://localhost:3000` and `http://127.0.0.1:3000` only, and the production origin is not in the allow-list

1.2 WHEN a request arrives from any subdomain origin such as `https://*.vibhanshu-ai-portfolio.dev` THEN the system returns HTTP 403 because no wildcard or pattern-based origin matching is configured

1.3 WHEN `allowCredentials` is `true` and a wildcard origin pattern is needed THEN the system cannot support it because `setAllowedOrigins` does not allow wildcard patterns when credentials are enabled — `setAllowedOriginPatterns` is required but not used

1.4 WHEN `application-prod.yml` is active THEN the system inherits the hard-coded localhost-only CORS allow-list because no `app.cors.allowed-origin-patterns` property exists in the production profile configuration

1.5 WHEN a request hits `POST /api/auth/login` (or any `/api/auth/**` path) and CORS is fixed THEN the `JwtAuthenticationFilter` returns HTTP 401 because its skip list only includes `/actuator/**` and `/api/portfolio/health`, not `/api/auth/**`, and the `switchIfEmpty` branch rejects the request since `permitAll` paths have no JWT principal

1.6 WHEN a request hits `POST /api/auth/register` or any other `/api/auth/**` endpoint and CORS is fixed THEN the `JwtAuthenticationFilter` returns HTTP 401 for the same reason as 1.5

### Expected Behavior (Correct)

2.1 WHEN a request arrives from the production origin `https://vibhanshu-ai-portfolio.dev` THEN the system SHALL accept the origin and return proper CORS headers (`Access-Control-Allow-Origin`, `Access-Control-Allow-Credentials`) in the response

2.2 WHEN a request arrives from a subdomain origin matching `https://*.vibhanshu-ai-portfolio.dev` THEN the system SHALL accept the origin and return proper CORS headers in the response

2.3 WHEN `allowCredentials` is `true` THEN the system SHALL use `setAllowedOriginPatterns` instead of `setAllowedOrigins` so that both exact origins and wildcard patterns are supported

2.4 WHEN `application-prod.yml` is active THEN the system SHALL read CORS allowed-origin patterns from the externalized property `app.cors.allowed-origin-patterns` containing at least `https://vibhanshu-ai-portfolio.dev` and `https://*.vibhanshu-ai-portfolio.dev`

2.5 WHEN a request hits any `/api/auth/**` path (login, register, etc.) THEN the `JwtAuthenticationFilter` SHALL skip JWT principal extraction and pass the request through to the downstream filter chain, consistent with the `permitAll()` declaration in `SecurityConfig`

2.6 WHEN a request hits any `/api/auth/**` path THEN the `JwtAuthenticationFilter` SHALL still strip any caller-supplied `X-User-Id` header to prevent spoofing on public endpoints

### Unchanged Behavior (Regression Prevention)

3.1 WHEN a request arrives from `http://localhost:3000` in the local profile THEN the system SHALL CONTINUE TO accept the origin and return proper CORS headers

3.2 WHEN a request arrives from `http://127.0.0.1:3000` in the local profile THEN the system SHALL CONTINUE TO accept the origin and return proper CORS headers

3.3 WHEN a request arrives from an origin not in the allowed list (e.g. `https://evil.com`) THEN the system SHALL CONTINUE TO reject the request with no `Access-Control-Allow-Origin` header

3.4 WHEN a request hits `/api/portfolio/**` or `/api/market/**` or any other authenticated endpoint with a valid JWT THEN the `JwtAuthenticationFilter` SHALL CONTINUE TO extract the `sub` claim and inject the `X-User-Id` header

3.5 WHEN a request hits an authenticated endpoint without a JWT or with an invalid JWT THEN the system SHALL CONTINUE TO return HTTP 401

3.6 WHEN a request hits `/actuator/**` or `/api/portfolio/health` THEN the `JwtAuthenticationFilter` SHALL CONTINUE TO skip JWT processing and pass the request through

3.7 WHEN a request includes a spoofed `X-User-Id` header on any endpoint (public or authenticated) THEN the `JwtAuthenticationFilter` SHALL CONTINUE TO strip the spoofed header before forwarding

3.8 WHEN `allowCredentials` is `true` in the CORS configuration THEN the system SHALL CONTINUE TO include `Access-Control-Allow-Credentials: true` in CORS responses

3.9 WHEN the `CLOUDFRONT_ORIGIN_SECRET` environment variable is set THEN the `CloudFrontOriginVerifyFilter` SHALL CONTINUE TO reject requests missing or mismatching the `X-Origin-Verify` header with HTTP 403
