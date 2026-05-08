# TODO — Azure Container Apps Deployment (Cross-Cutting Notes)

This file captures implementation notes, AWS-side tripwires, and follow-ups that do not fit cleanly inside `design.md` or `requirements.md` but must travel with the spec so that any future agent or operator can find them quickly.

---

## 1. Toolchain downgrade (JDK 25 → JDK 21) — cross-cutting change inside this spec

### 1.1 What changed and why

The Azure path requires a Microsoft-native Mariner-based runtime image. `mcr.microsoft.com/openjdk/jdk:21-mariner` is GA; there is no `:25-mariner` tag yet. The project set `JavaLanguageVersion.of(25)` aspirationally but has not adopted any Java 22+ language or API features (verified by source scan: no `StringTemplate`, `StructuredTaskScope`, `ScopedValue`, `Gatherer`, `java.lang.classfile`, or unnamed-variable patterns). Downgrading to JDK 21 LTS therefore:

- **Eliminates** the multi-stage jlink custom-JRE step on Azure (single-stage runtime works directly with the Microsoft Mariner JDK image)
- **Aligns** with Spring Boot 4's most-tested LTS path
- **Aligns** with AWS Lambda's GA `java21` managed runtime (today the project uses a custom runtime for Java 25)
- **Preserves** every language feature the codebase actually uses (records, sealed types, pattern matching, text blocks, `Math.clamp`, virtual threads — all in JDK 21)

This spec makes a single-line change in the root `build.gradle`:

```diff
 java {
     toolchain {
-        languageVersion = JavaLanguageVersion.of(25)
+        languageVersion = JavaLanguageVersion.of(21)
     }
 }
```

Nothing else in the Java source tree changes **for the toolchain downgrade itself**. (The broader spec does edit Java sources — `@Profile` widening, new Azure AI adapters, etc. — but those are unrelated to the JDK version bump.) The compiled bytecode target drops from class-file major version 69 (Java 25) to 65 (Java 21).

### 1.2 AWS Dockerfiles still reference `amazoncorretto:25` — this is deliberate

The four AWS Dockerfiles in scope for the **existing** Lambda path (`api-gateway/Dockerfile`, `portfolio-service/Dockerfile`, `market-data-service/Dockerfile`, `insight-service/Dockerfile`) continue to reference:

- `FROM amazoncorretto:25` (all three build stages: `gradle-dist`, `builder`, `jre-builder`)
- `--multi-release 25` in the `jdeps` invocation
- `COPY --from=jre-builder /usr/lib/jvm/java-25-amazon-corretto/lib/security/cacerts ...`

**Why we are leaving them alone in this spec:** the additive-only constraint requires zero changes to AWS Dockerfiles. A Corretto 25 JDK can compile to target bytecode version 21 (`--release 21`) without complaint, so the AWS build continues to produce a working fat JAR. The jlink custom JRE built under Corretto 25 running against Java 21 bytecode is also valid (a newer JRE can run older bytecode; the inverse is what fails).

**This is a working configuration but it is wasteful and inconsistent.** The builder downloads a Java 25 JDK, then the Gradle toolchain downloads a Java 21 JDK alongside it to compile sources. The jlink output JRE is a Java 25 JRE running Java 21 bytecode. Everything works, but a future agent cleaning this up should downgrade the AWS Dockerfiles too.

### 1.3 Tripwires if a future agent cleans up the AWS Dockerfiles

Search-and-replace is not enough. Each file has interlocking version-specific paths. The minimum consistent change set is:

| File / line pattern | Action |
|---|---|
| `FROM amazoncorretto:25 AS gradle-dist` (4 Dockerfiles) | → `FROM amazoncorretto:21 AS gradle-dist` |
| `FROM amazoncorretto:25 AS builder` (4 Dockerfiles) | → `FROM amazoncorretto:21 AS builder` |
| `FROM amazoncorretto:25 AS jre-builder` (4 Dockerfiles) | → `FROM amazoncorretto:21 AS jre-builder` |
| `--multi-release 25` in `jdeps` invocation (4 Dockerfiles) | → `--multi-release 21` |
| `COPY --from=jre-builder /usr/lib/jvm/java-25-amazon-corretto/lib/security/cacerts ...` (4 Dockerfiles) | → `COPY --from=jre-builder /usr/lib/jvm/java-21-amazon-corretto/lib/security/cacerts ...` |
| `docs/specs/deployment-verification-pipeline/design.md` references to `amazoncorretto:25` | Update or add a note that the document predates the downgrade |
| `docs/specs/deployment-verification-pipeline/tasks.md` references to `amazoncorretto:25` | Same as above |
| `README.md` Java 25 badge | `![Java](https://img.shields.io/badge/Java-21-orange.svg)` pointing to `https://openjdk.org/projects/jdk/21/` |
| `docs/changes/CHANGES_PHASE3_INFRA_SUMMARY_*.md` Corretto 25 references | Historical changelog — do NOT retroactively edit; the change note documents what happened at that point in time |

