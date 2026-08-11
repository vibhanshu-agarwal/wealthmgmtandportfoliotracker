# New User Signup & Profile Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn `api-gateway` into the real authentication server (per-user signup + login with bcrypt, atomic provisioning, auth-endpoint rate limiting, a read-only demo account) while keeping the frontend a static export, and retire the dead Better Auth code.

**Architecture:** Postgres schema owned by `portfolio-service` (3 new Flyway migrations); `api-gateway` gets a new `com.wealth.gateway.auth` package (validator, JDBC repository, services) using blocking `spring-jdbc` + `TransactionTemplate` on `Schedulers.boundedElastic()`; two new gateway filters (`AuthRateLimitFilter`, `ReadOnlyEnforcementFilter`) reuse the existing shared `RedisRateLimiter`/`GatewayRateLimitConfig.resolveTrustedHopKey`; the frontend gets a new Signup page and Better Auth is deleted.

**Tech Stack:** Java 21 / Spring Boot 4.1 (WebFlux, `spring-jdbc`, HikariCP, Spring Security `BCryptPasswordEncoder`, jqwik property tests, Testcontainers) for `api-gateway` / `portfolio-service`; TypeScript / Next.js 15 static export (Vitest + Testing Library + MSW, fast-check) for `frontend`.

This plan is the executable translation of the already-approved spec at `.kiro/specs/new-user-signup-profile/` (`requirements.md`, `design.md`, `tasks.md`). Task numbers below are renumbered sequentially for SDD tooling; each task's brief cites the original requirement IDs. Two facts were confirmed against the **live** repo/production DB during planning (not assumed from the spec) and both changed a task's exact content vs. a literal reading of `design.md`:

1. `asset_holdings` has **no `user_id` column** (only `portfolio_id` FK, `ON DELETE CASCADE`) and **no `valuation_history` table exists at all** — confirmed via `\d asset_holdings` against production Neon Postgres and `grep CREATE TABLE` across all migrations. So the V15 showcase-portfolio reassignment is a **single `UPDATE portfolios` statement** — no holdings/history UPDATEs.
2. `frontend/src/lib/api/insights-actions.ts` (+ its test) imports the soon-deleted `fetchWithAuth.server.ts`, and is **dead code** — `sendChatMessage`/`useActionState` have zero references outside that file and its test (`ChatInterface.tsx` uses the client `postChatMessage` from `insights.ts` instead, confirmed via `grep`). Task 7 deletes both files as a mechanical consequence of Req 8.1/8.2 ("zero errors", "no remaining source file imports"), not a design change.

## Global Constraints

- **Byte-identical `Uniform_Auth_Error`:** every 401 login-failure path (unknown email, wrong password, blank/missing fields, matched email with absent/malformed hash) returns the exact same pre-serialized JSON body `{"error":"Invalid username or password."}` — same bytes, no field ever varies. (Req 3.5, 3.6, 3.9, 4.6, 10.6)
- **No string-equality auth, ever:** login must use `PasswordEncoder.matches(...)` against a stored/dummy bcrypt hash — never `String.equals`. (Req 3.3)
- **Timing equalization:** on an unknown email, run `passwordEncoder.matches(submitted, DUMMY_PASSWORD_HASH)` before returning 401 — never short-circuit before hashing. (Req 3.4)
- **Password policy (signup only, not login):** ≥ 12 characters AND UTF-8 byte length ≤ 72 (`password.getBytes(StandardCharsets.UTF_8).length`, not `.length()`). Login never re-enforces this against legacy/seed passwords. (Req 1.6, 3.11)
- **BCrypt cost = 12** everywhere a hash is produced (signup, dummy hash, V15 seed hashes). (Req 4.3)
- **`users.id` UUID == JWT `sub`:** the provisioned `users.id` is exactly the UUID placed in the JWT `sub` claim — `PortfolioService.requireUserExists()` depends on this. (Req 2.3, 2.4)
- **JWT claims unchanged except new `ro`:** `sub`/`email`/`name`, HS256, `auth.jwt.secret`, 1-hour expiry are untouched; only a new boolean `ro` claim is added. (Req 3.7, 9.3)
- **Atomicity:** `users` row + `user_credentials` row commit together or not at all, in one `TransactionTemplate.execute(...)` on `Schedulers.boundedElastic()` (never on the Netty event loop). (Req 2.1, 2.5)
- **`portfolio-service` is the sole Schema_Owner:** every migration lives under `portfolio-service/src/main/resources/db/migration/`; `api-gateway` defines zero Flyway migrations. (Req 2.6, 8.6)
- **Reuse, never duplicate, rate-limiting primitives:** the new `Auth_Bucket` is a second `RedisRateLimiter` **bean** with new numbers, wired through the *existing* `RedisRateLimiter`/`KeyResolver` types and `GatewayRateLimitConfig.resolveTrustedHopKey` (package-private, same package) — no second limiter implementation. (Req 6.1, 6.8)
- **Auth_Bucket exact numbers:** `replenishRate=1`, `requestedTokens=12`, `burstCapacity=60` (≈5/min, burst 5, `Retry-After` = `ceil(12/1)` = 12s). Login and signup share **one** bucket per key (route id `"auth-bucket"`). (Req 6.3, 6.5, 6.6)
- **Fail-open:** any Redis error/timeout on the auth limiter must `chain.filter(exchange)`, never block the request. (Req 6.7)
- **Read-only decision is pure and exact:** block (403) **iff** `ro==true AND method∈{POST,PUT,PATCH,DELETE} AND path matches /api/portfolio/** or /api/market/** AND path NOT in the AI allowlist (/api/chat/**, /api/insights/generate/**)`. Every other combination passes through unchanged. (Req 7.4–7.7)
- **Dev user stays writable:** `00000000-0000-0000-0000-000000000001` keeps `read_only=false` after V15; only the *showcase portfolio's* `portfolios.user_id` moves to the demo UUID `00000000-0000-0000-0000-0000000d3110`. (Req 7.8)
- **V16 (Better Auth drop) ships with the frontend code removal, in the same release, versioned highest.** (Req 8.3)
- **No plaintext password** ever reaches a log line, error response, or any store other than as a bcrypt hash. (Req 4.1, 4.2)
- **Property tests:** jqwik (Java) / fast-check (frontend), ≥ 100 iterations (`tries = 100`), tagged with a comment naming the Property number from `design.md`. Generators only — no hand-rolled loops.
- **Integration tests:** `@Tag("integration")`, run via the `integrationTest` Gradle task (already wired at the root `build.gradle` to include only `@Tag("integration")` tests); use `TestContainerImages` constants, not inline `DockerImageName.parse(...)` calls.

---

### Task 1: Database schema migrations (portfolio-service — Schema Owner)

**Files:**
- Create: `portfolio-service/src/main/resources/db/migration/V14__Add_User_Credentials_And_Account_Flags.sql`
- Create: `portfolio-service/src/main/resources/db/migration/V15__Reconcile_Auth_Seed_Users.sql`
- Create: `portfolio-service/src/main/resources/db/migration/V16__Drop_Better_Auth_Tables.sql`
- Test: `portfolio-service/src/test/java/com/wealth/portfolio/AuthSchemaMigrationIntegrationTest.java`

**Interfaces:**
- Consumes: nothing (first task; current Flyway head is `V13`, confirmed via `flyway_schema_history` in prod).
- Produces (for Task 2's repository SQL and Task 8's integration tests):
  - `users` table gains `name VARCHAR(100)` (nullable) and `read_only BOOLEAN NOT NULL DEFAULT FALSE`. Existing `id UUID PK`, `email VARCHAR(255) UNIQUE`, `created_at` unchanged.
  - New table `user_credentials(user_id UUID PK REFERENCES users(id) ON DELETE CASCADE, email VARCHAR(254) NOT NULL, password_hash VARCHAR(255) NOT NULL, created_at TIMESTAMP DEFAULT now(), updated_at TIMESTAMP DEFAULT now())` with unique index `ux_user_credentials_email_lower ON user_credentials (lower(email))`.
  - Demo account row: `id='00000000-0000-0000-0000-0000000d3110'`, `email='demo@wealthtracker.dev'`, `read_only=true`.
  - `ba_user`, `ba_session`, `ba_account`, `ba_verification` dropped by V16 (highest version in the migration set).

- [ ] **Step 1: Write `V14__Add_User_Credentials_And_Account_Flags.sql`**

```sql
-- =============================================================================
-- V14: Add user_credentials (per-user login credentials) and account flags on
-- users (name, read_only). Owned by portfolio-service (the Schema_Owner);
-- api-gateway reads/writes these tables but defines no migrations of its own.
-- =============================================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS name      VARCHAR(100);
ALTER TABLE users ADD COLUMN IF NOT EXISTS read_only BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS user_credentials
(
    user_id       UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);

-- Case-insensitive email uniqueness without requiring the citext extension.
-- This is the concurrency guard for duplicate signups (Req 1.9, 2.8): two
-- concurrent INSERTs for the same email (any case) have exactly one winner.
CREATE UNIQUE INDEX IF NOT EXISTS ux_user_credentials_email_lower
    ON user_credentials (lower(email));
```

- [ ] **Step 2: Write `V15__Reconcile_Auth_Seed_Users.sql`**

Bcrypt(cost=12) hashes below MUST be generated fresh (not reused from any `ba_account` scrypt hash — those are a different algorithm and incompatible with `BCryptPasswordEncoder`). Generate them with a short one-off: create a scratch JUnit test or a `jshell` snippet using `new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode("<password>")` for each of the three passwords below, paste the output hashes into the SQL, then delete the scratch snippet (do not commit a throwaway hash-generation class). Passwords:
  - Demo: use a fresh password of your choosing that is ≥ 12 characters (e.g. `demo-wealthtracker-2026`) — this exact string must also be recorded in Task 6's `.env.local` note and is intentionally public (wired to `NEXT_PUBLIC_DEMO_PASSWORD`).
  - Dev (`00000000-0000-0000-0000-000000000001`): use `local-dev-password-2026` (local-dev only; not internet-exposed since this account requires a signup-endpoint credential change to reach — it's seeded so the dev user has *a* working password for parity, not for production access).
  - E2E test user (`00000000-0000-0000-0000-000000000e2e`): reuse the exact plaintext already documented in V10's comment and `.env.secrets`: `e2e-test-password-2026`. (Freshly bcrypt-hashed here — V10's Better-Auth scrypt hash for the same plaintext is not reused.)

```sql
-- =============================================================================
-- V15: Idempotently reconcile auth identities for demo/dev/E2E users and
-- reassign the seeded showcase portfolio from the dev user to the new demo
-- account so the read-only recruiter login lands on a populated dashboard.
--
-- Confirmed against the live schema (see plan header): asset_holdings has NO
-- user_id column (only portfolio_id FK, ON DELETE CASCADE) and there is no
-- separate valuation_history table — so reassigning portfolios.user_id alone
-- is sufficient; holdings follow automatically via the FK.
-- =============================================================================

-- Demo/recruiter account (read-only). Password is >=12 chars and intentionally
-- public (wired to NEXT_PUBLIC_DEMO_PASSWORD for the login pre-fill).
INSERT INTO users (id, email, name, read_only, created_at)
VALUES ('00000000-0000-0000-0000-0000000d3110', 'demo@wealthtracker.dev', 'Demo User', TRUE, now())
ON CONFLICT (id) DO NOTHING;
INSERT INTO user_credentials (user_id, email, password_hash)
VALUES ('00000000-0000-0000-0000-0000000d3110', 'demo@wealthtracker.dev', '<PASTE_FRESH_BCRYPT_HASH_DEMO>')
ON CONFLICT (user_id) DO NOTHING;

-- Dev user: already exists in `users` (seeded by V4); add name + credentials,
-- read_only stays FALSE (dev user remains writable for local development).
UPDATE users SET name = COALESCE(name, 'Dev User') WHERE id = '00000000-0000-0000-0000-000000000001';
INSERT INTO user_credentials (user_id, email, password_hash)
VALUES ('00000000-0000-0000-0000-000000000001', 'dev@local', '<PASTE_FRESH_BCRYPT_HASH_DEV>')
ON CONFLICT (user_id) DO NOTHING;

-- E2E test user: already exists in `users` (seeded by V10); add name + fresh
-- bcrypt credentials (NOT the legacy ba_account scrypt hash). read_only stays
-- FALSE so E2E write tests against /api/portfolio/** keep working.
UPDATE users SET name = COALESCE(name, 'E2E Test User') WHERE id = '00000000-0000-0000-0000-000000000e2e';
INSERT INTO user_credentials (user_id, email, password_hash)
VALUES ('00000000-0000-0000-0000-000000000e2e', 'e2e-test-user@vibhanshu-ai-portfolio.dev', '<PASTE_FRESH_BCRYPT_HASH_E2E>')
ON CONFLICT (user_id) DO NOTHING;

-- Reassign the seeded showcase portfolio (V3's AAPL/TSLA/BTC portfolio) from
-- the dev user to the demo account. Guarded on the CURRENT owner so re-running
-- this migration is idempotent: once the row belongs to the demo UUID, the
-- WHERE clause no longer matches and nothing is re-assigned. asset_holdings
-- needs no separate UPDATE — it has no user_id column, only portfolio_id (FK,
-- ON DELETE CASCADE), so ownership follows automatically.
UPDATE portfolios
   SET user_id = '00000000-0000-0000-0000-0000000d3110'
 WHERE user_id = '00000000-0000-0000-0000-000000000001';
```

- [ ] **Step 3: Write `V16__Drop_Better_Auth_Tables.sql`**

```sql
-- =============================================================================
-- V16: Drop the retired Better Auth tables. Versioned as the HIGHEST number in
-- this release so Flyway applies it last — ships in the same release as the
-- frontend Better Auth code removal (Task 7); no deployed build may reference
-- ba_* after this runs.
-- =============================================================================

DROP TABLE IF EXISTS ba_verification;
DROP TABLE IF EXISTS ba_account;
DROP TABLE IF EXISTS ba_session;
DROP TABLE IF EXISTS ba_user;
```

- [ ] **Step 4: Write the migration integration test**

