#!/bin/bash
# SessionStart hook — prepares a Claude Code on the web container to build and test
# this repository. No-op outside remote sessions so local machines are untouched.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

# --- JAVA_TOOL_OPTIONS -------------------------------------------------------
# The remote container injects JAVA_TOOL_OPTIONS so the JVM trusts the agent
# proxy CA. Every JVM it starts then prints "Picked up JAVA_TOOL_OPTIONS: ..."
# on stderr, which breaks tests that assert a spawned process emitted empty
# stderr. It is NOT unset here: Gradle needs it to resolve dependencies over the
# proxy. Instead build.gradle removes it from forked Test JVMs only, so
# dependency resolution keeps the CA and test subprocesses get clean streams.
# Nothing to do at session start; this note exists so the behaviour is findable.

# --- Docker daemon -----------------------------------------------------------
# Testcontainers-based tests need a running daemon. Docker is installed in the
# image but not started.
if ! docker info >/dev/null 2>&1; then
  (sudo dockerd >/tmp/dockerd.log 2>&1 &) || true
  for _ in $(seq 1 15); do
    docker info >/dev/null 2>&1 && break
    sleep 1
  done
fi

# --- Report ------------------------------------------------------------------
echo "=== repo session-start ==="
echo "java   : $(env -u JAVA_TOOL_OPTIONS java -version 2>&1 | head -1 | tr -d '\r')"
echo "node   : $(node -v 2>/dev/null || echo absent)"
echo "python : $(python3 -V 2>&1)"
if docker info >/dev/null 2>&1; then
  echo "docker : daemon up"
else
  echo "docker : daemon NOT running (see /tmp/dockerd.log)"
fi

cat <<'NOTES'

Known constraints in this container (verified 2026-09-13):
  * Docker REGISTRY pulls are blocked by the environment network policy (403 at
    CONNECT on production.cloudfront.docker.com). Testcontainers tests that pull
    an image — api-gateway InfrastructureHealthLoggerProfileTest — cannot run
    here until that policy is widened. The daemon itself works for local images.
  * Windows PowerShell is not available. scripts/tests/test_run_task_8_9_preflight.ps1
    requires Windows PowerShell 5.1 and must be run on Windows.
  * The Azure CLI is not installed by default and several Azure endpoints are
    denied by the network policy. Task 8.9 live proofs run on Windows.
  * Build/test commands that work here:
      ./gradlew test            (JVM suite, ~1m with daemon)
      ./gradlew compileJava compileTestJava
      cd frontend && npm test   (vitest)
NOTES
