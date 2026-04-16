# =============================================================================
# Multi-stage Dockerfile for market-data-service
# Build context: repo root (docker build -f market-data-service/Dockerfile .)
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

# Copy source for common-dto (dependency) and market-data-service
COPY common-dto/ common-dto/
COPY market-data-service/ market-data-service/

# Trim settings.gradle to only include modules needed for this service
# (Gradle 9.x fails if included project directories don't exist)
RUN sed -i "/include 'api-gateway'/d; /include 'portfolio-service'/d; /include 'insight-service'/d" settings.gradle

# Install findutils (provides xargs, required by Gradle wrapper)
RUN yum install -y findutils && yum clean all

# Build the fat JAR with Spring AOT enabled
RUN chmod +x gradlew \
    && ./gradlew :market-data-service:bootJar -Dspring.aot.enabled=true --no-daemon

# ---------------------------------------------------------------------------
# Stage 2: JRE Builder — extract JAR, run jdeps/jlink to produce Custom JRE
# ---------------------------------------------------------------------------
FROM amazoncorretto:25 AS jre-builder

# Install binutils (provides objcopy, required by jlink --strip-debug)
RUN yum install -y binutils && yum clean all

WORKDIR /jre-build

# Copy the fat JAR from builder stage
COPY --from=builder /workspace/market-data-service/build/libs/*.jar app.jar

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
COPY --from=builder /workspace/market-data-service/build/libs/*.jar app.jar

# Copy AWS Lambda Web Adapter from ECR Public (pinned tag — :latest is not published on this repo)
COPY --from=public.ecr.aws/awsguru/aws-lambda-adapter:1.0.0 /lambda-adapter /lambda-adapter

# Runtime configuration
ENV SERVER_PORT=8080
ENV PORT=8080

# Expose the service port
EXPOSE 8080

# Default: run Spring Boot directly
# In AWS Lambda, configure AWS_LAMBDA_EXEC_WRAPPER=/lambda-adapter so the adapter wraps this process.
ENTRYPOINT ["java", "-Dspring.aot.enabled=true", "-jar", "/app/app.jar"]