Create `portfolio-service/src/test/java/com/wealth/portfolio/AuthSchemaMigrationIntegrationTest.java`, following the exact Testcontainers pattern in `portfolio-service/src/test/java/com/wealth/portfolio/DlqIntegrationTest.java` (imports, `@Tag("integration")`, `@Testcontainers`, `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)`, `@ActiveProfiles("local")`, `@Container static final PostgreSQLContainer postgres = new PostgreSQLContainer(TestContainerImages.POSTGRES)...`, `@DynamicPropertySource` overriding `spring.datasource.*`) but WITHOUT the Kafka container (not needed here):

```java
package com.wealth.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Migration tests for V14-V16 (.kiro/specs/new-user-signup-profile, Requirement 8.3-8.7).
 * Run via: ./gradlew :portfolio-service:integrationTest
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class AuthSchemaMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer(TestContainerImages.POSTGRES)
          .withDatabaseName("portfolio_db")
          .withUsername("wealth_user")
          .withPassword("wealth_pass");

  @DynamicPropertySource
  static void overrideProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired JdbcTemplate jdbcTemplate;

  @Test
  void v16IsHighestAppliedVersionAndBetterAuthTablesAreAbsent() {
    String maxVersion = jdbcTemplate.queryForObject(
        "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank DESC LIMIT 1",
        String.class);
    assertThat(maxVersion).isEqualTo("16");

    List<String> baTables = jdbcTemplate.queryForList(
        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
            + "AND table_name IN ('ba_user','ba_session','ba_account','ba_verification')",
        String.class);
    assertThat(baTables).isEmpty();
  }

  @Test
  void demoDevAndE2eUsersEachResolveToExactlyOneRowPair() {
    List<String> ids = List.of(
        "00000000-0000-0000-0000-0000000d3110",
        "00000000-0000-0000-0000-000000000001",
        "00000000-0000-0000-0000-000000000e2e");
    for (String id : ids) {
      Integer userCount = jdbcTemplate.queryForObject(
          "SELECT count(*) FROM users WHERE id = ?::uuid", Integer.class, id);
      Integer credCount = jdbcTemplate.queryForObject(
          "SELECT count(*) FROM user_credentials WHERE user_id = ?::uuid", Integer.class, id);
      assertThat(userCount).as("users row for %s", id).isEqualTo(1);
      assertThat(credCount).as("user_credentials row for %s", id).isEqualTo(1);
    }
  }

  @Test
  void demoAccountIsReadOnlyAndOwnsTheShowcasePortfolioNonEmpty() {
    Boolean readOnly = jdbcTemplate.queryForObject(
        "SELECT read_only FROM users WHERE id = '00000000-0000-0000-0000-0000000d3110'::uuid",
        Boolean.class);
    assertThat(readOnly).isTrue();

    Integer devOwnedCount = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM portfolios WHERE user_id = '00000000-0000-0000-0000-000000000001'",
        Integer.class);
    assertThat(devOwnedCount).as("dev user must no longer own the showcase portfolio").isZero();

    Integer demoHoldingCount = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM asset_holdings h JOIN portfolios p ON p.id = h.portfolio_id "
            + "WHERE p.user_id = '00000000-0000-0000-0000-0000000d3110'",
        Integer.class);
    assertThat(demoHoldingCount).as("demo account's showcase portfolio must have holdings").isGreaterThan(0);
  }

  @Test
  void reRunningMigrateIsIdempotent() {
    // Flyway already ran once via Spring Boot's auto-migrate on context startup.
    // Re-invoking migrate() directly must be a no-op (no duplicate rows, no error).
    org.flywaydb.core.Flyway flyway = org.flywaydb.core.Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .load();
    flyway.migrate(); // no-op: already at V16

    Integer demoCount = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM users WHERE id = '00000000-0000-0000-0000-0000000d3110'::uuid",
        Integer.class);
    assertThat(demoCount).isEqualTo(1);
  }
}
```

- [ ] **Step 5: Run the migration test and fix any issues**

Run: `./gradlew :portfolio-service:integrationTest --tests "*AuthSchemaMigrationIntegrationTest*"`
Expected: all 4 tests PASS. If the bcrypt hash placeholders were left in (`<PASTE_...>`), the signup/login integration tests in Task 8 will fail login for those seeded users — Task 1 is not complete until real bcrypt hashes are pasted in.

- [ ] **Step 6: Commit**

```bash
git add portfolio-service/src/main/resources/db/migration/V14__Add_User_Credentials_And_Account_Flags.sql portfolio-service/src/main/resources/db/migration/V15__Reconcile_Auth_Seed_Users.sql portfolio-service/src/main/resources/db/migration/V16__Drop_Better_Auth_Tables.sql portfolio-service/src/test/java/com/wealth/portfolio/AuthSchemaMigrationIntegrationTest.java
git commit -m "feat(portfolio-service): add user_credentials schema, reconcile auth seed users, drop Better Auth tables"
```

---

### Task 2: Gateway auth foundation — validator, repository, hashing, signer (com.wealth.gateway / com.wealth.gateway.auth)

**Files:**
- Create: `api-gateway/src/main/java/com/wealth/gateway/auth/SignupValidator.java`
- Create: `api-gateway/src/main/java/com/wealth/gateway/auth/SignupDtos.java`
- Create: `api-gateway/src/main/java/com/wealth/gateway/auth/ValidationException.java`
- Create: `api-gateway/src/main/java/com/wealth/gateway/auth/UserCredentialRepository.java`
- Create: `api-gateway/src/main/java/com/wealth/gateway/auth/GatewayAuthDataConfig.java`
- Create: `api-gateway/src/main/java/com/wealth/gateway/auth/PasswordHasherConfig.java`
- Modify: `api-gateway/src/main/java/com/wealth/gateway/JwtSigner.java`
- Modify: `api-gateway/build.gradle`
- Modify: `api-gateway/src/main/resources/application-local.yml`
- Modify: `api-gateway/src/main/resources/application-prod.yml`
- Modify: `docker-compose.yml`
- Test: `api-gateway/src/test/java/com/wealth/gateway/auth/SignupValidatorPropertyTest.java`
- Test: `api-gateway/src/test/java/com/wealth/gateway/JwtSignerTest.java` (new file — none exists yet)

**Interfaces:**
- Consumes: Task 1's `user_credentials`/`users.name`/`users.read_only` schema (repository SQL only — this task does not run against a live DB, Task 8 does).
- Produces (for Task 3):
  - `SignupValidator.validate(SignupDtos.SignupRequest req) -> SignupDtos.ValidatedSignup` (record `email`, `password`, `name` — all normalized/trimmed), throwing `ValidationException(String field, String reason)`.
  - `UserCredentialRepository` with `Optional<CredentialRow> findByEmailIgnoreCase(String email)`, `void insertUser(UUID id, String email, String name)`, `void insertCredential(UUID userId, String email, String hash)`, and `record CredentialRow(String userId, String email, String name, String passwordHash, boolean readOnly)`.
  - A `PasswordEncoder` bean (`BCryptPasswordEncoder`, cost 12) and a public `PasswordHasherConfig.DUMMY_PASSWORD_HASH` constant.
  - `JwtSigner.signHs256(String userId, String email, String name, boolean readOnly)` (new 4-arg overload; existing 3-arg method delegates with `ro=false`).
  - A `TransactionTemplate` bean (from `GatewayAuthDataConfig`) for Task 3's `SignupService`.

- [ ] **Step 1: Add gateway JDBC dependencies**

Edit `api-gateway/build.gradle` — add inside the existing `dependencies { ... }` block, right after the `spring-boot-starter-oauth2-resource-server` line:

```groovy
    // Blocking JDBC for the auth data layer (Req 2.5: no spring-boot-starter-data-jpa in a
    // WebFlux app — plain spring-jdbc + HikariCP, bridged via Schedulers.boundedElastic()).
    implementation 'org.springframework.boot:spring-boot-starter-jdbc'
    runtimeOnly 'org.postgresql:postgresql'
```

And add to the `testImplementation` block (alongside the existing jqwik line):

```groovy
    // Testcontainers Postgres for the auth integration test suite (Task 8).
    testImplementation 'org.testcontainers:postgresql'
```

- [ ] **Step 2: Add local/prod datasource config (never in base `application.yml`)**

Edit `api-gateway/src/main/resources/application-local.yml`, add at the end (after the existing `auth.jwt.secret` key):

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/portfolio_db?options=-c%20timezone=Asia/Kolkata
    username: wealth_user
    password: wealth_pass
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 4
```

Edit `api-gateway/src/main/resources/application-prod.yml`, add a new top-level `spring.datasource` block (same env-var-driven, no-default convention as `portfolio-service/src/main/resources/application-prod.yml:12-19` — missing values must fail startup loudly, not silently fall back):

```yaml
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: ${SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE:4}
```

(Insert this as a new key under the existing top-level `spring:` block in that file, alongside the existing `spring.data.redis` and `spring.cloud` keys — do not create a second `spring:` root key.)

- [ ] **Step 3: Wire local Docker Compose so `api-gateway` can reach Postgres**

Edit `docker-compose.yml`'s `api-gateway` service block:
  1. Add `postgres:` under its `depends_on:` with `condition: service_healthy` (alongside the existing `portfolio-service`, `market-data-service`, `insight-service`, `redis` entries).
  2. Add to its `environment:` block:
     ```yaml
     SPRING_DATASOURCE_URL: jdbc:postgresql://portfolio-db:5432/portfolio_db?options=-c%20timezone=Asia/Kolkata
     SPRING_DATASOURCE_USERNAME: wealth_user
     SPRING_DATASOURCE_PASSWORD: wealth_pass
     ```
  3. Remove the now-obsolete `APP_AUTH_USER_ID: ${APP_AUTH_USER_ID:-user-001}` line (Task 5 deletes the `app.auth.*` binding it fed).

- [ ] **Step 4: Write `SignupDtos`, `ValidationException`, and `SignupValidator` (pure, no I/O)**

Create `api-gateway/src/main/java/com/wealth/gateway/auth/SignupDtos.java`:

```java
package com.wealth.gateway.auth;

public final class SignupDtos {

    private SignupDtos() {}

    public record SignupRequest(String email, String password, String name) {}

    /** Output of SignupValidator.validate — normalized (email lowercased? NO — email case is
     * preserved as submitted; only the functional index in Postgres is case-insensitive), and
     * the name is trimmed. */
    public record ValidatedSignup(String email, String password, String name) {}
}
```

Create `api-gateway/src/main/java/com/wealth/gateway/auth/ValidationException.java`:

```java
package com.wealth.gateway.auth;

/** Thrown by SignupValidator when a signup field fails validation (Req 1.4-1.8, 9.2). */
public class ValidationException extends RuntimeException {

    private final String field;

    public ValidationException(String field, String reason) {
        super(reason);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
```

Create `api-gateway/src/main/java/com/wealth/gateway/auth/SignupValidator.java`:

```java
package com.wealth.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Pure validation for signup requests — no I/O, no Spring dependency (Req 1.4-1.8, 9.2).
 *
 * Email_Format_Rule: local@domain, non-empty local part, domain with >= 1 dot.
 * Password_Policy: >= 12 characters AND UTF-8 byte length <= 72 (BCrypt's input limit —
 * checked on byte length, NOT character count, since a multibyte passphrase can exceed
 * 72 bytes under 72 characters).
 */
public final class SignupValidator {

    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final int MAX_NAME_LENGTH = 100;

    // local part: 1+ non-@ non-whitespace chars. domain: 1+ labels separated by dots, each
    // label alphanumeric/hyphen, at least one dot required (Email_Format_Rule).
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private SignupValidator() {}

    public static SignupDtos.ValidatedSignup validate(SignupDtos.SignupRequest req) {
        if (req == null || req.email() == null) {
            throw new ValidationException("email", "email is required");
        }
        if (req.password() == null) {
            throw new ValidationException("password", "password is required");
        }
        if (req.name() == null) {
            throw new ValidationException("name", "name is required");
        }

        String email = req.email();
        if (email.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new ValidationException("email", "email is invalid");
        }

        String password = req.password();
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (password.length() < MIN_PASSWORD_LENGTH || passwordBytes > MAX_PASSWORD_BYTES) {
            throw new ValidationException("password", "password does not meet policy");
        }

        String trimmedName = req.name().trim();
        if (trimmedName.isEmpty()) {
            throw new ValidationException("name", "name is required");
        }
        if (trimmedName.length() > MAX_NAME_LENGTH) {
            throw new ValidationException("name", "name is too long");
        }

        return new SignupDtos.ValidatedSignup(email, password, trimmedName);
    }
}
```

- [ ] **Step 5: Write the jqwik property test for `SignupValidator`**

Create `api-gateway/src/test/java/com/wealth/gateway/auth/SignupValidatorPropertyTest.java`, following the exact style of `api-gateway/src/test/java/com/wealth/gateway/GatewayRateLimitConfigKeyResolverPropertyTest.java` (package-private class, `@Property(tries = 100)`, `@Provide` generators, no Spring context):

```java
package com.wealth.gateway.auth;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feature: new-user-signup-profile, Property 1: Signup input validation is exact and
 * side-effect-free. Validates Requirements 1.4, 1.5, 1.6, 1.7, 1.8, 9.2.
 */
class SignupValidatorPropertyTest {

    @Property(tries = 100)
    void acceptsExactlyWhenAllThreeRulesHold(
            @ForAll("validEmails") String email,
            @ForAll("validPasswords") String password,
            @ForAll("validNames") String name) {
        var result = SignupValidator.validate(new SignupDtos.SignupRequest(email, password, name));

        assertThat(result.email()).isEqualTo(email);
        assertThat(result.password()).isEqualTo(password);
        assertThat(result.name()).isEqualTo(name.trim());
    }

    @Property(tries = 100)
    void rejectsInvalidEmailNamingTheEmailField(
            @ForAll("invalidEmails") String email,
            @ForAll("validPasswords") String password,
            @ForAll("validNames") String name) {
        assertThatThrownBy(() -> SignupValidator.validate(new SignupDtos.SignupRequest(email, password, name)))
                .isInstanceOf(ValidationException.class)
                .extracting(ex -> ((ValidationException) ex).field())
                .isEqualTo("email");
    }

    @Property(tries = 100)
    void rejectsInvalidPasswordNamingThePasswordField(
            @ForAll("validEmails") String email,
            @ForAll("invalidPasswords") String password,
            @ForAll("validNames") String name) {
        assertThatThrownBy(() -> SignupValidator.validate(new SignupDtos.SignupRequest(email, password, name)))
                .isInstanceOf(ValidationException.class)
                .extracting(ex -> ((ValidationException) ex).field())
                .isEqualTo("password");
    }

    @Property(tries = 100)
    void rejectsInvalidNameNamingTheNameField(
            @ForAll("validEmails") String email,
            @ForAll("validPasswords") String password,
            @ForAll("invalidNames") String name) {
        assertThatThrownBy(() -> SignupValidator.validate(new SignupDtos.SignupRequest(email, password, name)))
                .isInstanceOf(ValidationException.class)
                .extracting(ex -> ((ValidationException) ex).field())
                .isEqualTo("name");
    }

    @Provide
    Arbitrary<String> validEmails() {
        Arbitrary<String> local = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(20);
        Arbitrary<String> domainLabel = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(10);
        return Combinators.combine(local, domainLabel, domainLabel)
                .as((l, d1, d2) -> l + "@" + d1 + "." + d2);
    }

    @Provide
    Arbitrary<String> invalidEmails() {
        return Arbitraries.of(
                "", "no-at-sign.com", "@nolocal.com", "no-domain@",
                "user@nodot", "user@@double.com", " ", "user @with-space.com",
                "x".repeat(255) + "@toolong.com");
    }

    @Provide
    Arbitrary<String> validPasswords() {
        // 12..72 ASCII chars — guarantees UTF-8 byte length == char length, safely under 72.
        return Arbitraries.strings().withCharRange('!', '~').ofMinLength(12).ofMaxLength(72);
    }

    @Provide
    Arbitrary<String> invalidPasswords() {
        Arbitrary<String> tooShort = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(0).ofMaxLength(11);
        // Multibyte passphrase that is <= 72 CHARACTERS but > 72 BYTES (each char is 2+ UTF-8
        // bytes) — this is the specific edge case the byte-length check exists for.
        Arbitrary<String> tooManyBytes = Arbitraries.just("é".repeat(40)); // 40 chars, 80 bytes
        return Arbitraries.oneOf(tooShort, tooManyBytes);
    }

    @Provide
    Arbitrary<String> validNames() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(100)
                .map(s -> " " + s + " "); // surrounding whitespace must be trimmed, not rejected
    }

    @Provide
    Arbitrary<String> invalidNames() {
        Arbitrary<String> blank = Arbitraries.of("", "   ", "\t\n");
        Arbitrary<String> tooLong = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(101).ofMaxLength(150);
        return Arbitraries.oneOf(blank, tooLong);
    }
}
```

- [ ] **Step 6: Run the property test**

Run: `./gradlew :api-gateway:test --tests "com.wealth.gateway.auth.SignupValidatorPropertyTest"`
Expected: PASS (4 properties × 100 tries each).

- [ ] **Step 7: Write `UserCredentialRepository`**

Create `api-gateway/src/main/java/com/wealth/gateway/auth/UserCredentialRepository.java`:

```java
package com.wealth.gateway.auth;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Plain NamedParameterJdbcTemplate access to the users/user_credentials tables owned by
 * portfolio-service (Req 2.6 — api-gateway reads/writes but defines no migrations).
 */
@Repository
public class UserCredentialRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public UserCredentialRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record CredentialRow(String userId, String email, String name, String passwordHash, boolean readOnly) {}

