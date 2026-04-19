# MongoDB localhost:27017 RCA — Execution Task List

**Service:** `market-data-service` (AWS Lambda, `prod,aws` profile)  
**Date:** 2026-04-19  
**Branch:** `architecture/cloud-native-extraction`

---

## Root Cause Summary

The Lambda is connecting to `localhost:27017` because `SPRING_DATA_MONGODB_URI` is empty (missing/blank GitHub secret `MONGODB_CONNECTION_STRING`). Spring Boot's `MongoProperties` silently falls back to `localhost:27017` when the URI resolves to empty. The 30-second default `serverSelectionTimeout` then blocks `StartupHydrationService` during `SpringApplication.run()`, preventing the actuator from starting and causing LWA to log `app is not ready after Nms` repeatedly.

```
GitHub secret MONGODB_CONNECTION_STRING missing/blank
  → TF_VAR_mongodb_connection_string = ""
    → Lambda env SPRING_DATA_MONGODB_URI = ""
      → spring.data.mongodb.uri resolves to empty
        → MongoProperties fallback: localhost:27017
          → Cluster monitor: ConnectException (Connection refused)
          → StartupHydrationService.findAll() blocks 30 s
            → LWA readiness probe times out
```

---

## Pre-Work (Required First)

### [ ] P0 — Restore GitHub Secret

1. In the GitHub repository → **Settings → Secrets and variables → Actions**, verify `MONGODB_CONNECTION_STRING` is present and contains the Atlas URI (`mongodb+srv://...`).
2. If missing or blank, repopulate it from `.env.secrets` or the Atlas connection string page.
3. Trigger a manual run of `.github/workflows/terraform.yml` (or push to main) to re-apply `aws_lambda_function.market_data` with the corrected env var.
4. Verify via AWS CLI after apply:
   ```bash
   aws lambda get-function-configuration \
     --function-name wealth-market-data-service \
     --query 'Environment.Variables.SPRING_DATA_MONGODB_URI'
   ```
   Must return the Atlas URI, not an empty string.

---

## Code Changes

### [ ] 1 — Terraform: add URI validation to both variable declarations

Fails `terraform plan` immediately when the secret is blank, instead of failing at Lambda runtime.

**File: `infrastructure/terraform/variables.tf`** (lines 103-107)

```hcl
variable "mongodb_connection_string" {
  type        = string
  sensitive   = true
  description = "MongoDB Atlas URI"
  validation {
    condition     = can(regex("^mongodb(\\+srv)?://", var.mongodb_connection_string))
    error_message = "mongodb_connection_string must be a valid mongodb:// or mongodb+srv:// URI."
  }
}
```

**File: `infrastructure/terraform/modules/compute/variables.tf`** (lines 76-79)

```hcl
variable "mongodb_connection_string" {
  type      = string
  sensitive = true
  validation {
    condition     = can(regex("^mongodb(\\+srv)?://", var.mongodb_connection_string))
    error_message = "mongodb_connection_string must be a valid mongodb:// or mongodb+srv:// URI."
  }
}
```

---

### [ ] 2 — Isolate the localhost fallback to the `local` profile only

Currently the `localhost:27017` fallback in `application.yml` silently masks any missing env var in all profiles. Moving it to `application-local.yml` (loaded only when `SPRING_PROFILES_ACTIVE=local`) ensures non-local deployments fail loudly.

**File: `market-data-service/src/main/resources/application.yml`** — edit line 8, remove fallback default:

```yaml
spring:
  data:
    mongodb:
      uri: ${SPRING_DATA_MONGODB_URI}   # was: ${SPRING_DATA_MONGODB_URI:mongodb://localhost:27017/market_db}
```

**File: `market-data-service/src/main/resources/application-local.yml`** — new file:

```yaml
# Local development profile — provides localhost fallback not present in other profiles.
spring:
  data:
    mongodb:
      uri: ${SPRING_DATA_MONGODB_URI:mongodb://localhost:27017/market_db}
```

> `application-local.yml` is auto-loaded by Spring Boot when the active profile contains `local`.
> `application.yml` defaults `spring.profiles.active` to `local` so this is transparent for local dev.

---

### [ ] 3 — Add `MongoConfig`: serverless pool tuning + URI assertion

New configuration class, `@Profile("aws")`-gated so it never affects local dev or Testcontainers tests.

**File: `market-data-service/src/main/java/com/wealth/market/MongoConfig.java`** — new file:

```java
package com.wealth.market;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.concurrent.TimeUnit;

@Configuration
@Profile("aws")
class MongoConfig {

    @Bean
    MongoClientSettingsBuilderCustomizer lambdaMongoClientCustomizer() {
        return builder -> builder
                .applyToConnectionPoolSettings(pool -> pool
                        .maxSize(5)                                       // one pool per Lambda instance; 3-5 per serverless guidance
                        .minSize(0)                                       // avoid idle connections on cold/warm cycles
                        .maxConnectionIdleTime(30, TimeUnit.SECONDS))    // release fast between invocations
                .applyToSocketSettings(socket -> socket
                        .connectTimeout(5, TimeUnit.SECONDS)             // fail-fast; must exceed network latency to Atlas
                        .readTimeout(10, TimeUnit.SECONDS))              // non-zero; short OLTP reads
                .applyToClusterSettings(cluster -> cluster
                        .serverSelectionTimeout(5, TimeUnit.SECONDS));   // replaces the broken 30 s default
    }

    @Bean
    ApplicationRunner mongoUriAssertion(@Value("${spring.data.mongodb.uri:}") String uri) {
        return args -> {
            if (uri == null || uri.isBlank() || uri.startsWith("mongodb://localhost")) {
                throw new IllegalStateException(
                        "spring.data.mongodb.uri is empty or points to localhost under aws profile — " +
                        "SPRING_DATA_MONGODB_URI is not being injected by Terraform.");
            }
        };
    }
}
```

