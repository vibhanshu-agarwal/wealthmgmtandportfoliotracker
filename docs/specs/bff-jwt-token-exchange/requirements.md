# Requirements Document

## Introduction

This feature centralizes HS256 JWT token minting into a single BFF (Backend-For-Frontend) token exchange path within the Next.js frontend. Today, JWT minting logic is duplicated between the BFF route handler (`/api/auth/jwt`) and the server-side fetch helper (`fetchWithAuth.server.ts`). This duplication creates a maintenance burden and risks divergence in token claims, expiry, or signing behavior.

The centralized design establishes the BFF route as the sole Token Issuer. Server-side code that needs an HS256 JWT calls the shared minting function rather than reimplementing signing inline. Client-side code continues to obtain tokens via the BFF HTTP endpoint. Session cookies never leak beyond the Next.js boundary, and backend microservices remain stateless — they trust the `Authorization: Bearer` JWT validated by the API Gateway.

## Glossary

- **BFF**: Backend-For-Frontend — the Next.js server layer that acts as an intermediary between browser clients and backend microservices
- **Token_Issuer**: The centralized module within the BFF responsible for minting HS256 JWTs from authenticated Better Auth sessions
- **Token_Exchange_Endpoint**: The Next.js API route (`GET /api/auth/jwt`) that exposes the Token Issuer to client-side code over HTTP
- **JWT**: JSON Web Token — a compact, URL-safe token format used to convey claims between parties
- **HS256**: HMAC-SHA256 — the symmetric signing algorithm used for all BFF-minted JWTs
- **AUTH_JWT_SECRET**: The shared symmetric secret used by both the Token Issuer and the API Gateway to sign and verify JWTs
- **Better_Auth_Session**: The server-side session managed by Better Auth, identified by an opaque session cookie — not a JWT
- **API_Gateway**: The Spring Cloud Gateway service that validates HS256 JWTs and injects `X-User-Id` headers before routing to backend microservices
- **Server_Fetch_Helper**: The `fetchWithAuth.server.ts` module used by Server Components, Server Actions, and Route Handlers to make authenticated requests to backend services
- **Client_Fetch_Helper**: The `fetchWithAuth.ts` module used by Client Components (via TanStack Query) to make authenticated requests
- **Gateway_JWT_Hook**: The `useAuthenticatedUserId` TanStack Query hook that calls the Token Exchange Endpoint and caches the JWT client-side

## Requirements

### Requirement 1: Centralized Token Minting Function

**User Story:** As a developer, I want a single shared function that mints HS256 JWTs from Better Auth sessions, so that token creation logic is defined in exactly one place and cannot diverge.

#### Acceptance Criteria

1. THE Token_Issuer SHALL expose a single `mintToken` function that accepts a Better_Auth_Session and returns a signed HS256 JWT string
2. THE Token_Issuer SHALL sign every JWT using the AUTH_JWT_SECRET environment variable
3. WHEN AUTH_JWT_SECRET is not set, THE Token_Issuer SHALL fall back to the BETTER_AUTH_SECRET environment variable
4. THE Token_Issuer SHALL include the `sub`, `email`, and `name` claims in every minted JWT, sourced from the Better_Auth_Session user object
5. THE Token_Issuer SHALL set the `iat` (issued-at) claim to the current time on every minted JWT
6. THE Token_Issuer SHALL set the `exp` (expiration) claim to 1 hour after the issued-at time on every minted JWT
7. THE Token_Issuer SHALL set the JWT protected header algorithm to `HS256`

### Requirement 2: BFF Token Exchange Endpoint

**User Story:** As a frontend client component, I want to call a single HTTP endpoint to exchange my session cookie for an HS256 JWT, so that I can make authenticated requests to backend services through the API Gateway.

#### Acceptance Criteria

1. WHEN a GET request is received at `/api/auth/jwt`, THE Token_Exchange_Endpoint SHALL read the Better_Auth_Session from the request cookies
2. WHEN the Better_Auth_Session is valid and contains a user ID, THE Token_Exchange_Endpoint SHALL delegate to the Token_Issuer `mintToken` function to produce the JWT
3. WHEN the Better_Auth_Session is valid, THE Token_Exchange_Endpoint SHALL return a JSON response containing the `token`, `userId`, and `email` fields with HTTP status 200
4. IF the Better_Auth_Session is missing or does not contain a user ID, THEN THE Token_Exchange_Endpoint SHALL return a JSON error response with HTTP status 401
5. THE Token_Exchange_Endpoint SHALL NOT contain any inline JWT signing logic — all signing is delegated to the Token_Issuer

### Requirement 3: Server-Side Fetch Helper Integration