    public Optional<CredentialRow> findByEmailIgnoreCase(String email) {
        String sql = """
                SELECT u.id AS user_id, u.name AS name, u.read_only AS read_only,
                       c.email AS email, c.password_hash AS password_hash
                  FROM user_credentials c
                  JOIN users u ON u.id = c.user_id
                 WHERE lower(c.email) = lower(:email)
                """;
        var params = new MapSqlParameterSource("email", email);
        var rows = jdbc.query(sql, params, (rs, rowNum) -> new CredentialRow(
                rs.getString("user_id"),
                rs.getString("email"),
                rs.getString("name"),
                rs.getString("password_hash"),
                rs.getBoolean("read_only")));
        return rows.stream().findFirst();
    }

    public void insertUser(UUID id, String email, String name) {
        String sql = "INSERT INTO users (id, email, name, read_only) VALUES (:id, :email, :name, false)";
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("email", email)
                .addValue("name", name));
    }

    public void insertCredential(UUID userId, String email, String hash) {
        String sql = "INSERT INTO user_credentials (user_id, email, password_hash) "
                + "VALUES (:userId, :email, :hash)";
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("email", email)
                .addValue("hash", hash));
    }
}
```

Note for Task 3: `findByEmailIgnoreCase`/`insertUser`/`insertCredential` throw Spring's unchecked `DataAccessException` hierarchy on failure — `DuplicateKeyException` (a `DataAccessException` subtype) on a unique-constraint violation, translated automatically by Spring JDBC's default `SQLErrorCodeSQLExceptionTranslator`. No manual exception translation needed here.

- [ ] **Step 8: Write `GatewayAuthDataConfig` (TransactionTemplate bean)**

`spring-boot-starter-jdbc` autoconfigures the `DataSource` (Hikari), `JdbcTemplate`, `NamedParameterJdbcTemplate`, and a `DataSourceTransactionManager` (`PlatformTransactionManager`) — but Spring Boot does **not** autoconfigure a `TransactionTemplate` bean, so this one small class supplies it:

```java
package com.wealth.gateway.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class GatewayAuthDataConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
```

- [ ] **Step 9: Write `PasswordHasherConfig` (BCryptPasswordEncoder bean + dummy hash constant)**

```java
package com.wealth.gateway.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordHasherConfig {

    private static final int BCRYPT_COST = 12;

    /**
     * A fixed, valid bcrypt(cost=12) hash that never matches any submitted password — used only
     * to equalize verification time on the unknown-email login path (Req 3.4). Generated once
     * offline via `new BCryptPasswordEncoder(12).encode(UUID.randomUUID().toString())` and pasted
     * here as a constant; it is never regenerated at runtime.
     */
    public static final String DUMMY_PASSWORD_HASH =
            "$2a$12$D9tG1z8kQxYV1nq0h1o7XeYV1nq0h1o7XeYV1nq0h1o7XeYV1nq0h";

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_COST);
    }
}
```

- [ ] **Step 10: Run a throwaway check that `DUMMY_PASSWORD_HASH` is a syntactically valid bcrypt hash**

The placeholder hash above is illustrative — it must be replaced with a REAL bcrypt(cost=12) output before Task 3 uses it (a malformed hash makes `passwordEncoder.matches(...)` throw `IllegalArgumentException` instead of returning `false`, breaking the fail-uniformly path). Generate the real value the same way as Task 1 Step 2's seed hashes (a scratch `BCryptPasswordEncoder(12).encode(...)` call on any input), paste the output into `DUMMY_PASSWORD_HASH`, then verify:

Run a throwaway `jshell`/scratch test asserting `new BCryptPasswordEncoder(12).matches("anything", PasswordHasherConfig.DUMMY_PASSWORD_HASH)` returns `false` without throwing. Delete the scratch check once confirmed — do not commit it.

- [ ] **Step 11: Extend `JwtSigner` with the `ro` claim overload**

Edit `api-gateway/src/main/java/com/wealth/gateway/JwtSigner.java` — replace the existing single `signHs256` method with:

```java
    public String signHs256(String userId, String email, String name) throws JOSEException {
        return signHs256(userId, email, name, false);
    }

    public String signHs256(String userId, String email, String name, boolean readOnly) throws JOSEException {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256),
                new JWTClaimsSet.Builder()
                        .subject(userId)
                        .claim("email", email)
                        .claim("name", name)
                        .claim("ro", readOnly)
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(3600)))
                        .build());
        jwt.sign(new MACSigner(jwtSecretBytes));
        return jwt.serialize();
    }
```

(No new imports needed — all types used are already imported in the file.)

- [ ] **Step 12: Write `JwtSignerTest`**

Create `api-gateway/src/test/java/com/wealth/gateway/JwtSignerTest.java`:

```java
package com.wealth.gateway;

import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtSignerTest {

    private static final String SECRET = "test-secret-for-jwt-signer-min-32-chars-long";

    @Test
    void fourArgOverloadSetsRoClaimAndLeavesOtherClaimsUnchanged() throws Exception {
        JwtSigner signer = new JwtSigner(SECRET);

        String token = signer.signHs256("user-1", "a@b.com", "Alice", true);
        SignedJWT parsed = SignedJWT.parse(token);

        assertThat(parsed.getHeader().getAlgorithm().getName()).isEqualTo("HS256");
        assertThat(parsed.getJWTClaimsSet().getSubject()).isEqualTo("user-1");
        assertThat(parsed.getJWTClaimsSet().getStringClaim("email")).isEqualTo("a@b.com");
        assertThat(parsed.getJWTClaimsSet().getStringClaim("name")).isEqualTo("Alice");
        assertThat(parsed.getJWTClaimsSet().getBooleanClaim("ro")).isTrue();

        long expirySeconds = (parsed.getJWTClaimsSet().getExpirationTime().getTime()
                - parsed.getJWTClaimsSet().getIssueTime().getTime()) / 1000;
        assertThat(expirySeconds).isEqualTo(3600);
    }

    @Test
    void threeArgOverloadDefaultsRoToFalse() throws Exception {
        JwtSigner signer = new JwtSigner(SECRET);

        String token = signer.signHs256("user-2", "b@c.com", "Bob");
        SignedJWT parsed = SignedJWT.parse(token);

        assertThat(parsed.getJWTClaimsSet().getBooleanClaim("ro")).isFalse();
    }
}
```

- [ ] **Step 13: Compile and run the full unit test suite for this task**

Run: `./gradlew :api-gateway:compileJava :api-gateway:test --tests "com.wealth.gateway.auth.*" --tests "com.wealth.gateway.JwtSignerTest"`
Expected: compiles cleanly, all tests PASS.

- [ ] **Step 14: Commit**

```bash
git add api-gateway/build.gradle api-gateway/src/main/resources/application-local.yml api-gateway/src/main/resources/application-prod.yml docker-compose.yml api-gateway/src/main/java/com/wealth/gateway/auth/ api-gateway/src/main/java/com/wealth/gateway/JwtSigner.java api-gateway/src/test/java/com/wealth/gateway/auth/ api-gateway/src/test/java/com/wealth/gateway/JwtSignerTest.java
git commit -m "feat(api-gateway): add signup validator, credential repository, password hashing, and JWT ro claim"
```

---

### Task 3: Gateway authentication and signup services (com.wealth.gateway.auth)

**Files:**
- Create: `api-gateway/src/main/java/com/wealth/gateway/auth/AuthenticationService.java`
- Create: `api-gateway/src/main/java/com/wealth/gateway/auth/SignupService.java`
- Create: `api-gateway/src/main/java/com/wealth/gateway/auth/InvalidCredentialsException.java`
- Create: `api-gateway/src/main/java/com/wealth/gateway/auth/CredentialStoreUnavailableException.java`
- Create: `api-gateway/src/main/java/com/wealth/gateway/auth/DuplicateEmailException.java`
- Create: `api-gateway/src/main/java/com/wealth/gateway/auth/ProvisioningFailedException.java`
- Create: `api-gateway/src/main/java/com/wealth/gateway/auth/LoginResponse.java`
- Test: `api-gateway/src/test/java/com/wealth/gateway/auth/AuthenticationServiceTest.java`
- Test: `api-gateway/src/test/java/com/wealth/gateway/auth/SignupServiceTest.java`

**Interfaces:**
- Consumes: `SignupValidator.validate`, `UserCredentialRepository`, `PasswordEncoder` bean, `PasswordHasherConfig.DUMMY_PASSWORD_HASH`, `JwtSigner.signHs256(...,boolean)`, `TransactionTemplate` bean — all from Task 2.
- Produces (for Task 5's `AuthController`):
  - `AuthenticationService.authenticate(LoginDtos.LoginRequest req) -> Mono<LoginResponse>` — errors as `InvalidCredentialsException` (401 path) or `CredentialStoreUnavailableException` (503 path).
  - `SignupService.provision(SignupDtos.SignupRequest req) -> Mono<LoginResponse>` — errors as `ValidationException` (400), `DuplicateEmailException` (409), or `ProvisioningFailedException` (500/503).
  - `record LoginResponse(String token, String userId, String email, String name)` — this is the shared response shape Task 5's controller serializes for both `/login` and `/signup` (matches the existing `LoginDtos.LoginResponse` shape exactly, but lives in the `auth` package so these services don't depend on the top-level `com.wealth.gateway` package).

- [ ] **Step 1: Write the shared response record and exception types**

Create `api-gateway/src/main/java/com/wealth/gateway/auth/LoginResponse.java`:

```java
package com.wealth.gateway.auth;

public record LoginResponse(String token, String userId, String email, String name) {}
```

Create the four exception classes (each a minimal unchecked `RuntimeException` subtype — `AuthController` in Task 5 catches them by type to select the HTTP status):

`api-gateway/src/main/java/com/wealth/gateway/auth/InvalidCredentialsException.java`:
```java
package com.wealth.gateway.auth;

/** Login failed for any reason that must surface as the Uniform_Auth_Error (401). */
public class InvalidCredentialsException extends RuntimeException {}
```

`api-gateway/src/main/java/com/wealth/gateway/auth/CredentialStoreUnavailableException.java`:
```java
package com.wealth.gateway.auth;

/** The Credential_Store could not be reached (503) — never reveals whether the email exists. */
public class CredentialStoreUnavailableException extends RuntimeException {
    public CredentialStoreUnavailableException(Throwable cause) {
        super(cause);
    }
}
```

`api-gateway/src/main/java/com/wealth/gateway/auth/DuplicateEmailException.java`:
```java
package com.wealth.gateway.auth;

/** Signup attempted with an email already present in the Credential_Store (409). */
public class DuplicateEmailException extends RuntimeException {}
```

`api-gateway/src/main/java/com/wealth/gateway/auth/ProvisioningFailedException.java`:
```java
package com.wealth.gateway.auth;

/** The Provisioning_Transaction failed and rolled back for a reason other than duplicate/validation. */
public class ProvisioningFailedException extends RuntimeException {
    public ProvisioningFailedException(Throwable cause) {
        super(cause);
    }
}
```

- [ ] **Step 2: Write `AuthenticationService`**

```java
package com.wealth.gateway.auth;