**Parameter rationale (per `.agents/skills/mongodb-connection` serverless scenario):**

| Parameter | Value | Reason |
|---|---|---|
| `maxSize` | 5 | Each Lambda instance owns its own pool; 3–5 is the skill ceiling |
| `minSize` | 0 | Prevent idle connections on warm/cold cycles |
| `maxConnectionIdleTime` | 30 s | Release unused connections quickly between invocations |
| `connectTimeout` | 5 s | Must exceed longest network latency to Atlas member |
| `readTimeout` | 10 s | Non-zero; ensures sockets are always closed (skill requirement) |
| `serverSelectionTimeout` | 5 s | Replaces 30 s default; prevents startup stall on bad URI |

---

### [ ] 4 — Decouple `StartupHydrationService` from the startup critical path

`ApplicationRunner.run()` executes inside `SpringApplication.run()` before the actuator endpoints are published. A Mongo stall here blocks LWA's readiness probe. Converting to `ApplicationListener<ApplicationReadyEvent>` fires hydration *after* the application is fully ready, matching the pattern already used by `InfrastructureHealthLogger` in the same module.

**File: `market-data-service/src/main/java/com/wealth/market/StartupHydrationService.java`**

Import changes:
- Remove: `import org.springframework.boot.ApplicationRunner;`
- Remove: `import org.springframework.boot.ApplicationArguments;`
- Add: `import org.springframework.context.ApplicationListener;`
- Add: `import org.springframework.boot.context.event.ApplicationReadyEvent;`

Class declaration change:
```java
// Before:
class StartupHydrationService implements ApplicationRunner {

// After:
class StartupHydrationService implements ApplicationListener<ApplicationReadyEvent> {
```

Method signature change (body is identical):
```java
// Before:
@Override
public void run(ApplicationArguments args) {
    MDC.put("marketDataStartupHydrationId", UUID.randomUUID().toString());
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
        runHydration();
    } finally {
        sample.stop(Timer.builder("market.data.startup.hydration")
                .description("Time to republish cached prices to Kafka on startup")
                .register(meterRegistry));
        MDC.remove("marketDataStartupHydrationId");
    }
}

// After:
@Override
public void onApplicationEvent(ApplicationReadyEvent event) {
    MDC.put("marketDataStartupHydrationId", UUID.randomUUID().toString());
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
        runHydration();
    } finally {
        sample.stop(Timer.builder("market.data.startup.hydration")
                .description("Time to republish cached prices to Kafka on startup")
                .register(meterRegistry));
        MDC.remove("marketDataStartupHydrationId");
    }
}
```

`runHydration()` is unchanged.

---

## Files Changed

| File | Status | Description |
|---|---|---|
| `infrastructure/terraform/variables.tf` | Edit | Add `validation` block to `mongodb_connection_string` |
| `infrastructure/terraform/modules/compute/variables.tf` | Edit | Same `validation` block |
| `market-data-service/src/main/resources/application.yml` | Edit | Remove `localhost:27017` fallback from URI placeholder |
| `market-data-service/src/main/resources/application-local.yml` | **New** | Holds the localhost fallback for `local` profile |
| `market-data-service/src/main/java/com/wealth/market/MongoConfig.java` | **New** | Serverless pool tuning + startup URI assertion |
| `market-data-service/src/main/java/com/wealth/market/StartupHydrationService.java` | Edit | `ApplicationRunner` → `ApplicationListener<ApplicationReadyEvent>` |

No `build.gradle` changes — all required types are already on the classpath.  
No new CI/CD workflows — Terraform validation (Change 1) catches the secret gap before deploy.  
Existing Testcontainers tests are unaffected — `MongoConfig` is `@Profile("aws")`-gated.

---

## Verification Checklist (post-deploy)

- [ ] `aws lambda get-function-configuration --function-name wealth-market-data-service --query 'Environment.Variables.SPRING_DATA_MONGODB_URI'` returns the Atlas URI
- [ ] CloudWatch: `org.mongodb.driver.cluster` log shows `hosts=[<atlas-host>:27017]` and `credential=MongoCredential{...}` (not `localhost`)
- [ ] CloudWatch: `[INFRA-OK]   MongoDB — ping succeeded` from `InfrastructureHealthLogger`
- [ ] LWA no longer logs `app is not ready after Nms` beyond the first 1–2 probes
- [ ] Local: `./gradlew :market-data-service:test` passes without changes to test behaviour
- [ ] `terraform plan` with a blank `TF_VAR_mongodb_connection_string` exits with the validation error (not silently passes)
