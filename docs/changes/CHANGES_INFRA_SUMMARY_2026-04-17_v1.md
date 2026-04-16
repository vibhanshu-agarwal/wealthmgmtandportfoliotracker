# Phase 3 CI/CD + Production Dependency Verification — 2026-04-17 (v1)

## Summary

Introduced a GitHub Actions deployment workflow for the `architecture/cloud-native-extraction` integration branch, aligned the deployment plan documentation with that branch as the active deployment target, and hardened the Lambda environment injection step so secrets are written as real values (not literal `${...}` placeholders).

Also documented the consolidated pre-deployment smoke test (`verify-prod-deps.sh`) that bundles Neon PostgreSQL, Upstash Redis, and Aiven Kafka verification.

## GitHub Actions: `.github/workflows/deploy.yml`

### What shipped

- **Trigger**: `push` to `architecture/cloud-native-extraction` (not `main`).
- **Parallel jobs**:
  - `deploy-frontend`: builds the Next.js frontend under `frontend/` and syncs the resolved artifact directory (`dist/`, `build/`, or `out/`) to `s3://${{ secrets.S3_BUCKET_NAME }}`.
  - `deploy-backend`: runs `./gradlew bootJar`, builds/pushes the `portfolio-service` container image to ECR (`latest` + `${{ github.sha }}`), updates Lambda **environment**, then updates Lambda **image URI**.

### Lambda environment injection (critical correctness)

- Replaced the previous `cat <<'EOF'` approach (which would not expand shell variables) with **`jq` JSON generation**, ensuring GitHub-provided environment values are embedded correctly into `lambda-env.json` before `aws lambda update-function-configuration`.
- Added an explicit **`apt-get install jq`** step to avoid runner-image drift surprises.

### Required secrets checklist (also duplicated as comments in the workflow)

Repo / AWS:

- `AWS_ACCESS_KEY_ID`
- `AWS_SECRET_ACCESS_KEY`
- `AWS_REGION`
- `AWS_ACCOUNT_ID`
- `S3_BUCKET_NAME`
- `ECR_REPOSITORY_NAME`
- `LAMBDA_FUNCTION_NAME`

Application/runtime (mapped into Lambda):

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `REDIS_URL`
- `UPSTASH_REDIS_REST_URL`
- `UPSTASH_REDIS_REST_TOKEN`
- `KAFKA_BOOTSTRAP_SERVERS`
- `KAFKA_SASL_USERNAME`
- `KAFKA_SASL_PASSWORD`
- `BEDROCK_REGION`
- `BEDROCK_MODEL_ID`
- `NEXTAUTH_SECRET`
- `AUTH_JWT_SECRET`

**Note**: if a secret is missing in GitHub, it becomes an empty string in Lambda (the workflow still succeeds YAML-wise).

## Pre-deployment smoke test: `verify-prod-deps.sh`

Consolidates the three successful verification paths into one command:

- `./verify-db.sh` — Neon/PostgreSQL connectivity + Flyway + Spring `Started` checks (via containerized `portfolio-service`)
- `./verify-redis.sh` — Upstash REST `SET/GET` + `rediss://` `PING`
- `./verify-kafka.sh` — Aiven TLS + SASL metadata via `kcat` with broker CA chain extraction

## Documentation sync

- Updated `.kiro/specs/aws-deployment/deployment-plan.md` to reflect that **`architecture/cloud-native-extraction` is the current deployment target branch** for Phase 3 automation.

## Key Decisions

| Decision | Rationale |
| --- | --- |
| Target CI/CD on `architecture/cloud-native-extraction` | Matches the active modernized codebase branch rather than legacy `main`. |
| Generate Lambda env JSON with `jq` | Prevents accidental literal `${VAR}` strings in Lambda configuration. |
| Keep frontend artifact resolution flexible (`dist`/`build`/`out`) | Next.js output layout varies by configuration; avoids brittle CI assumptions. |