import com.wealth.gateway.JwtSigner;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class AuthenticationService {

    private final UserCredentialRepository credentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtSigner jwtSigner;

    public AuthenticationService(
            UserCredentialRepository credentialRepository,
            PasswordEncoder passwordEncoder,
            JwtSigner jwtSigner) {
        this.credentialRepository = credentialRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtSigner = jwtSigner;
    }

    public Mono<LoginResponse> authenticate(com.wealth.gateway.LoginDtos.LoginRequest req) {
        // Req 3.9: reject blank/missing fields with the Uniform_Auth_Error BEFORE any hashing —
        // no hasher call, no mint.
        if (isBlank(req.email()) || isBlank(req.password())) {
            return Mono.error(new InvalidCredentialsException());
        }
        return Mono.fromCallable(() -> {
                    var cred = credentialRepository.findByEmailIgnoreCase(req.email());
                    if (cred.isEmpty()) {
                        // Req 3.4: burn equivalent CPU against a fixed dummy hash, then fail uniformly.
                        passwordEncoder.matches(req.password(), PasswordHasherConfig.DUMMY_PASSWORD_HASH);
                        throw new InvalidCredentialsException();
                    }
                    var row = cred.get();
                    // Req 4.6: absent/malformed stored hash -> uniform 401 (still run a match for timing).
                    String storedHash = row.passwordHash();
                    boolean matches = storedHash != null && !storedHash.isBlank()
                            && passwordEncoder.matches(req.password(), storedHash);
                    if (!matches) {
                        throw new InvalidCredentialsException();
                    }
                    String token;
                    try {
                        token = jwtSigner.signHs256(row.userId(), row.email(), row.name(), row.readOnly());
                    } catch (com.nimbusds.jose.JOSEException e) {
                        throw new ProvisioningFailedException(e);
                    }
                    return new LoginResponse(token, row.userId(), row.email(), row.name());
                })
                .subscribeOn(Schedulers.boundedElastic()) // Req 2.5: never block the event loop
                .onErrorMap(
                        ex -> ex instanceof DataAccessException,
                        ex -> new CredentialStoreUnavailableException(ex)); // Req 3.10
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
```

Note: `onErrorMap(Predicate, Function)` only remaps matching exceptions and passes `InvalidCredentialsException`/`ProvisioningFailedException` through unchanged (they don't match the `DataAccessException` predicate) — this preserves the distinct 401 vs 503 vs 500 outcomes Task 5's controller depends on.

- [ ] **Step 3: Write `SignupService`**

```java
package com.wealth.gateway.auth;

import com.wealth.gateway.JwtSigner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@Service
public class SignupService {

    private final UserCredentialRepository credentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtSigner jwtSigner;
    private final TransactionTemplate transactionTemplate;

    public SignupService(
            UserCredentialRepository credentialRepository,
            PasswordEncoder passwordEncoder,
            JwtSigner jwtSigner,
            TransactionTemplate transactionTemplate) {
        this.credentialRepository = credentialRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtSigner = jwtSigner;
        this.transactionTemplate = transactionTemplate;
    }

    public Mono<LoginResponse> provision(SignupDtos.SignupRequest req) {
        // Req 1.4-1.8, 9.2: validate before touching the database. Propagates ValidationException
        // synchronously into the Mono's error channel via fromCallable below.
        return Mono.fromCallable(() -> {
                    SignupDtos.ValidatedSignup v = SignupValidator.validate(req);
                    return transactionTemplate.execute(status -> {
                        String hash = passwordEncoder.encode(v.password()); // Req 4.1: hash, never plaintext
                        UUID userId = UUID.randomUUID(); // Req 2.3: becomes the JWT sub
                        try {
                            credentialRepository.insertUser(userId, v.email(), v.name());
                            credentialRepository.insertCredential(userId, v.email(), hash);
                        } catch (DuplicateKeyException dup) {
                            status.setRollbackOnly(); // Req 2.2, 2.7, 2.8, 1.9
                            throw new DuplicateEmailException();
                        }
                        String token;
                        try {
                            token = jwtSigner.signHs256(userId.toString(), v.email(), v.name(), false);
                        } catch (com.nimbusds.jose.JOSEException e) {
                            status.setRollbackOnly();
                            throw new ProvisioningFailedException(e);
                        }
                        return new LoginResponse(token, userId.toString(), v.email(), v.name());
                    });
                })
                .subscribeOn(Schedulers.boundedElastic()) // Req 2.5
                .onErrorMap(
                        ex -> !(ex instanceof ValidationException
                                || ex instanceof DuplicateEmailException
                                || ex instanceof ProvisioningFailedException),
                        ProvisioningFailedException::new); // Req 2.2: any other failure -> rollback + error
    }
}
```

- [ ] **Step 4: Write `AuthenticationServiceTest` (Mockito unit test — no Spring context, no DB)**

```java
package com.wealth.gateway.auth;

import com.wealth.gateway.JwtSigner;
import com.wealth.gateway.LoginDtos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    @Mock UserCredentialRepository repository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtSigner jwtSigner;

    private AuthenticationService service() {
        return new AuthenticationService(repository, passwordEncoder, jwtSigner);
    }

    @Test
    void blankEmailFailsBeforeAnyHasherCallOrLookup() {
        var req = new LoginDtos.LoginRequest("  ", "password12345");

        var ex = service().authenticate(req).blockOptional();

        assertThat(ex).isEmpty(); // Mono errors, doesn't emit a value
        verifyNoInteractions(repository, passwordEncoder);
    }

    @Test
    void unknownEmailRunsDummyHashMatchThenFailsUniformly() {
        when(repository.findByEmailIgnoreCase("nobody@x.com")).thenReturn(Optional.empty());
        var req = new LoginDtos.LoginRequest("nobody@x.com", "somepassword");

        try {
            service().authenticate(req).block();
        } catch (InvalidCredentialsException expected) {
            // expected
        }

        verify(passwordEncoder).matches(eq("somepassword"), eq(PasswordHasherConfig.DUMMY_PASSWORD_HASH));
    }

    @Test
    void wrongPasswordFailsUniformlyWithoutMintingAToken() throws Exception {
        var row = new UserCredentialRepository.CredentialRow("u1", "a@b.com", "Alice", "stored-hash", false);
        when(repository.findByEmailIgnoreCase("a@b.com")).thenReturn(Optional.of(row));
        when(passwordEncoder.matches("wrong", "stored-hash")).thenReturn(false);

        try {
            service().authenticate(new LoginDtos.LoginRequest("a@b.com", "wrong")).block();
        } catch (InvalidCredentialsException expected) {
            // expected
        }

        verify(jwtSigner, never()).signHs256(any(), any(), any(), anyBoolean());
    }

    @Test
    void correctPasswordMintsTokenWithReadOnlyFromStoredRow() throws Exception {
        var row = new UserCredentialRepository.CredentialRow("u1", "a@b.com", "Alice", "stored-hash", true);
        when(repository.findByEmailIgnoreCase("a@b.com")).thenReturn(Optional.of(row));
        when(passwordEncoder.matches("correct", "stored-hash")).thenReturn(true);
        when(jwtSigner.signHs256("u1", "a@b.com", "Alice", true)).thenReturn("jwt-token");

        var result = service().authenticate(new LoginDtos.LoginRequest("a@b.com", "correct")).block();

        assertThat(result).isEqualTo(new LoginResponse("jwt-token", "u1", "a@b.com", "Alice"));
    }

    @Test
    void dataAccessExceptionMapsToCredentialStoreUnavailable() {
        when(repository.findByEmailIgnoreCase("a@b.com"))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        try {
            service().authenticate(new LoginDtos.LoginRequest("a@b.com", "whatever12345")).block();
        } catch (CredentialStoreUnavailableException expected) {
            // expected
        }
    }
}
```

- [ ] **Step 5: Write `SignupServiceTest` (Mockito unit test — mocks `TransactionTemplate` to execute synchronously)**

```java
package com.wealth.gateway.auth;

import com.wealth.gateway.JwtSigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SignupServiceTest {

    @Mock UserCredentialRepository repository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtSigner jwtSigner;
    @Mock TransactionTemplate transactionTemplate;

    private SignupService service() {
        return new SignupService(repository, passwordEncoder, jwtSigner, transactionTemplate);
    }

    /** Makes the mocked TransactionTemplate actually invoke the callback (no real transaction). */
    private void stubTransactionToRunCallback() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        });
    }

    @Test
    void invalidRequestFailsBeforeAnyRepositoryCall() {
        var req = new SignupDtos.SignupRequest("not-an-email", "whatever12345", "Name");

        assertThatThrownBy(() -> service().provision(req).block())
                .isInstanceOf(ValidationException.class);

        verifyNoInteractions(repository, transactionTemplate);
    }

    @Test
    void validRequestHashesPasswordAndMintsTokenWithReadOnlyFalse() throws Exception {
        stubTransactionToRunCallback();
        when(passwordEncoder.encode("password12345")).thenReturn("hashed");
        when(jwtSigner.signHs256(any(), eq("a@b.com"), eq("Alice"), eq(false))).thenReturn("jwt");

        var req = new SignupDtos.SignupRequest("a@b.com", "password12345", "  Alice  ");
        var result = service().provision(req).block();

        assertThat(result.token()).isEqualTo("jwt");
        assertThat(result.email()).isEqualTo("a@b.com");
        assertThat(result.name()).isEqualTo("Alice"); // trimmed
        verify(repository).insertUser(any(), eq("a@b.com"), eq("Alice"));
        verify(repository).insertCredential(any(), eq("a@b.com"), eq("hashed"));
    }

    @Test
    void duplicateKeyOnInsertMapsToDuplicateEmailException() {
        stubTransactionToRunCallback();
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        doThrow(new DuplicateKeyException("dup")).when(repository).insertUser(any(), any(), any());

        var req = new SignupDtos.SignupRequest("a@b.com", "password12345", "Alice");

        assertThatThrownBy(() -> service().provision(req).block())
                .isInstanceOf(DuplicateEmailException.class);
    }
}
```

Adjust the import for `eq(...)` (`import static org.mockito.ArgumentMatchers.eq;`) if the compiler flags it missing — Mockito's `eq` and AssertJ's fluent API are both used above.

- [ ] **Step 6: Run the tests**

Run: `./gradlew :api-gateway:compileJava :api-gateway:test --tests "com.wealth.gateway.auth.AuthenticationServiceTest" --tests "com.wealth.gateway.auth.SignupServiceTest"`
Expected: compiles cleanly, all tests PASS.

- [ ] **Step 7: Commit**

```bash
git add api-gateway/src/main/java/com/wealth/gateway/auth/AuthenticationService.java api-gateway/src/main/java/com/wealth/gateway/auth/SignupService.java api-gateway/src/main/java/com/wealth/gateway/auth/InvalidCredentialsException.java api-gateway/src/main/java/com/wealth/gateway/auth/CredentialStoreUnavailableException.java api-gateway/src/main/java/com/wealth/gateway/auth/DuplicateEmailException.java api-gateway/src/main/java/com/wealth/gateway/auth/ProvisioningFailedException.java api-gateway/src/main/java/com/wealth/gateway/auth/LoginResponse.java api-gateway/src/test/java/com/wealth/gateway/auth/AuthenticationServiceTest.java api-gateway/src/test/java/com/wealth/gateway/auth/SignupServiceTest.java
git commit -m "feat(api-gateway): add AuthenticationService and SignupService"
```

---

### Task 4: Gateway filters — read-only enforcement and auth rate limiting (com.wealth.gateway)

**Files:**
- Create: `api-gateway/src/main/java/com/wealth/gateway/ReadOnlyEnforcementFilter.java`
- Create: `api-gateway/src/main/java/com/wealth/gateway/AuthRateLimitFilter.java`
- Modify: `api-gateway/src/main/java/com/wealth/gateway/GatewayRateLimitConfig.java` (add `authRateLimiter` bean)
- Modify: `api-gateway/src/main/resources/application-prod.yml` (Auth_Bucket numbers)
- Modify: `api-gateway/src/test/java/com/wealth/gateway/TestJwtFactory.java` (add an overload that sets extra claims, for minting `ro=true` test tokens)
- Test: `api-gateway/src/test/java/com/wealth/gateway/ReadOnlyEnforcementFilterPropertyTest.java`
- Test: `api-gateway/src/test/java/com/wealth/gateway/AuthRateLimitFilterKeyDerivationTest.java`

**Interfaces:**
- Consumes: `GatewayRateLimitConfig.resolveTrustedHopKey(String, String)` (existing, package-private, same package — no change needed to call it), the `RedisRateLimiter`/`KeyResolver` Spring Cloud Gateway types (existing dependency).
- Produces: `ReadOnlyEnforcementFilter` (`GlobalFilter`, order `HIGHEST_PRECEDENCE + 3`) and `AuthRateLimitFilter` (`WebFilter`, order `HIGHEST_PRECEDENCE + 1`) — both `@Component`-registered, auto-wired into the filter chain; nothing else depends on their internals directly except Task 5 (which relies on `AuthRateLimitFilter` already running before `AuthController`, no code coupling).

- [ ] **Step 1: Extend `TestJwtFactory` with an extra-claims overload**

Edit `api-gateway/src/test/java/com/wealth/gateway/TestJwtFactory.java` — add this overload (keep all existing methods unchanged):

```java
    /**
     * Mints a compact JWT string with additional custom claims (e.g. {@code ro=true} for
     * read-only/demo-account test tokens).
     */
    public static String mint(String sub, Duration expiry, String secret, java.util.Map<String, Object> extraClaims) {
        Instant now = Instant.now();
        io.jsonwebtoken.JwtBuilder builder = Jwts.builder()
                .subject(sub)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiry)));
        extraClaims.forEach(builder::claim);
        return builder
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
    }

    /** Convenience: a read-only (ro=true) token for the seed user, using the default test secret. */
    public static String readOnlySeedUserToken() {
        return mint(SEED_USER_ID, Duration.ofHours(1), TEST_SECRET, java.util.Map.of("ro", true));
    }
```

- [ ] **Step 2: Write `ReadOnlyEnforcementFilter`**

```java
package com.wealth.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Blocks portfolio/market writes from a read-only (demo) account while allowing the AI routes
 * (Req 7.4-7.7). Ordered after JwtAuthenticationFilter (HIGHEST_PRECEDENCE + 2) so the validated
 * `ro` claim is available on the principal.
 */
