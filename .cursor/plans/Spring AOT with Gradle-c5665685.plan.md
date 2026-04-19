span

---

todos:

- id: "verify-task-graph"
  content: "Run Gradle dry-run or task graph for :service:bootJar; confirm processAot runs before bootJar for each runnable module"
  status: pending
- id: "wire-bootjar-if-needed"
  content: "If processAot is missing from the graph, add tasks.named('bootJar') { dependsOn tasks.named('processAot') } to those services' build.gradle (or conditional subprojects block)"
  status: pending
- id: "align-runtime-flags"
  content: "Decide per environment (compose vs Lambda Dockerfile) whether java -jar should pass -Dspring.aot.enabled=true and keep it off Gradle unless intentional"
  status: pending
- id: "optional-native"
  content: "If target is GraalVM binary: add GraalVM native plugin, nativeCompile workflow, and CI resource expectations separate from bootJar"
  status: pending
  isProject: false

---

# Spring AOT with Gradle (this repo)

## What you already have

- **Spring Boot 4.0.5** is declared on the root build ([`build.gradle`](build.gradle) `plugins { id 'org.springframework.boot' version '4.0.5' apply false ... }`).
- All four runnable modules apply both plugins ([`api-gateway/build.gradle`](api-gateway/build.gradle), [`portfolio-service/build.gradle`](portfolio-service/build.gradle), [`market-data-service/build.gradle`](market-data-service/build.gradle), [`insight-service/build.gradle`](insight-service/build.gradle)):

```groovy
plugins {
    id 'org.springframework.boot'
    id 'org.springframework.boot.aot'
}
```

- **`common-dto`** is a plain Java library and should **not** apply the Boot or AOT plugins (correct as-is in [`common-dto/build.gradle`](common-dto/build.gradle)).
- **Docker**: [`portfolio-service/Dockerfile`](portfolio-service/Dockerfile) builds with `./gradlew :portfolio-service:bootJar` and comments that AOT is bundled for Lambda-style deployments. Some other service Dockerfiles use runtime `ENTRYPOINT` with `-Dspring.aot.enabled=true` (e.g. [`api-gateway/Dockerfile`](api-gateway/Dockerfile)).

That is the standard Gradle shape for Spring Boot 4 AOT: **`org.springframework.boot` + `org.springframework.boot.aot`** on the application module.

## Two separate concepts (easy to confuse)


| Layer                      | What it does                                                                                                       | How you trigger it                                                                                                                                            |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Build-time AOT**         | Runs`processAot`, generates `*__ApplicationContextInitializer` and related sources, compiles them into the fat JAR | Gradle: ensure`bootJar` (or your image build) actually runs **`processAot`** before packaging                                                                 |
| **Runtime AOT on the JVM** | Uses the precomputed bean factory path instead of full classpath scanning at startup                               | JVM flag**`-Dspring.aot.enabled=true`** (or equivalent config) on **`java -jar ...`**, **not** on Gradle’s JVM unless you intend to affect the Gradle daemon |

Your internal write-up in [`docs/changes/CHANGES_PHASE3_INFRA_SUMMARY_18042026.md`](docs/changes/CHANGES_PHASE3_INFRA_SUMMARY_18042026.md) (§10.1–10.2) documents a real failure mode: enabling AOT at **runtime** without **`processAot` having run at build time** produces “initializer could not be found”. It also recommends explicitly wiring:

`tasks.named('bootJar') { dependsOn tasks.named('processAot') }`

**Note:** That explicit `dependsOn` does **not** appear in the current `*.gradle` files in this workspace (grep shows no matches). Whether you need it depends on your Spring Boot 4.0.5 + Gradle 9.4.1 task graph; **verify** rather than assume.

**Verification (when you leave plan mode):** run something like `./gradlew :api-gateway:bootJar --dry-run` (or the Gradle task graph / build scan) and confirm **`processAot`** is scheduled before **`bootJar`**. If it is not, add the explicit `dependsOn` per service (or once in `subprojects` only for modules that apply the AOT plugin).

## Optional paths beyond “AOT in a fat JAR”

1. **GraalVM native image**
   Add **`org.graalvm.buildtools.native`** and follow [Spring Boot’s native image Gradle guide](https://docs.spring.io/spring-boot/4.0/gradle-plugin/native-image.html). Native builds consume `processAot` output via **`nativeCompile`**; that is a different artifact than `bootJar`.
2. **Tests with AOT**
   Use **`processTestAot`** / test AOT configuration when you want the test `ApplicationContext` to mirror AOT-processed behavior (see [Spring Boot Gradle AOT plugin](https://docs.spring.io/spring-boot/4.0/gradle-plugin/aot.html)).
3. **Local docker-compose vs Lambda**
   [`docker-compose.yml`](docker-compose.yml) intentionally runs some services **without** `-Dspring.aot.enabled=true` (comments reference local vs Lambda). Align entrypoints only if you want local behavior to match production AOT-on-JVM startup.

## Summary checklist for you

1. Keep **`org.springframework.boot.aot`** only on **Spring Boot application** modules (already done).
2. **Verify** `bootJar` → `processAot` ordering; add explicit `dependsOn` if the graph is wrong (matches your own incident doc).
3. Use **`-Dspring.aot.enabled=true` on the `java` process** when you want the packaged JAR to actually **use** AOT at runtime.
4. Add **GraalVM native** plugin only if the goal is a **native executable**, not just JVM cold-start tuning.

Official references worth bookmarking: [Gradle AOT plugin](https://docs.spring.io/spring-boot/4.0/gradle-plugin/aot.html), [AOT on the JVM (reference)](https://docs.spring.io/spring-boot/4.0/reference/packaging/aot.html).
