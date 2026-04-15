# =============================================================================
# Multi-stage Dockerfile for insight-service
# Build context: repo root (docker build -f insight-service/Dockerfile .)
# =============================================================================

# ---------------------------------------------------------------------------
# Stage 1: Builder — compile source and produce Spring Boot fat JAR with AOT
# ---------------------------------------------------------------------------
FROM amazoncorretto:25 AS builder

WORKDIR /workspace

# Copy Gradle wrapper and build config first (layer caching)
COPY gradlew gradlew
COPY gradle/ gradle/
COPY build.gradle build.gradle
COPY settings.gradle settings.gradle

# Copy source for common-dto (dependency) and insight-service
COPY common-dto/ common-dto/
COPY insight-service/ insight-service/

# Trim settings.gradle to only include modules needed for this service
# (Gradle 9.x fails if included project directories don't exist)
RUN sed -i "/include 'api-gateway'/d; /include 'portfolio-service'/d; /include 'market-data-service'/d" settings.gradle

# Install findutils (provides xargs, required by Gradle wrapper)
RUN yum install -y findutils && yum clean all

# Build the fat JAR with Spring AOT enabled
RUN chmod +x gradlew \
    && ./gradlew :insight-service:bootJar -Dspring.aot.enabled=true --no-daemon

# ---------------------------------------------------------------------------
# Stage 2: JRE Builder — extract JAR, run jdeps/jlink to produce Custom JRE
# ---------------------------------------------------------------------------
FROM amazoncorretto:25 AS jre-builder

# Install binutils (provides objcopy, required by jlink --strip-debug)
RUN yum install -y binutils && yum clean all

WORKDIR /jre-build

# Copy the fat JAR from builder stage
COPY --from=builder /workspace/insight-service/build/libs/*.jar app.jar

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

# Install tar/gzip (for RIE download extraction) — curl-minimal is pre-installed
RUN microdnf install -y tar gzip && microdnf clean all

# Copy Custom JRE
ENV JAVA_HOME=/opt/java
COPY --from=jre-builder /custom-jre $JAVA_HOME
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Copy application JAR
WORKDIR /app
COPY --from=builder /workspace/insight-service/build/libs/*.jar app.jar

# Copy AWS Lambda Web Adapter from official ECR image
COPY --from=public.ecr.aws/awsguru/aws-lambda-adapter:1.0.0 /lambda-adapter /opt/extensions/aws-lambda-web-adapter

# Download AWS Lambda Runtime Interface Emulator (RIE)
RUN ARCH=$(uname -m) \
    && if [ "$ARCH" = "x86_64" ]; then RIE_ARCH="x86_64"; else RIE_ARCH="arm64"; fi \
    && curl -Lo /usr/local/bin/aws-lambda-rie \
    "https://github.com/aws/aws-lambda-runtime-interface-emulator/releases/latest/download/aws-lambda-rie-${RIE_ARCH}" \
    && chmod +x /usr/local/bin/aws-lambda-rie

# Lambda Web Adapter configuration
ENV AWS_LWA_PORT=8083
ENV AWS_LWA_READINESS_CHECK_PATH=/actuator/health

# Expose the service port
EXPOSE 8083

# Default: Lambda Web Adapter wraps the app
# Locally (no Lambda runtime API), the adapter runs in passthrough mode
ENTRYPOINT ["/opt/extensions/aws-lambda-web-adapter"]
CMD ["java", "-Dspring.aot.enabled=true", "-jar", "/app/app.jar"]