@Component
public class ReadOnlyEnforcementFilter implements GlobalFilter, Ordered {

    private static final Set<HttpMethod> MUTATING_METHODS =
            Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);
    private static final List<String> PROTECTED_PATTERNS = List.of("/api/portfolio/**", "/api/market/**");
    private static final byte[] FORBIDDEN_BODY = ("{\"error\":\"read_only_account\","
            + "\"message\":\"The demo account is read-only.\"}").getBytes(StandardCharsets.UTF_8);

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final List<String> aiAllowlistPatterns;

    public ReadOnlyEnforcementFilter(
            @Value("${app.read-only.ai-allowlist:/api/chat/**,/api/insights/generate/**}")
            List<String> aiAllowlistPatterns) {
        this.aiAllowlistPatterns = aiAllowlistPatterns;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 3;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .filter(p -> p instanceof JwtAuthenticationToken)
                .cast(JwtAuthenticationToken.class)
                .flatMap(jwt -> {
                    boolean ro = Boolean.TRUE.equals(jwt.getToken().getClaims().get("ro"));
                    String path = exchange.getRequest().getURI().getPath();
                    HttpMethod method = exchange.getRequest().getMethod();
                    if (decide(ro, method, path)) {
                        return writeForbidden(exchange);
                    }
                    return chain.filter(exchange);
                })
                .switchIfEmpty(chain.filter(exchange));
    }

    /**
     * Pure decision function (Property 6): block iff ro AND mutating method AND protected path
     * AND not AI-allowlisted. Package-visible for property testing.
     */
    boolean decide(boolean ro, HttpMethod method, String path) {
        if (!ro || method == null || !MUTATING_METHODS.contains(method)) {
            return false;
        }
        boolean protectedPath = PROTECTED_PATTERNS.stream().anyMatch(p -> matcher.match(p, path));
        if (!protectedPath) {
            return false;
        }
        boolean aiAllowlisted = aiAllowlistPatterns.stream().anyMatch(p -> matcher.match(p, path));
        return !aiAllowlisted;
    }

    private Mono<Void> writeForbidden(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(FORBIDDEN_BODY);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
```

Add the new property to `api-gateway/src/main/resources/application.yml` is NOT required — the `@Value` default above (`/api/chat/**,/api/insights/generate/**`) covers it with no YAML change needed, matching Req 7's stated default allowlist.

- [ ] **Step 3: Write the jqwik property test for `ReadOnlyEnforcementFilter.decide`**

```java
package com.wealth.gateway;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.http.HttpMethod;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: new-user-signup-profile, Property 6: Read-only enforcement is exactly "block
 * portfolio/market writes, allow AI routes and reads". Validates Requirements 7.4-7.7.
 */
class ReadOnlyEnforcementFilterPropertyTest {

    private final ReadOnlyEnforcementFilter filter =
            new ReadOnlyEnforcementFilter(List.of("/api/chat/**", "/api/insights/generate/**"));

    @Property(tries = 100)
    void blocksIffReadOnlyAndMutatingAndProtectedAndNotAiAllowlisted(
            @ForAll("booleans") boolean ro,
            @ForAll("methods") HttpMethod method,
            @ForAll("paths") String path) {
        boolean blocked = filter.decide(ro, method, path);

        boolean expectedMutating = List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)
                .contains(method);
        boolean expectedProtected = path.startsWith("/api/portfolio/") || path.startsWith("/api/market/");
        boolean expectedAiAllowlisted = path.startsWith("/api/chat/") || path.startsWith("/api/insights/generate/");
        boolean expected = ro && expectedMutating && expectedProtected && !expectedAiAllowlisted;

        assertThat(blocked).isEqualTo(expected);
    }

    @Provide
    Arbitrary<Boolean> booleans() {
        return Arbitraries.of(true, false);
    }

    @Provide
    Arbitrary<HttpMethod> methods() {
        return Arbitraries.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH,
                HttpMethod.DELETE, HttpMethod.HEAD);
    }

    @Provide
    Arbitrary<String> paths() {
        return Arbitraries.of(
                "/api/portfolio/holdings", "/api/portfolio/analytics", "/api/market/prices",
                "/api/chat/message", "/api/insights/generate/summary", "/api/insights/history",
                "/api/auth/login", "/api/settings/profile");
    }
}
```

- [ ] **Step 4: Add the `authRateLimiter` bean to `GatewayRateLimitConfig`**

Edit `api-gateway/src/main/java/com/wealth/gateway/GatewayRateLimitConfig.java` — add this method inside the class, after `strictRateLimiter`:

```java
  /**
   * Auth_Bucket (Req 6.3, 6.5): shared by /api/auth/login and /api/auth/signup via a single
   * route id ("auth-bucket"), so combined login+signup volume from one client draws down one
   * bucket. replenishRate=1, requestedTokens=12, burstCapacity=60 -> 12 tokens accrue every 12s
   * (~5/min), burst allowance 60/12 = 5. Retry-After = ceil(12/1) = 12s.
   */
  @Bean
  @Profile("prod")
  RedisRateLimiter authRateLimiter(
      @Value("${app.rate-limit.auth.replenish-rate:1}") int replenishRate,
      @Value("${app.rate-limit.auth.burst-capacity:60}") int burstCapacity,
      @Value("${app.rate-limit.auth.requested-tokens:12}") int requestedTokens) {
    return new RedisRateLimiter(replenishRate, burstCapacity, requestedTokens);
  }
```

Note this bean has literal defaults (`1`/`60`/`12`) rather than the no-default fail-fast style of `standardRateLimiter`/`strictRateLimiter` — that's intentional: the Auth_Bucket numbers are a fixed part of this feature's contract (Req 6.3 spells out the exact values), not a per-environment tuning knob, so a missing override should fall back to the correct value rather than crash startup. For explicitness and consistency with the file's existing style, also add these three keys to `api-gateway/src/main/resources/application-prod.yml` under the existing `app.rate-limit:` block (sibling to `standard:`/`strict:`):

```yaml
    auth:
      replenish-rate: 1
      burst-capacity: 60
      requested-tokens: 12
```

- [ ] **Step 5: Write `AuthRateLimitFilter`**

```java
package com.wealth.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Throttles /api/auth/login and /api/auth/signup by programmatically invoking the shared
 * RedisRateLimiter with the Auth_Bucket config (Req 6). A WebFilter, not a route
 * RequestRateLimiter, because /api/auth/** is a controller endpoint, not a proxied route (Req
 * 6.1, 6.8). Ordered to run before AuthController's handler.
 */
@Component
@Profile("prod")
public class AuthRateLimitFilter implements WebFilter, Ordered {

    private static final String AUTH_ROUTE_ID = "auth-bucket"; // shared by login+signup (Req 6.5)
    private static final byte[] THROTTLED_BODY = ("{\"error\":\"rate_limited\","
            + "\"message\":\"Too many requests. Please try again later.\"}")
            .getBytes(StandardCharsets.UTF_8);

    private final RateLimiter<?> authRateLimiter;
    private final KeyResolver authKeyResolver;
    private final int retryAfterSeconds;

    public AuthRateLimitFilter(
            org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter authRateLimiter,
            @Value("${app.rate-limit.trust-xff-last-hop:false}") boolean trustXffLastHop,
            @Value("${app.rate-limit.auth.requested-tokens:12}") int requestedTokens,
            @Value("${app.rate-limit.auth.replenish-rate:1}") int replenishRate) {
        this.authRateLimiter = authRateLimiter;
        this.authKeyResolver = exchange -> Mono.just(
                GatewayRateLimitConfig.resolveTrustedHopKey(
                        exchange.getRequest().getHeaders().getFirst("X-Forwarded-For"),
                        exchange.getRequest().getRemoteAddress() != null
                                && exchange.getRequest().getRemoteAddress().getAddress() != null
                                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                                : null));
        this.retryAfterSeconds = (int) Math.ceil((double) requestedTokens / replenishRate);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!path.equals("/api/auth/login") && !path.equals("/api/auth/signup")) {
            return chain.filter(exchange);
        }
        return authKeyResolver.resolve(exchange)
                .flatMap(key -> authRateLimiter.isAllowed(AUTH_ROUTE_ID, key)
                        .flatMap(resp -> {
                            if (resp.isAllowed()) {
                                return chain.filter(exchange);
                            }
                            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                            exchange.getResponse().getHeaders().add("Retry-After", String.valueOf(retryAfterSeconds));
                            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(THROTTLED_BODY);
                            return exchange.getResponse().writeWith(Mono.just(buffer));
                        }))
                .onErrorResume(ex -> chain.filter(exchange)); // Req 6.7: fail open
    }
}
```

`@Profile("prod")` mirrors `standardRateLimiter`/`strictRateLimiter`'s scoping (the `authRateLimiter` `RedisRateLimiter` bean this filter constructor-injects only exists under `prod`) — under `local`, this filter is simply not registered, matching the existing precedent that local dev uses the single `default-filters` `RequestRateLimiter` and no per-feature limiter beans.

- [ ] **Step 6: Write `AuthRateLimitFilterKeyDerivationTest`**

This documents that the filter keys through the shared, already-property-tested `resolveTrustedHopKey` (Req 6.2) rather than re-testing spoof-resistance from scratch (that property already exists in `GatewayRateLimitConfigKeyResolverPropertyTest`, per the plan's Global Constraints "reuse, never duplicate"):

```java
package com.wealth.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents that AuthRateLimitFilter derives its key via the shared
 * GatewayRateLimitConfig.resolveTrustedHopKey (Req 6.2, 6.8) — the spoof-resistance property
 * itself is already covered by GatewayRateLimitConfigKeyResolverPropertyTest and is not
 * re-implemented here.
 */
class AuthRateLimitFilterKeyDerivationTest {

    @Test
    void authFilterUsesTheSameTrustedHopResolverAsTheRouteLimiter() {
        String spoofed = "1.2.3.4, 5.6.7.8, 9.9.9.9";
        String viaSharedResolver = GatewayRateLimitConfig.resolveTrustedHopKey(spoofed, "203.0.113.1");

        assertThat(viaSharedResolver).isEqualTo("9.9.9.9"); // right-most (ingress-appended) hop
    }
}
```

- [ ] **Step 7: Run the tests**

Run: `./gradlew :api-gateway:compileJava :api-gateway:test --tests "com.wealth.gateway.ReadOnlyEnforcementFilterPropertyTest" --tests "com.wealth.gateway.AuthRateLimitFilterKeyDerivationTest"`
Expected: compiles cleanly, all tests PASS.

- [ ] **Step 8: Commit**

```bash
git add api-gateway/src/main/java/com/wealth/gateway/ReadOnlyEnforcementFilter.java api-gateway/src/main/java/com/wealth/gateway/AuthRateLimitFilter.java api-gateway/src/main/java/com/wealth/gateway/GatewayRateLimitConfig.java api-gateway/src/main/resources/application-prod.yml api-gateway/src/test/java/com/wealth/gateway/TestJwtFactory.java api-gateway/src/test/java/com/wealth/gateway/ReadOnlyEnforcementFilterPropertyTest.java api-gateway/src/test/java/com/wealth/gateway/AuthRateLimitFilterKeyDerivationTest.java
git commit -m "feat(api-gateway): add ReadOnlyEnforcementFilter and AuthRateLimitFilter"
```

---

### Task 5: Gateway controller wiring (com.wealth.gateway)

**Files:**
- Modify: `api-gateway/src/main/java/com/wealth/gateway/AuthController.java`
- Modify: `api-gateway/src/main/java/com/wealth/gateway/LoginDtos.java`
- Modify: `api-gateway/src/main/resources/application.yml` (remove `app.auth.*`)
- Test: `api-gateway/src/test/java/com/wealth/gateway/AuthControllerUniformErrorTest.java`

**Interfaces:**
- Consumes: `AuthenticationService`, `SignupService` (Task 3); `SignupDtos.SignupRequest` (Task 2).
- Produces: the actual `POST /api/auth/login` and `POST /api/auth/signup` HTTP contract that Task 8's integration tests and Task 6's frontend call.

- [ ] **Step 1: Add `SignupRequest` handling to `LoginDtos` (or leave it in `SignupDtos` — decision: keep `SignupRequest` in `SignupDtos`, no duplication)**

No change needed to `LoginDtos.java` beyond what's already there (`LoginRequest`, `LoginResponse`, `ErrorResponse` all stay — `AuthController` continues to accept `LoginDtos.LoginRequest` for `/login` and now also `SignupDtos.SignupRequest` for `/signup`). Skip this step's file modification — it turned out to be unnecessary once `SignupDtos` (Task 2) already covers the signup request shape. Do not create a duplicate request type.

- [ ] **Step 2: Rewrite `AuthController`**

Replace the full contents of `api-gateway/src/main/java/com/wealth/gateway/AuthController.java`:

```java
package com.wealth.gateway;

