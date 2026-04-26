# Tasks: Production Demo-Ready Auth and Seeding Fix

## 1. Align API Gateway JWT signing and validation

- [ ] 1.1 Add Terraform root variables for API Gateway auth configuration
  - Add `auth_jwt_secret` as sensitive.
  - Add `app_auth_email` as sensitive or non-sensitive per repository convention.
  - Add `app_auth_password` as sensitive.
  - Add `app_auth_user_id` with default `00000000-0000-0000-0000-000000000e2e`.
  - Add `app_auth_name` with default `Demo User`.

- [ ] 1.2 Pass auth variables from root Terraform into the compute module
  - Update `infrastructure/terraform/main.tf` module arguments.
  - Update `infrastructure/terraform/modules/compute/variables.tf`.

- [ ] 1.3 Inject auth variables into `wealth-api-gateway` Lambda environment
  - Add `AUTH_JWT_SECRET`.
  - Add `APP_AUTH_EMAIL`.
  - Add `APP_AUTH_PASSWORD`.
  - Add `APP_AUTH_USER_ID`.
  - Add `APP_AUTH_NAME`.

- [ ] 1.4 Wire GitHub Actions secrets into Terraform
  - Add `TF_VAR_auth_jwt_secret` from `secrets.AUTH_JWT_SECRET`.
  - Add `TF_VAR_app_auth_email` from the demo/e2e email secret.
  - Add `TF_VAR_app_auth_password` from the demo/e2e password secret.
  - Optionally add `TF_VAR_app_auth_user_id` if not relying on Terraform default.

- [ ] 1.5 Change AWS JWT decoding to HS256
  - Update `JwtDecoderConfig` so the `aws` profile validates HS256 using `auth.jwt.secret`.
  - Remove active production dependency on `auth.jwk-uri` / `AUTH_JWK_URI`.
  - Keep any future RS256/JWK path behind a distinct future profile only if needed.

- [ ] 1.6 Add or update API Gateway tests
  - Verify valid login returns a token when the secret is at least 32 bytes.
  - Verify the token issued by login is accepted by the active decoder.
  - Verify wrong signing keys are rejected.
  - Verify blank or short secrets fail clearly.

## 2. Ensure demo login identity matches seeded data

- [ ] 2.1 Standardize the production demo user ID
  - Use `00000000-0000-0000-0000-000000000e2e` for production demo auth.
  - Ensure `APP_AUTH_USER_ID` is set to this value in Lambda.

- [ ] 2.2 Fix seeding setup user ID naming
  - Replace `TEST_USER_ID = E2E_TEST_USER_EMAIL ?? ...` in `global-setup.ts`.
  - Introduce `E2E_TEST_USER_ID` with default `00000000-0000-0000-0000-000000000e2e`.
  - Keep `E2E_TEST_USER_EMAIL` only for login credentials.

- [ ] 2.3 Align AWS synthetic workflow env
  - Pass `E2E_TEST_USER_ID` to the AWS synthetic step.
  - Ensure synthetic login email/password match the Lambda `APP_AUTH_EMAIL` and `APP_AUTH_PASSWORD` values.

- [ ] 2.4 Verify seeded dashboard identity end-to-end
  - Login response `userId` must equal `E2E_TEST_USER_ID`.
  - `/api/portfolio` with the login JWT must return seeded holdings.

## 3. Harden seeding against Lambda cold starts

- [ ] 3.1 Make `global-setup.ts` the owner of seeding retries
  - Keep retries in `seedFetch()`.
  - Remove or downgrade the workflow shell pre-warm step to diagnostics only.

- [ ] 3.2 Improve seed failure diagnostics
  - Include label, endpoint, final status, attempt count, and body excerpt.
  - Make it obvious whether a status was treated as transient.

- [ ] 3.3 Confirm transient status handling
  - Retry `429`, `500`, `502`, `503`, and `504`.
  - Do not retry `503` with `internal_api_key_not_configured`.

- [ ] 3.4 Add a focused test for retry behavior if practical
  - First seed response `502`.
  - Second seed response `200`.
  - Assert two calls and final success.

## 4. Improve frontend login demo behavior

- [ ] 4.1 Preserve HTTP status from `loginWithBackend`
  - Throw a typed or status-carrying error instead of a generic error only.

- [ ] 4.2 Map login errors by status on the login page
  - `401`: invalid username/password.
  - `500`/`503`: login service unavailable.
  - network failure: unable to reach service.
  - malformed success body: invalid login response.

- [ ] 4.3 Update login page helper text
  - Avoid showing local dev credentials as the production demo instruction.
  - Do not hardcode the production demo password in source.

- [ ] 4.4 Add frontend tests for login error messages
  - Verify 401 shows invalid credentials.
  - Verify 500/503 shows service unavailable.
  - Verify network failure shows reachability message.

## 5. Terraform and documentation cleanup

- [ ] 5.1 Update Terraform examples/docs
  - Document `AUTH_JWT_SECRET` and demo auth variables.
  - Remove or clearly mark `AUTH_JWK_URI` as not used by the current single-user auth path.

- [ ] 5.2 Add Terraform plan assertions where practical
  - Assert `wealth-api-gateway` has `AUTH_JWT_SECRET`.
  - Assert `wealth-api-gateway` has `APP_AUTH_USER_ID` equal to the seeded demo user ID.
  - Assert all required auth env vars are non-empty when visible in the plan.

## 6. Validation checklist

- [ ] 6.1 Run API Gateway tests
  - `./gradlew :api-gateway:test`
  - `./gradlew :api-gateway:integrationTest` if Docker/Testcontainers is available.

- [ ] 6.2 Run frontend tests
  - Run targeted auth/session tests.
  - Run Playwright auth preflight against local Docker Compose.

- [ ] 6.3 Run Terraform checks
  - `terraform fmt -check -recursive`
  - `terraform validate`
  - `terraform plan -input=false`
  - `python3 scripts/assert_plan.py plan.json`

- [ ] 6.4 Run live production smoke after deploy
  - Login with demo credentials returns HTTP 200.
  - Login response contains JWT and the seeded demo user ID.
  - `/api/portfolio` with the JWT returns seeded holdings.
  - Browser dashboard shows non-empty holdings and non-zero portfolio value.

- [ ] 6.5 Run AWS synthetic monitoring
  - Run `tests/e2e/aws-synthetic/aws-synthetic.spec.ts` against `https://vibhanshu-ai-portfolio.dev`.
  - Confirm seeding completes before tests execute.