### 1.4 Optional: switch AWS Lambda runtime from custom to `java21` managed

Today the Lambda is likely deployed as an image (Lambda Container Image support) because of the custom Java 25 runtime. With JDK 21 bytecode, the Lambda could run on the managed `java21` runtime handler-based path instead. This is a significant architectural change (switches from Spring Boot web → Spring Cloud Function handler or direct `RequestHandler<>` implementation). **Out of scope for this spec and for the AWS Dockerfile cleanup above.** Flag only — do not implement without a dedicated spec.

### 1.5 Tripwires for the Azure side

None. The Azure Dockerfiles produced by this spec use `mcr.microsoft.com/openjdk/jdk:21-mariner` directly — builder stage and runtime stage share the same image, no jlink, no cacerts overwrite. See `design.md` §3.7 after the revision.

---

## 2. Pre-deployment operational prerequisites (mirror of `design.md` §5.2)

Before any `terraform apply` or `deploy-azure.yml` run against Azure succeeds, these must be established once, manually, out-of-band:

1. **Entra ID app registration** with a service principal in the target tenant
2. **Federated identity credentials** on the app registration:
   - Issuer: `https://token.actions.githubusercontent.com`
   - Subject: `repo:<owner>/<repo>:ref:refs/heads/main` (deploy + terraform apply on main)
   - Subject: `repo:<owner>/<repo>:pull_request` (plan on PR — optional)
3. **Role assignments on the subscription for the service principal:**
   - `Contributor` on the target resource group (Terraform apply)
   - `User Access Administrator` on the target resource group (Terraform creates `AcrPull` and `Cognitive Services OpenAI User` role assignments itself)
   - `AcrPush` on the ACR resource (image push in `deploy-azure.yml`)
4. **GitHub Actions repository secrets populated:**
   - `AZURE_CLIENT_ID` — app registration client ID
   - `AZURE_TENANT_ID` — Entra tenant ID
   - `AZURE_SUBSCRIPTION_ID` — target subscription ID
   - **No `AZURE_CLIENT_SECRET`** — OIDC federated credentials replace it

**Failure symptom if any of these is missing or misconfigured:** `AADSTS70021: No matching federated identity record found`.

---

## 3. Deferred items (backlog — authoritative IDs in `design.md` §6)

These are out of scope for this spec; listed here so they appear in a single place when someone returns to this work. **The backlog IDs below are the authoritative ones and match `design.md` §6 exactly. Earlier revisions of this file used different numbers — do not rely on any prior copy.**

| ID | Item | Source |
|---|---|---|
| B1 | Widen `api-gateway/InfrastructureHealthLogger` `@Profile` to `{"aws","azure"}` for observability parity | `design.md` §3.3 note + §6 |
| B2 | AWS Dockerfile toolchain alignment (downgrade the four AWS Dockerfiles from `amazoncorretto:25` → `amazoncorretto:21`, fix `--multi-release` and cacerts paths) | §1.3 above; `design.md` §6 |
| B3 | AWS Lambda managed-runtime migration (custom container → `java21` managed runtime; requires its own spec) | §1.4 above; `design.md` §6 |
| B4 | Per-cloud Kafka consumer group IDs (`group-id: portfolio-service-${CLOUD:aws}`) for simultaneous A/B dual-cloud consumption | `design.md` §2.4 + §6 |
| B5 | mTLS between ACA services via Dapr or service mesh add-on | `design.md` §6 |

**Historical note:** An earlier draft of `design.md` §6 contained a `B2` entry about "redundant JDK 21 in the Mariner runtime base image." That entry is obsolete and has been replaced with the current `B2` (AWS Dockerfile toolchain alignment). The redundancy it described no longer exists because the Azure Dockerfile now uses its bundled JDK directly rather than layering a custom jlink JRE on top.

---

## 4. Priority statement

**Priority one:** Azure Container Apps deployment end-to-end, per `design.md` and `requirements.md`.

**Priority two (backlog, do not interleave):** AWS Dockerfile toolchain cleanup per §1.3. Defer until Azure is validated in production.

**Priority three (backlog, requires its own spec):** AWS Lambda managed-runtime migration per §1.4.

If AWS deployment breaks at any point during or after Azure rollout, **check §1.2 first**. The most likely cause is a toolchain mismatch between the `build.gradle` JDK 21 toolchain and the Dockerfile's `amazoncorretto:25` stages. If Gradle inside the builder cannot auto-provision the JDK 21 toolchain (e.g., offline build, restricted network), set `org.gradle.java.installations.auto-download=false` and pre-install JDK 21 in the builder image, or temporarily revert `build.gradle` to `JavaLanguageVersion.of(25)` and rebuild.