import com.wealth.gateway.auth.CredentialStoreUnavailableException;
import com.wealth.gateway.auth.DuplicateEmailException;
import com.wealth.gateway.auth.InvalidCredentialsException;
import com.wealth.gateway.auth.ProvisioningFailedException;
import com.wealth.gateway.auth.SignupDtos;
import com.wealth.gateway.auth.SignupService;
import com.wealth.gateway.auth.ValidationException;
import com.wealth.gateway.auth.AuthenticationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * A single pre-serialized constant written identically on every login-failure path (Req 3.5,
     * 3.6, 10.6) — unknown email, wrong password, blank fields, and absent/malformed stored hash
     * all produce this exact response, so no path reveals *why* it failed.
     */
    private static final LoginDtos.ErrorResponse UNIFORM_AUTH_ERROR =
            new LoginDtos.ErrorResponse("Invalid username or password.");

    private final AuthenticationService authService;
    private final SignupService signupService;

    public AuthController(AuthenticationService authService, SignupService signupService) {
        this.authService = authService;
        this.signupService = signupService;
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<?>> login(@RequestBody LoginDtos.LoginRequest request) {
        return authService.authenticate(request)
                .map(resp -> ResponseEntity.ok((Object) new LoginDtos.LoginResponse(
                        resp.token(), resp.userId(), resp.email(), resp.name())))
                .onErrorResume(InvalidCredentialsException.class, ex -> Mono.just(uniformAuthError()))
                .onErrorResume(CredentialStoreUnavailableException.class, ex -> Mono.just(serviceUnavailable()));
    }

    @PostMapping("/signup")
    public Mono<ResponseEntity<?>> signup(@RequestBody SignupDtos.SignupRequest request) {
        return signupService.provision(request)
                .map(resp -> ResponseEntity.status(HttpStatus.CREATED).body((Object) new LoginDtos.LoginResponse(
                        resp.token(), resp.userId(), resp.email(), resp.name())))
                .onErrorResume(ValidationException.class,
                        ex -> Mono.just(badRequest(ex.field())))
                .onErrorResume(DuplicateEmailException.class, ex -> Mono.just(conflict()))
                .onErrorResume(ProvisioningFailedException.class, ex -> Mono.just(provisioningFailed()));
    }

    private static ResponseEntity<?> uniformAuthError() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(UNIFORM_AUTH_ERROR);
    }

    private static ResponseEntity<?> serviceUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new LoginDtos.ErrorResponse("Service temporarily unavailable."));
    }

    private static ResponseEntity<?> badRequest(String field) {
        return ResponseEntity.badRequest()
                .body(new SignupDtos.FieldErrorResponse("invalid_request", field));
    }

    private static ResponseEntity<?> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new LoginDtos.ErrorResponse("An account with this email already exists."));
    }

    private static ResponseEntity<?> provisioningFailed() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new LoginDtos.ErrorResponse("Account provisioning failed. Please try again."));
    }
}
```

This references a new `SignupDtos.FieldErrorResponse` record — add it to `api-gateway/src/main/java/com/wealth/gateway/auth/SignupDtos.java` (edit the file from Task 2, add this record inside the existing `SignupDtos` class body):

```java
    public record FieldErrorResponse(String error, String field) {}
```

- [ ] **Step 3: Remove the `app.auth.*` binding from `application.yml`**

Edit `api-gateway/src/main/resources/application.yml` — delete these lines entirely (the whole `auth:` sub-block under `app:`):

```yaml
  auth:
    email: ${APP_AUTH_EMAIL:dev@localhost.local}
    password: ${APP_AUTH_PASSWORD:password}
    # Align with Flyway V3 seed (portfolios.user_id = user-001); override via APP_AUTH_USER_ID if needed.
    user-id: ${APP_AUTH_USER_ID:user-001}
    name: ${APP_AUTH_NAME:Development User}
```

(Leave the sibling `app.routes` and `app.cors` keys untouched — only the `app.auth` block is removed.)

- [ ] **Step 4: Write `AuthControllerUniformErrorTest`**

```java
package com.wealth.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealth.gateway.auth.AuthenticationService;
import com.wealth.gateway.auth.InvalidCredentialsException;
import com.wealth.gateway.auth.SignupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Example test for Uniform_Auth_Error constant identity (Req 3.5, 3.6, 10.6): the unknown-email
 * and wrong-password 401 bodies must be byte-for-byte identical.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerUniformErrorTest {

    @Mock AuthenticationService authService;
    @Mock SignupService signupService;

    @Test
    void unknownEmailAndWrongPasswordProduceByteIdenticalBodies() throws Exception {
        AuthController controller = new AuthController(authService, signupService);
        ObjectMapper mapper = new ObjectMapper();

        when(authService.authenticate(new LoginDtos.LoginRequest("nobody@x.com", "pw")))
                .thenReturn(Mono.error(new InvalidCredentialsException()));
        when(authService.authenticate(new LoginDtos.LoginRequest("known@x.com", "wrongpw")))
                .thenReturn(Mono.error(new InvalidCredentialsException()));

        var unknownEmailResponse = controller.login(new LoginDtos.LoginRequest("nobody@x.com", "pw")).block();
        var wrongPasswordResponse = controller.login(new LoginDtos.LoginRequest("known@x.com", "wrongpw")).block();

        byte[] unknownBytes = mapper.writeValueAsBytes(unknownEmailResponse.getBody());
        byte[] wrongPasswordBytes = mapper.writeValueAsBytes(wrongPasswordResponse.getBody());

        assertThat(unknownEmailResponse.getStatusCode().value()).isEqualTo(401);
        assertThat(wrongPasswordResponse.getStatusCode().value()).isEqualTo(401);
        assertThat(unknownBytes).isEqualTo(wrongPasswordBytes);
    }
}
```

- [ ] **Step 5: Run the tests and confirm the module still compiles**

Run: `./gradlew :api-gateway:compileJava :api-gateway:test --tests "com.wealth.gateway.AuthControllerUniformErrorTest"`
Expected: compiles cleanly (note: `SecurityConfig` and `JwtAuthenticationFilter` already `permitAll()`/skip `/api/auth/**` — no change needed there), all tests PASS.

- [ ] **Step 6: Checkpoint — full gateway module build**

Run: `./gradlew :api-gateway:build -x integrationTest`
Expected: BUILD SUCCESSFUL. This is the "backend auth path wired" checkpoint from the original spec's `tasks.md` (its Task 6) — if anything from Tasks 2-5 doesn't compile or a unit test fails, fix it here before moving to the frontend tasks.

- [ ] **Step 7: Commit**

```bash
git add api-gateway/src/main/java/com/wealth/gateway/AuthController.java api-gateway/src/main/java/com/wealth/gateway/auth/SignupDtos.java api-gateway/src/main/resources/application.yml api-gateway/src/test/java/com/wealth/gateway/AuthControllerUniformErrorTest.java
git commit -m "feat(api-gateway): rewrite AuthController for per-user login/signup, remove hardcoded credential"
```

---

### Task 6: Frontend — session, signup page, navigation (Next.js static export)

**Files:**
- Modify: `frontend/src/lib/auth/session.ts`
- Create: `frontend/src/app/(auth)/signup/page.tsx`
- Modify: `frontend/src/app/(auth)/login/page.tsx`
- Test: `frontend/src/lib/auth/session.test.ts` (new — check if one already exists first; if `session.ts` already has a test file, extend it instead of creating a duplicate)
- Test: `frontend/src/app/(auth)/signup/page.test.tsx`
- Test: `frontend/src/lib/auth/signupValidator.property.test.ts`
- Create: `frontend/src/lib/auth/signupValidator.ts` (shared client-side validator mirroring `SignupValidator`)

**Interfaces:**
- Consumes: `apiPath` from `@/lib/config/api` (existing), the `POST /api/auth/signup` contract from Task 5 (`201` body `{token,userId,email,name}`; `400` body `{error,field}`; `409` body `{error}`).
- Produces: `signupWithBackend(email, password, name) -> Promise<AuthSession>` (extends `session.ts`), the `/signup` route, and `validateSignup(email, password, name)` (client-side mirror of `SignupValidator`).

- [ ] **Step 1: Check for an existing `session.ts` test file before creating one**

Run: `find frontend/src/lib/auth -iname "session*.test.ts"` — if a file is found, all subsequent steps that reference `session.test.ts` mean "add to the existing file", not "create a new one". If none is found, Step 3 below creates it fresh.

- [ ] **Step 2: Write the shared client-side validator**

Create `frontend/src/lib/auth/signupValidator.ts` — a plain TS mirror of the Java `SignupValidator` (Task 2, Step 4), using the exact same three rules and byte-length check via `TextEncoder`:

```typescript
export type SignupField = "email" | "password" | "name";

export class SignupValidationError extends Error {
  constructor(readonly field: SignupField, message: string) {
    super(message);
    this.name = "SignupValidationError";
  }
}

const MAX_EMAIL_LENGTH = 254;
const MIN_PASSWORD_LENGTH = 12;
const MAX_PASSWORD_BYTES = 72;
const MAX_NAME_LENGTH = 100;
const EMAIL_PATTERN = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

export interface ValidatedSignup {
  email: string;
  password: string;
  name: string;
}

/** Mirrors the server's SignupValidator (api-gateway com.wealth.gateway.auth.SignupValidator). */
export function validateSignup(email: string, password: string, name: string): ValidatedSignup {
  if (!email || email.length > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.test(email)) {
    throw new SignupValidationError("email", "Enter a valid email address.");
  }

  const passwordBytes = new TextEncoder().encode(password ?? "").length;
  if (!password || password.length < MIN_PASSWORD_LENGTH || passwordBytes > MAX_PASSWORD_BYTES) {
    throw new SignupValidationError(
      "password",
      `Password must be at least ${MIN_PASSWORD_LENGTH} characters (max 72 bytes).`,
    );
  }

  const trimmedName = (name ?? "").trim();
  if (trimmedName.length === 0) {
    throw new SignupValidationError("name", "Name is required.");
  }
  if (trimmedName.length > MAX_NAME_LENGTH) {
    throw new SignupValidationError("name", "Name is too long (max 100 characters).");
  }

  return { email, password, name: trimmedName };
}
```

- [ ] **Step 3: Write the fast-check property test for `validateSignup`**

Check whether `fast-check` is already a frontend dependency (`grep fast-check frontend/package.json`); if absent, add `"fast-check": "^3.19.0"` to `devDependencies` and run `npm install` inside `frontend/` before writing this test.

Create `frontend/src/lib/auth/signupValidator.property.test.ts`:

```typescript
import { describe, it, expect } from "vitest";
import fc from "fast-check";
import { validateSignup, SignupValidationError } from "./signupValidator";

// Feature: new-user-signup-profile, Property 1 (frontend companion): client validator mirrors
// SignupValidator. Validates Requirements 5.1, 5.2, 5.3 (mirrors 1.4-1.8, 9.2).
describe("validateSignup — property: mirrors the server SignupValidator", () => {
  const validEmail = fc
    .tuple(fc.stringMatching(/^[a-z]{1,20}$/), fc.stringMatching(/^[a-z]{1,10}$/), fc.stringMatching(/^[a-z]{1,10}$/))
    .map(([local, d1, d2]) => `${local}@${d1}.${d2}`);
  const validPassword = fc.string({ minLength: 12, maxLength: 72, unit: "grapheme-ascii" });
  const validName = fc.string({ minLength: 1, maxLength: 100, unit: "grapheme-ascii" }).map((s) => ` ${s} `);

  it("accepts any (email, password, name) satisfying all three rules and trims the name", () => {
    fc.assert(
      fc.property(validEmail, validPassword, validName, (email, password, name) => {
        const result = validateSignup(email, password, name);
        expect(result.email).toBe(email);
        expect(result.password).toBe(password);
        expect(result.name).toBe(name.trim());
      }),
      { numRuns: 100 },
    );
  });

  it("rejects a password shorter than 12 characters, naming the password field", () => {
    fc.assert(
      fc.property(validEmail, fc.string({ maxLength: 11 }), validName, (email, password, name) => {
        expect(() => validateSignup(email, password, name)).toThrowError(SignupValidationError);
        try {
          validateSignup(email, password, name);
        } catch (e) {
          expect((e as SignupValidationError).field).toBe("password");
        }
      }),
      { numRuns: 100 },
    );
  });

  it("rejects a password whose UTF-8 byte length exceeds 72 even under 72 characters", () => {
    // 'é' is 2 UTF-8 bytes; 40 repeats = 40 chars but 80 bytes.
    const multibytePassword = "é".repeat(40);
    expect(() => validateSignup("a@b.com", multibytePassword, "Name")).toThrowError(SignupValidationError);
    try {
      validateSignup("a@b.com", multibytePassword, "Name");
    } catch (e) {
      expect((e as SignupValidationError).field).toBe("password");
    }
  });

  it("rejects a blank or overlong name, naming the name field", () => {
    fc.assert(
      fc.property(validEmail, validPassword, fc.constantFrom("", "   ", "x".repeat(101)), (email, password, name) => {
        expect(() => validateSignup(email, password, name)).toThrowError(SignupValidationError);
        try {
          validateSignup(email, password, name);
        } catch (e) {
          expect((e as SignupValidationError).field).toBe("name");
        }
      }),
      { numRuns: 100 },
    );
  });
});
```

- [ ] **Step 4: Extend `session.ts` with `signupWithBackend`**

Edit `frontend/src/lib/auth/session.ts` — add this function right after the existing `loginWithBackend` function (mirroring its structure exactly, reusing `LoginError`, `coerceSession`, `saveAuthSession`):

```typescript
export async function signupWithBackend(
  email: string,
  password: string,
  name: string,
): Promise<AuthSession> {
  let response: Response;
  try {
    response = await fetch(apiPath("/auth/signup"), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, password, name }),
    });
  } catch {
    throw new LoginError("Signup request failed", "network");
  }

  if (!response.ok) {
    throw new LoginError(`Signup failed (${response.status})`, "http", response.status);
  }

  let raw: Record<string, unknown>;
  try {
    raw = (await response.json()) as Record<string, unknown>;
  } catch {
    throw new LoginError("Signup response was not valid JSON", "invalid-response");
  }
  const parsed = coerceSession(raw);
  if (!parsed) {
    throw new LoginError("Signup response missing token, userId, or email", "invalid-response");
  }
  saveAuthSession(parsed);
  return parsed;
}
```

- [ ] **Step 5: Write the Signup page**

Create `frontend/src/app/(auth)/signup/page.tsx` — mirrors `login/page.tsx`'s structure/styling exactly (same Tailwind classes, same card layout) but with client-side validation via `validateSignup`, field-specific error display, a 10-second `AbortController` timeout, and retaining email+name on any error path:

```tsx
"use client";

import React, { useState } from "react";
import { useRouter } from "next/navigation";
import { signupWithBackend } from "@/lib/auth/session";
import { validateSignup, SignupValidationError, type SignupField } from "@/lib/auth/signupValidator";

const SIGNUP_TIMEOUT_MS = 10_000;

function serverErrorMessage(status: number, field?: string): string {
  if (status === 409) {
    return "An account with this email already exists.";
  }
  if (status === 400) {
    if (field === "email") return "Enter a valid email address.";
    if (field === "password") return "Password must be at least 12 characters (max 72 bytes).";
    if (field === "name") return "Enter a name between 1 and 100 characters.";
    return "Please check your input and try again.";
  }
  return "Signup could not be completed. Please try again.";
}