**User Story:** As a developer writing Server Components and Server Actions, I want the server-side fetch helper to obtain JWTs from the centralized Token Issuer, so that I do not need to duplicate signing logic in every server-side module.

#### Acceptance Criteria

1. THE Server_Fetch_Helper SHALL obtain the Better_Auth_Session from the incoming request headers
2. WHEN the Better_Auth_Session contains a valid user ID, THE Server_Fetch_Helper SHALL call the Token_Issuer `mintToken` function to obtain an HS256 JWT
3. THE Server_Fetch_Helper SHALL attach the JWT as an `Authorization: Bearer` header on outgoing requests to backend services
4. IF the Better_Auth_Session is missing or does not contain a user ID, THEN THE Server_Fetch_Helper SHALL send the outgoing request without an Authorization header
5. THE Server_Fetch_Helper SHALL NOT contain any inline JWT signing logic — all signing is delegated to the Token_Issuer
6. THE Server_Fetch_Helper SHALL NOT import the `jose` library directly — the `SignJWT` dependency is encapsulated within the Token_Issuer

### Requirement 4: Client-Side Token Flow

**User Story:** As a frontend developer building client components, I want the client-side fetch helper and TanStack Query hook to work together seamlessly with the BFF token exchange, so that client components can make authenticated API calls without managing tokens manually.

#### Acceptance Criteria

1. THE Gateway_JWT_Hook SHALL call the Token_Exchange_Endpoint via HTTP GET with credentials included
2. WHEN the Token_Exchange_Endpoint returns a 401 status, THE Gateway_JWT_Hook SHALL treat the user as unauthenticated and return a status of `unauthenticated`
3. WHEN the Token_Exchange_Endpoint returns a successful response, THE Gateway_JWT_Hook SHALL cache the JWT with a stale time shorter than the JWT expiration period
4. THE Gateway_JWT_Hook SHALL automatically refetch the JWT before the cached token expires
5. THE Client_Fetch_Helper SHALL accept a raw JWT string and attach it as an `Authorization: Bearer` header on outgoing requests

### Requirement 5: Secret Management and Security

**User Story:** As a platform operator, I want the JWT signing secret to be managed securely and consistently across the BFF and API Gateway, so that tokens minted by the BFF are always accepted by the Gateway.

#### Acceptance Criteria

1. THE Token_Issuer SHALL read the signing secret from the AUTH_JWT_SECRET environment variable at invocation time, not at module load time
2. THE Token_Issuer SHALL encode the secret as UTF-8 bytes before passing it to the HS256 signing function
3. THE API_Gateway SHALL validate incoming JWTs using the same AUTH_JWT_SECRET value configured in its `auth.jwt.secret` property
4. THE BFF SHALL NOT expose the AUTH_JWT_SECRET value in any HTTP response, log output, or client-accessible bundle
5. THE BFF SHALL NOT include session cookies in outgoing requests to backend microservices — only the minted JWT is forwarded

### Requirement 6: Domain Boundary Enforcement

**User Story:** As a security engineer, I want session cookies to remain within the Next.js BFF boundary and never leak to backend microservices, so that backend services remain stateless and do not depend on frontend session infrastructure.

#### Acceptance Criteria

1. THE Server_Fetch_Helper SHALL NOT forward any `Cookie` headers from the original browser request to backend microservices
2. THE Server_Fetch_Helper SHALL only attach headers explicitly constructed for the outgoing request (Content-Type, Authorization)
3. THE API_Gateway SHALL strip any caller-supplied `X-User-Id` header before processing, to prevent spoofing
4. WHEN the API_Gateway validates a JWT successfully, THE API_Gateway SHALL inject the `X-User-Id` header from the JWT `sub` claim before routing to downstream services

### Requirement 7: Token Minting Round-Trip Integrity

**User Story:** As a developer, I want confidence that tokens minted by the Token Issuer are structurally valid and verifiable, so that the API Gateway always accepts BFF-minted tokens.

#### Acceptance Criteria

1. FOR ALL valid Better_Auth_Sessions, minting a JWT with the Token_Issuer and then verifying the JWT with the same AUTH_JWT_SECRET SHALL succeed without error (round-trip property)
2. FOR ALL minted JWTs, the decoded `sub` claim SHALL equal the user ID from the original Better_Auth_Session (claim preservation property)
3. FOR ALL minted JWTs, the decoded `email` claim SHALL equal the email from the original Better_Auth_Session (claim preservation property)
4. FOR ALL minted JWTs, the decoded `exp` claim SHALL be exactly 3600 seconds after the `iat` claim (expiry invariant)
5. FOR ALL minted JWTs, the protected header `alg` field SHALL equal `HS256`
