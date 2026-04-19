# =============================================================================
# Multi-stage Dockerfile for api-gateway
# Build context: repo root (docker build -f api-gateway/Dockerfile .)
# =============================================================================

# ---------------------------------------------------------------------------
# Stage 0: Gradle distribution cache
# Downloads the Gradle wrapper zip once with retry logic so the builder stage
# never hits services.gradle.org. A 504 from that CDN would otherwise fail the
# entire build — this is a pre-existing flaky failure that this stage eliminates.
# The zip is placed at the exact path the wrapper expects so it skips the download.
# ---------------------------------------------------------------------------
FROM amazoncorretto:25 AS gradle-dist

# amazoncorretto:25 ships with curl-minimal which supports all required flags.
# Installing the full curl package would conflict with curl-minimal — skip it.
# --retry 5 --retry-delay 5 --retry-connrefused covers transient CDN 504s.
RUN GRADLE_VERSION=9.4.1 \
    && GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
    && DEST="${HOME}/.gradle/wrapper/dists/gradle-${GRADLE_VERSION}-bin" \
    && mkdir -p "${DEST}" \
    && curl --retry 5 --retry-delay 5 --retry-connrefused \
    --location --fail --show-error \
    -o "${DEST}/gradle-${GRADLE_VERSION}-bin.zip" \
    "${GRADLE_URL}"

# ---------------------------------------------------------------------------
# Stage 1: Builder — compile source and produce Spring Boot fat JAR with AOT
# ---------------------------------------------------------------------------
FROM amazoncorretto:25 AS builder

WORKDIR /workspace

# Pre-populate the Gradle wrapper cache from the gradle-dist stage.
# The wrapper checks this path first and skips the network download entirely.
COPY --from=gradle-dist /root/.gradle /root/.gradle

# Copy Gradle wrapper and build config first (layer caching)
COPY gradlew gradlew
COPY gradle/ gradle/
COPY build.gradle build.gradle
COPY settings.gradle settings.gradle

# Copy source for common-dto (dependency) and api-gateway
COPY common-dto/ common-dto/
COPY api-gateway/ api-gateway/

# Trim settings.gradle to only include modules needed for this service
# (Gradle 9.x fails if included project directories don't exist)
RUN sed -i "/include 'portfolio-service'/d; /include 'market-data-service'/d; /include 'insight-service'/d" settings.gradle

# Install findutils (provides xargs, required by Gradle wrapper)
RUN yum install -y findutils && yum clean all

# Build the fat JAR. The bootJar task depends on processAot (configured in build.gradle),
# so AOT initializer classes are generated and bundled automatically.
RUN chmod +x gradlew \
    && ./gradlew :api-gateway:bootJar --no-daemon

# ---------------------------------------------------------------------------
# Stage 2: JRE Builder — extract JAR, run jdeps/jlink to produce Custom JRE
# ---------------------------------------------------------------------------
FROM amazoncorretto:25 AS jre-builder

# Install binutils (provides objcopy, required by jlink --strip-debug)
RUN yum install -y binutils && yum clean all

WORKDIR /jre-build

# Copy the fat JAR from builder stage
COPY --from=builder /workspace/api-gateway/build/libs/*.jar app.jar

# Extract the fat JAR to access individual library JARs for jdeps analysis
RUN mkdir -p extracted && cd extracted && jar xf /jre-build/app.jar

# Run jdeps on all JARs to discover required modules, then build a Custom JRE
# Fallback modules: jdk.unsupported (Spring internals), java.security.jgss (auth/Kafka)
RUN DEPS=$(jdeps \
    --ignore-missing-deps \
    --print-module-deps \
    --multi-release 25 \
    -cp "extracted/BOOT-INF/lib/*" \
    app.jar 2>/dev/null || echo "java.base") \
    && echo "Detected modules: $DEPS" \
    && jlink \
    --add-modules "${DEPS},jdk.unsupported,java.security.jgss" \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=zip-6 \
    --output /custom-jre

# ---------------------------------------------------------------------------
# Stage 3: Runtime — Amazon Linux 2023 Minimal with Custom JRE + Lambda support
# Matches the Lambda runtime environment for full glibc parity.
# ---------------------------------------------------------------------------
FROM public.ecr.aws/amazonlinux/amazonlinux:2023-minimal AS runtime

# Install tar/gzip (for RIE download extraction) — curl-minimal is pre-installed.
# ca-certificates populates the OS trust store so the JVM can validate SSL certs
# from external services (Aiven Kafka, MongoDB Atlas, etc.) — without it, JSSE
# throws "PKIX path building failed: unable to find valid certification path".
RUN microdnf install -y tar gzip ca-certificates && microdnf clean all

# Copy Custom JRE
ENV JAVA_HOME=/opt/java
COPY --from=jre-builder /custom-jre $JAVA_HOME
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Overwrite the jlink JRE's cacerts with the full Corretto trust store.
# jlink copies cacerts from the source JDK, but the resulting keystore may be
# incomplete for external SSL services (Aiven Kafka SASL_SSL, MongoDB Atlas).
# The full Corretto cacerts includes all standard root CAs.
COPY --from=jre-builder /usr/lib/jvm/java-25-amazon-corretto/lib/security/cacerts ${JAVA_HOME}/lib/security/cacerts

# Copy application JAR
WORKDIR /app
COPY --from=builder /workspace/api-gateway/build/libs/*.jar app.jar

# Copy AWS Lambda Web Adapter from official ECR image.
# The binary MUST be named 'lambda-adapter' — this is the name the binary registers
# with the Lambda Extensions API. Using a different filename causes a name mismatch
# that results in Extension.Crash.
COPY --from=public.ecr.aws/awsguru/aws-lambda-adapter:1.0.0 /lambda-adapter /opt/extensions/lambda-adapter

# Download AWS Lambda Runtime Interface Emulator (RIE)
RUN ARCH=$(uname -m) \
    && if [ "$ARCH" = "x86_64" ]; then RIE_ARCH="x86_64"; else RIE_ARCH="arm64"; fi \
    && curl -Lo /usr/local/bin/aws-lambda-rie \
    "https://github.com/aws/aws-lambda-runtime-interface-emulator/releases/latest/download/aws-lambda-rie-${RIE_ARCH}" \
    && chmod +x /usr/local/bin/aws-lambda-rie

# Lambda Web Adapter configuration
ENV AWS_LWA_PORT=8080
ENV AWS_LWA_READINESS_CHECK_PATH=/actuator/health

# Expose the service port
EXPOSE 8080

# Default: Java app runs as PID 1; Lambda auto-loads the LWA sidecar from /opt/extensions/
# Locally (no Lambda runtime API), the adapter runs in passthrough mode
ENTRYPOINT ["java", "-Dspring.aot.enabled=true", "-jar", "/app/app.jar"]