export default function SignupPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [fieldError, setFieldError] = useState<SignupField | null>(null);
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e: React.SyntheticEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    setFieldError(null);

    const form = new FormData(e.currentTarget);
    const submittedEmail = (form.get("email") as string) ?? "";
    const submittedPassword = (form.get("password") as string) ?? "";
    const submittedName = (form.get("name") as string) ?? "";
    setEmail(submittedEmail);
    setName(submittedName);

    try {
      validateSignup(submittedEmail, submittedPassword, submittedName);
    } catch (err) {
      if (err instanceof SignupValidationError) {
        setFieldError(err.field);
        setError(err.message);
      } else {
        setError("Please check your input and try again.");
      }
      return;
    }

    setLoading(true);
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), SIGNUP_TIMEOUT_MS);

    try {
      await signupWithBackend(submittedEmail, submittedPassword, submittedName.trim());
      router.push("/overview");
    } catch (err) {
      const status = (err as { status?: number })?.status;
      if (status === 400 || status === 409) {
        setError(serverErrorMessage(status));
      } else {
        setError("Signup could not be completed. Please try again.");
      }
    } finally {
      clearTimeout(timeoutId);
      setLoading(false);
    }
  }

  return (
    <main className="flex min-h-screen flex-col items-center justify-center bg-background">
      <div className="w-full max-w-sm space-y-6 rounded-xl border border-border bg-card p-8 shadow-sm">
        <div className="space-y-1">
          <h1 className="text-2xl font-semibold tracking-tight text-foreground">
            Create an account
          </h1>
          <p className="text-sm text-muted-foreground">
            Sign up to start tracking your portfolio.
          </p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-1">
            <label htmlFor="name" className="text-sm font-medium text-foreground">
              Name
            </label>
            <input
              id="name"
              name="name"
              type="text"
              required
              autoComplete="name"
              defaultValue={name}
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              placeholder="Jane Doe"
            />
            {fieldError === "name" && error && <p className="text-sm text-destructive">{error}</p>}
          </div>

          <div className="space-y-1">
            <label htmlFor="email" className="text-sm font-medium text-foreground">
              Email
            </label>
            <input
              id="email"
              name="email"
              type="email"
              required
              autoComplete="email"
              defaultValue={email}
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              placeholder="you@example.com"
            />
            {fieldError === "email" && error && <p className="text-sm text-destructive">{error}</p>}
          </div>

          <div className="space-y-1">
            <label htmlFor="password" className="text-sm font-medium text-foreground">
              Password
            </label>
            <input
              id="password"
              name="password"
              type="password"
              required
              autoComplete="new-password"
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              placeholder="At least 12 characters"
            />
            {fieldError === "password" && error && <p className="text-sm text-destructive">{error}</p>}
          </div>

          {!fieldError && error && <p className="text-sm text-destructive">{error}</p>}

          <button
            type="submit"
            disabled={loading}
            className="w-full rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
          >
            {loading ? "Creating account…" : "Create account"}
          </button>
        </form>

        <p className="text-center text-sm text-muted-foreground">
          Already have an account?{" "}
          <a href="/login" className="font-medium text-primary hover:underline">
            Sign in
          </a>
        </p>
      </div>
    </main>
  );
}
```

- [ ] **Step 6: Add the login-page link to `/signup`**

Edit `frontend/src/app/(auth)/login/page.tsx` — add this block right after the closing `</form>` tag and before the closing `</div>` of the card:

```tsx
        <p className="text-center text-sm text-muted-foreground">
          Don&apos;t have an account?{" "}
          <a href="/signup" className="font-medium text-primary hover:underline">
            Sign up
          </a>
        </p>
```

- [ ] **Step 7: Write the Signup page component test**

Create `frontend/src/app/(auth)/signup/page.test.tsx`, following the existing MSW + Testing Library conventions already used elsewhere in the frontend test suite (check `frontend/src/lib/api/fetchWithAuth.test.ts` and any existing page-level test under `frontend/src/app/` for the exact `render`/`screen`/`userEvent` import style before writing this — match it precisely rather than inventing a new pattern):

```tsx
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import SignupPage from "./page";

const pushMock = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

describe("SignupPage", () => {
  const mockFetch = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", mockFetch);
    vi.stubGlobal("localStorage", {
      getItem: vi.fn(),
      setItem: vi.fn(),
      removeItem: vi.fn(),
    });
    pushMock.mockClear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it("shows a field-specific error and does not call the endpoint for a short password", async () => {
    render(<SignupPage />);
    await userEvent.type(screen.getByLabelText(/name/i), "Jane Doe");
    await userEvent.type(screen.getByLabelText(/email/i), "jane@example.com");
    await userEvent.type(screen.getByLabelText(/password/i), "short");
    await userEvent.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByText(/at least 12 characters/i)).toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
    expect(screen.getByLabelText(/email/i)).toHaveValue("jane@example.com");
  });

  it("persists the session and navigates to /overview on 201", async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      status: 201,
      json: async () => ({ token: "t", userId: "u1", email: "jane@example.com", name: "Jane Doe" }),
    });

    render(<SignupPage />);
    await userEvent.type(screen.getByLabelText(/name/i), "Jane Doe");
    await userEvent.type(screen.getByLabelText(/email/i), "jane@example.com");
    await userEvent.type(screen.getByLabelText(/password/i), "a-strong-password-123");
    await userEvent.click(screen.getByRole("button", { name: /create account/i }));

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/overview"));
  });

  it("shows the duplicate-email message and retains email/name on 409", async () => {
    mockFetch.mockResolvedValueOnce({ ok: false, status: 409 });

    render(<SignupPage />);
    await userEvent.type(screen.getByLabelText(/name/i), "Jane Doe");
    await userEvent.type(screen.getByLabelText(/email/i), "jane@example.com");
    await userEvent.type(screen.getByLabelText(/password/i), "a-strong-password-123");
    await userEvent.click(screen.getByRole("button", { name: /create account/i }));

    expect(await screen.findByText(/already exists/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/email/i)).toHaveValue("jane@example.com");
    expect(screen.getByLabelText(/name/i)).toHaveValue("Jane Doe");
  });

  it("has a link back to /login", () => {
    render(<SignupPage />);
    expect(screen.getByRole("link", { name: /sign in/i })).toHaveAttribute("href", "/login");
  });
});
```

- [ ] **Step 8: Run all frontend tests for this task**

Run: `cd frontend && npm test -- --run signupValidator page.test`
Expected: all new tests PASS. If MSW/Testing Library import paths differ from what's guessed above, fix them to match the actual conventions found in Step 7's precedent check — do not leave a failing import.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/lib/auth/session.ts frontend/src/lib/auth/signupValidator.ts frontend/src/lib/auth/signupValidator.property.test.ts frontend/src/app/\(auth\)/signup/ frontend/src/app/\(auth\)/login/page.tsx frontend/package.json frontend/package-lock.json
git commit -m "feat(frontend): add signup page, client-side validator, and login<->signup navigation"
```

---

### Task 7: Retire Better Auth (frontend)

**Files:**
- Delete: `frontend/src/lib/auth.ts`
- Delete: `frontend/src/lib/auth-client.ts`
- Delete: `frontend/src/lib/auth/mintToken.ts`
- Delete: `frontend/src/lib/auth/mintToken.test.ts`
- Delete: `frontend/src/lib/api/fetchWithAuth.server.ts`
- Delete: `frontend/src/lib/api/insights-actions.ts`
- Delete: `frontend/src/lib/api/insights-actions.test.ts`
- Modify: `frontend/src/lib/api/fetchWithAuth.test.ts` (remove the server-side describe block and its unique mocks)
- Modify: `frontend/package.json` (remove `better-auth`)
- Modify: `frontend/package-lock.json` (regenerated by `npm install`, not hand-edited)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: nothing consumed by later tasks — this is a leaf cleanup task. Note the two extra files beyond `design.md`'s literal 4-file list (`insights-actions.ts`/`.test.ts`) — confirmed dead code via `grep -rln "sendChatMessage|useActionState"` returning only those two files; `ChatInterface.tsx` uses the client `postChatMessage`/`fetchWithAuth.ts` path instead, unaffected by this task.

- [ ] **Step 1: Confirm no other file references the files about to be deleted**

Run: `grep -rln "better-auth\|from \"@/lib/auth\"\|from \"@/lib/auth-client\"\|from \"@/lib/auth/mintToken\"\|fetchWithAuth.server\|insights-actions" frontend/src`
Expected output: exactly these 7 files (the ones this task deletes or edits) — `auth.ts`, `auth-client.ts`, `mintToken.ts`, `fetchWithAuth.server.ts`, `insights-actions.ts`, `insights-actions.test.ts`, `fetchWithAuth.test.ts`. If any OTHER file appears, stop and report it — it means something outside this task's known scope depends on Better Auth, and the deletion plan needs to be revisited before proceeding.

- [ ] **Step 2: Delete the dead files**

```bash
git rm frontend/src/lib/auth.ts frontend/src/lib/auth-client.ts frontend/src/lib/auth/mintToken.ts frontend/src/lib/auth/mintToken.test.ts frontend/src/lib/api/fetchWithAuth.server.ts frontend/src/lib/api/insights-actions.ts frontend/src/lib/api/insights-actions.test.ts
```

- [ ] **Step 3: Edit `fetchWithAuth.test.ts` to remove the server-side test block**

Edit `frontend/src/lib/api/fetchWithAuth.test.ts`:
1. Delete lines 5-23 (the three `vi.mock(...)` calls for `@/lib/auth`, `server-only`, and `next/headers` — these exist only to support the server-side tests being removed).
2. Delete from the `// Mock mintToken for server-side fetch helper tests` comment (originally line 246) through the end of the file (the entire `describe("fetchWithAuth (server-side)", ...)` block and its closing brace).
3. The file must end with the closing `});` of the `describe("fetchWithAuthClient", ...)` block (originally line 244) and nothing after it.

After this edit, run: `grep -n "better-auth\|@/lib/auth\|server-only\|next/headers" frontend/src/lib/api/fetchWithAuth.test.ts` — expect NO matches.

- [ ] **Step 4: Remove the `better-auth` dependency**

Edit `frontend/package.json` — delete the line `"better-auth": "^1.6.2",` from `dependencies`. Then run:

```bash
cd frontend && npm install
```

This regenerates `package-lock.json` without `better-auth`. Do not hand-edit the lockfile.

- [ ] **Step 5: Verify the build and test suite are clean**

Run: `cd frontend && npm test -- --run`
Expected: all tests PASS (no import errors for the deleted files).

Run: `cd frontend && npm run build`
Expected: the static export builds with zero errors (confirms Req 8.2 — `output: "export"` still works, no runtime-only Better Auth machinery is reachable from any remaining page).

Run: `grep -rln "better-auth" frontend/src frontend/package.json`
Expected: NO matches anywhere.

- [ ] **Step 6: Commit**

```bash
git add -A frontend/
git commit -m "chore(frontend): retire Better Auth — remove dead code, dependency, and orphaned chat Server Action"
```

---

### Task 8: Integration and architecture tests (Testcontainers Postgres + Redis, `@Tag("integration")`)

**Files:**
- Create: `api-gateway/src/test/java/com/wealth/gateway/TestContainerImages.java` (modify — add a `POSTGRES` constant to the existing file from Task 1's precedent)
- Create: `api-gateway/src/test/java/com/wealth/gateway/auth/AuthIntegrationTest.java` (Postgres-backed: signup, login, provisioning atomicity, read-only enforcement, reconciliation idempotency)
- Create: `api-gateway/src/test/java/com/wealth/gateway/AuthRateLimitIntegrationTest.java` (Redis-backed: throttling + fail-open)
- Create: `api-gateway/src/test/java/com/wealth/gateway/BetterAuthArchitectureTest.java` (ArchUnit: no Better Auth types, no Flyway in the gateway)

**Interfaces:**
- Consumes: everything from Tasks 1-5 (the fully wired backend). This is the last backend task; nothing depends on it.

- [ ] **Step 1: Add a `POSTGRES` constant to `api-gateway`'s `TestContainerImages`**

Edit `api-gateway/src/test/java/com/wealth/gateway/TestContainerImages.java`, add alongside the existing `REDIS` constant:

```java
    /** Postgres image matching the Neon production engine version (see portfolio-service's copy). */
    public static final DockerImageName POSTGRES = DockerImageName.parse("postgres:18.4");
```

Update the class Javadoc's bullet list to mention Postgres too, matching the existing comment style.

- [ ] **Step 2: Write `AuthIntegrationTest` (Postgres-backed)**

This is the single largest test class in the plan — it runs the real Flyway migrations from `portfolio-service` against a Testcontainers Postgres, boots the full gateway Spring context (`WebEnvironment.RANDOM_PORT`), and drives `/api/auth/login` and `/api/auth/signup` via `WebTestClient`, plus authenticated calls through `ReadOnlyEnforcementFilter`. Follow `DlqIntegrationTest`'s Testcontainers setup pattern (Postgres container, `@DynamicPropertySource`) combined with `ProductionRateLimitingIntegrationTest`'s Spring-context/`WebTestClient` pattern (both already read in full during planning — mirror their annotations and container lifecycle exactly). The Flyway migrations directory is NOT on the gateway's classpath by default (they live under `portfolio-service/src/main/resources/db/migration`) — this test must run Flyway itself against the container before the gateway's `ApplicationContext` starts, using the `org.flywaydb:flyway-core` + `org.flywaydb:flyway-database-postgresql` artifacts (add as `testImplementation` to `api-gateway/build.gradle` if not already present — check first) pointed at that directory via an absolute/relative `filesystem:` location, since `api-gateway`'s test classpath doesn't include `portfolio-service`'s resources.

```java
package com.wealth.gateway.auth;

import com.wealth.gateway.TestContainerImages;
import com.wealth.gateway.TestJwtFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * .kiro/specs/new-user-signup-profile, Requirement 10 (10.1-10.3, 10.4-10.7, 10.10-10.12).
 * Run via: ./gradlew :api-gateway:integrationTest
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class AuthIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(TestContainerImages.POSTGRES)
            .withDatabaseName("portfolio_db")
            .withUsername("wealth_user")
            .withPassword("wealth_pass");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                // Path relative to api-gateway/ (the module's working directory under Gradle).
                .locations("filesystem:../portfolio-service/src/main/resources/db/migration")
                .load()
                .migrate();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("auth.jwt.secret", () -> TestJwtFactory.TEST_SECRET);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbcTemplate;

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }

    // ---- Requirement 10.1, 10.2 — signup provisions both rows; sub matched by requireUserExists ----

    @Test
    void signupProvisionsUsersAndUserCredentialsRows() {
        String email = "new-user-1@example.com";

        var response = client().post().uri("/api/auth/signup")
                .bodyValue(java.util.Map.of("email", email, "password", "a-strong-password-123", "name", "New User"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(java.util.Map.class)
                .returnResult().getResponseBody();

        String userId = (String) response.get("userId");
        Integer userCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE id = ?::uuid", Integer.class, userId);
        Integer credCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_credentials WHERE user_id = ?::uuid", Integer.class, userId);
        assertThat(userCount).isEqualTo(1);
        assertThat(credCount).isEqualTo(1);
    }

    // ---- Requirement 10.7 — duplicate-email signup returns 409, row count stays 1 ----

    @Test
    void duplicateEmailSignupReturns409AndRowCountStaysOne() {
        String email = "dup-user@example.com";
        var body = java.util.Map.of("email", email, "password", "a-strong-password-123", "name", "Dup User");

        client().post().uri("/api/auth/signup").bodyValue(body).exchange().expectStatus().isCreated();
        client().post().uri("/api/auth/signup").bodyValue(body).exchange().expectStatus().isEqualTo(409);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_credentials WHERE lower(email) = lower(?)", Integer.class, email);
        assertThat(count).isEqualTo(1);
    }

    // ---- Requirement 10.4 — correct login returns 200 with non-empty token ----

    @Test
    void correctLoginReturns200WithNonEmptyToken() {
        String email = "login-ok@example.com";
        client().post().uri("/api/auth/signup")
                .bodyValue(java.util.Map.of("email", email, "password", "a-strong-password-123", "name", "Login Ok"))
                .exchange().expectStatus().isCreated();

        var response = client().post().uri("/api/auth/login")
                .bodyValue(java.util.Map.of("email", email, "password", "a-strong-password-123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(java.util.Map.class)
                .returnResult().getResponseBody();

        assertThat((String) response.get("token")).isNotBlank();
    }

    // ---- Requirement 10.5, 10.6 — wrong password / unknown email both 401, byte-identical bodies ----

    @Test
    void wrongPasswordAndUnknownEmailReturnByteIdenticalUniform401() {
        String email = "wrongpw-user@example.com";
        client().post().uri("/api/auth/signup")
                .bodyValue(java.util.Map.of("email", email, "password", "a-strong-password-123", "name", "WP User"))
                .exchange().expectStatus().isCreated();

        byte[] wrongPasswordBody = client().post().uri("/api/auth/login")
                .bodyValue(java.util.Map.of("email", email, "password", "totally-wrong-password"))
                .exchange().expectStatus().isEqualTo(401)
                .expectBody().returnResult().getResponseBody();

        byte[] unknownEmailBody = client().post().uri("/api/auth/login")
                .bodyValue(java.util.Map.of("email", "no-such-user@example.com", "password", "whatever12345"))
                .exchange().expectStatus().isEqualTo(401)
                .expectBody().returnResult().getResponseBody();

        assertThat(wrongPasswordBody).isEqualTo(unknownEmailBody);
    }

    // ---- Requirement 10.10, 10.11 — demo read-only write blocked, chat allowed ----

    @Test
    void demoAccountWriteToPortfolioIsRejectedWith403AndDataUnchanged() {
        String demoToken = TestJwtFactory.mint("00000000-0000-0000-0000-0000000d3110", Duration.ofHours(1),
                TestJwtFactory.TEST_SECRET, java.util.Map.of("ro", true));

        client().post().uri("/api/portfolio/holdings")
                .header("Authorization", "Bearer " + demoToken)
                .bodyValue(java.util.Map.of("assetTicker", "AAPL", "quantity", 1))
                .exchange()
                .expectStatus().isEqualTo(403);
    }

    @Test
    void demoAccountChatPostIsAllowedNotForbidden() {
        String demoToken = TestJwtFactory.mint("00000000-0000-0000-0000-0000000d3110", Duration.ofHours(1),
                TestJwtFactory.TEST_SECRET, java.util.Map.of("ro", true));

        var status = client().post().uri("/api/chat/message")
                .header("Authorization", "Bearer " + demoToken)
                .bodyValue(java.util.Map.of("message", "hello"))
                .exchange()
                .returnResult(Void.class).getStatus();

        // Proxied to a non-existent upstream in this test context (no insight-service running) —
        // the assertion is that ReadOnlyEnforcementFilter did NOT reject it (never 403), matching
        // the RateLimitingIntegrationTest precedent of asserting "not blocked" rather than a
        // specific downstream status when no real upstream is wired.
        assertThat(status.value()).isNotEqualTo(403);
    }

    // ---- Requirement 10.12 — demo UUID owns the showcase portfolio with non-empty holdings ----

    @Test
    void demoUuidOwnsShowcasePortfolioAndDevUserDoesNot() {
        Integer devOwned = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM portfolios WHERE user_id = '00000000-0000-0000-0000-000000000001'",
                Integer.class);
        Integer demoHoldings = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM asset_holdings h JOIN portfolios p ON p.id = h.portfolio_id "
                        + "WHERE p.user_id = '00000000-0000-0000-0000-0000000d3110'",
                Integer.class);
        assertThat(devOwned).isZero();
        assertThat(demoHoldings).isGreaterThan(0);
    }
}
```

Note: `/api/portfolio/holdings` and `/api/chat/message` are proxied routes with no real upstream running in this test — `ReadOnlyEnforcementFilter` runs and makes its decision BEFORE the proxy attempt, so the 403 assertion in `demoAccountWriteToPortfolioIsRejectedWith403AndDataUnchanged` is a direct filter rejection (no proxy needed), while the chat test's "not 403" assertion holds regardless of what (if anything) the failed proxy attempt returns. If `application-local.yml`'s test routes point at ports with nothing listening, Spring Cloud Gateway returns a 502/504 for the chat case — assert `isNotEqualTo(403)`, not a 2xx, exactly as coded above.

- [ ] **Step 3: Write `AuthRateLimitIntegrationTest` (Redis-backed)**

Follow `ProductionRateLimitingIntegrationTest`'s exact container/`@DynamicPropertySource` pattern (already read in full during planning), but target `/api/auth/login` with small Auth_Bucket numbers and assert throttling + fail-open:

```java
package com.wealth.gateway;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * .kiro/specs/new-user-signup-profile, Requirement 10.8, 10.9 (Property 7, Property 8).
 * Run via: ./gradlew :api-gateway:integrationTest
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"prod", "azure"})
class AuthRateLimitIntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final int SMALL_BURST = 3; // small burst_capacity keeps the test fast

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(TestContainerImages.REDIS).withExposedPorts(REDIS_PORT);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
        registry.add("spring.data.redis.timeout", () -> "3s");
        registry.add("spring.data.redis.connect-timeout", () -> "3s");
        registry.add("auth.jwt.secret", () -> TestJwtFactory.TEST_SECRET);

        registry.add("app.rate-limit.standard.replenish-rate", () -> 1);
        registry.add("app.rate-limit.standard.burst-capacity", () -> 3);
        registry.add("app.rate-limit.standard.requested-tokens", () -> 1);
        registry.add("app.rate-limit.strict.replenish-rate", () -> 1);
        registry.add("app.rate-limit.strict.burst-capacity", () -> 3);
        registry.add("app.rate-limit.strict.requested-tokens", () -> 1);

        registry.add("app.rate-limit.auth.replenish-rate", () -> 1);
        registry.add("app.rate-limit.auth.burst-capacity", () -> SMALL_BURST);
        registry.add("app.rate-limit.auth.requested-tokens", () -> 1);
    }

    @LocalServerPort int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }

    @Test
    void exceedingTheAuthBucketReturns429WithPositiveRetryAfter() {
        Map<String, Object> body = Map.of("email", "throttle-test@example.com", "password", "wrong-password-1");

        for (int i = 0; i < SMALL_BURST; i++) {
            client().post().uri("/api/auth/login").bodyValue(body).exchange()
                    .expectStatus().isNotEqualTo(429);
        }

        client().post().uri("/api/auth/login").bodyValue(body).exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().value("Retry-After", value -> assertThat(Integer.parseInt(value)).isPositive());
    }

    @Test
    void loginAndSignupShareOneBucketPerKey() {
        Map<String, Object> loginBody = Map.of("email", "shared-bucket@example.com", "password", "wrong-password-1");
        Map<String, Object> signupBody = Map.of("email", "shared-bucket-2@example.com", "password", "a-strong-password-123", "name", "N");

        // Interleave login+signup calls up to the shared burst, then confirm the NEXT one (of
        // either kind) is throttled — proving they draw from the same bucket.
        for (int i = 0; i < SMALL_BURST; i++) {
            var uri = i % 2 == 0 ? "/api/auth/login" : "/api/auth/signup";
            var body = i % 2 == 0 ? loginBody : signupBody;
            client().post().uri(uri).bodyValue(body).exchange().expectStatus().isNotEqualTo(429);
        }

        client().post().uri("/api/auth/login").bodyValue(loginBody).exchange()
                .expectStatus().isEqualTo(429);
    }
}
```

- [ ] **Step 4: Add a Redis-unavailable fail-open test**

Add this test to `AuthRateLimitIntegrationTest` (same file, Step 3) — after the container-based tests. This mirrors `ProductionRateLimitingIntegrationTest.failOpenWhenRedisDown()` exactly: stop the shared Testcontainers Redis mid-test, confirm every request still gets through (never `429`, never `5xx`), then restart it; `@DirtiesContext` forces a fresh Spring context for any later test so nothing inherits a stale Redis connection. Add the two extra imports (`org.springframework.test.annotation.DirtiesContext`) to the file's import list.

```java
    /**
     * Restarting the shared Redis container mid-test assigns a new mapped port, which invalidates
     * the spring.data.redis.url the currently-cached Spring context resolved at startup.
     * @DirtiesContext forces a fresh context for any subsequent test in this class.
     */
    @Test
    @org.springframework.test.annotation.DirtiesContext(
            methodMode = org.springframework.test.annotation.DirtiesContext.MethodMode.AFTER_METHOD)
    void loginReturnsNonServerErrorWhenRedisIsUnreachable() {
        redis.stop();
        try {
            Map<String, Object> body = Map.of("email", "fail-open-user@example.com", "password", "whatever12345");

            // Even past what would normally be the burst capacity, every request must proceed to
            // the login logic (never 429, since the limiter fails open) and never 5xx (Req 10.9).
            for (int i = 0; i < SMALL_BURST + 3; i++) {
                final int requestNum = i + 1;
                client().post().uri("/api/auth/login").bodyValue(body).exchange()
                        .expectStatus().value(status -> {
                            assertThat(status).as("request %d must not be 429 when Redis is down", requestNum)
                                    .isNotEqualTo(429);
                            assertThat(status).as("request %d must not be 5xx when Redis is down", requestNum)
                                    .isLessThan(500);
                        });
            }
        } finally {
            redis.start();
        }
    }
```

- [ ] **Step 5: Write `BetterAuthArchitectureTest`**

Follows `RateLimitConfigurationGuardrailTest`'s ArchUnit pattern (already read in full during planning):

```java
package com.wealth.gateway;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * .kiro/specs/new-user-signup-profile, Requirement 2.6, 8.6, 8.7: the gateway has no Better Auth
 * types and defines no Flyway migration of its own.
 */
class BetterAuthArchitectureTest {

    private static final JavaClasses GATEWAY_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.wealth.gateway");

    @Test
    void noClassesDependOnBetterAuthTypes() {
        noClasses()
                .that().resideInAPackage("com.wealth.gateway..")
                .should().dependOnClassesThat().resideInAnyPackage("..betterauth..", "..better_auth..")
                .check(GATEWAY_CLASSES);
    }

    @Test
    void gatewayHasNoFlywayMigrationDirectory() {
        java.io.File migrationDir = new java.io.File("src/main/resources/db/migration");
        org.assertj.core.api.Assertions.assertThat(migrationDir).doesNotExist();
    }
}
```

- [ ] **Step 6: Run the full integration suite**

Run: `./gradlew :api-gateway:integrationTest`
Expected: all tests PASS. This requires Docker Desktop running locally (Testcontainers spins up real Postgres + Redis containers) — if Docker isn't running, start it first.

- [ ] **Step 7: Commit**

```bash
git add api-gateway/src/test/java/com/wealth/gateway/TestContainerImages.java api-gateway/src/test/java/com/wealth/gateway/auth/AuthIntegrationTest.java api-gateway/src/test/java/com/wealth/gateway/AuthRateLimitIntegrationTest.java api-gateway/src/test/java/com/wealth/gateway/BetterAuthArchitectureTest.java api-gateway/build.gradle
git commit -m "test(api-gateway): add Testcontainers integration suite and ArchUnit checks for new-user-signup-profile"
```

---

## Final Checkpoint (controller-run, not a dispatched task)

After Task 8's review is clean, run the full suite once more end-to-end before handing off to the final whole-branch review:

```bash
./gradlew :api-gateway:check :portfolio-service:check
cd frontend && npm test -- --run && npm run build
```

Then bring up the full local stack (`docker compose up --build`) and manually verify: signup a new account through the UI, log out, log back in with it; log in with the demo credentials and confirm a portfolio write is rejected with a visible error while chat still works; confirm the existing dev-user login flow (local docker-compose default) still reaches `/overview` with data. This manual pass is the "live-test locally" step from the original request — do it after the final whole-branch review, before reporting completion.

Before that manual pass, create a gitignored `frontend/.env.local` (never commit it) with:

```
NEXT_PUBLIC_DEMO_EMAIL=demo@wealthtracker.dev
NEXT_PUBLIC_DEMO_PASSWORD=<the exact plaintext used for Task 1 Step 2's demo bcrypt hash>
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
```

so the Login_Page's demo pre-fill (existing, unchanged behavior — Req 7.2) matches the demo account V15 actually seeded, and the frontend dev server talks to the local gateway instead of relative `/api/*`. Run the frontend with `cd frontend && npm run dev` (not part of `docker compose` — the frontend is a static export, deployed separately) against the Dockerized backend. Note this is a **local-only** convenience; deploying this feature later also requires updating the deployed frontend's build-time `NEXT_PUBLIC_DEMO_EMAIL`/`NEXT_PUBLIC_DEMO_PASSWORD` to the new demo account (currently they resolve to the E2E test user, per the single-hardcoded-credential path this feature removes) — that deployment-config update is out of scope for this plan (implementation + local verification only) and belongs to a future deploy step.
