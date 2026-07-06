# Feature Spec: New User Signup & Profile

Referenced from [`roadmap_enhancements_v3.md`](./roadmap_enhancements_v3.md), Section 3.1.
Priority 2 in the updated matrix.

## 1. Problem Statement

v1 described this item as "transition from stateless, edge-only JWT minting to a persisted user
model." That description is out of date in a specific way that changes the scope of the work: a
persisted-user-model library (Better Auth) **is already integrated** in the codebase, with its own
Postgres schema and seed data — but it is not reachable from the deployed application, and the
login path that *is* reachable today supports exactly **one** hardcoded credential pair, not a
general user base. This spec documents the verified current state end-to-end, the structural
reason it's stuck, and the decision points that need to be resolved before "add signup" can be
turned into an implementation plan.

## 2. Current State (verified end-to-end, file-by-file)

### 2.1 The login flow that actually runs today

`frontend/src/app/(auth)/login/page.tsx` is the only page under `(auth)/`. It is pre-filled with
demo credentials (`NEXT_PUBLIC_DEMO_EMAIL` / `NEXT_PUBLIC_DEMO_PASSWORD`, injected at build time —
explicitly for "recruiters can sign in with a single click," per its own comment) and calls
`loginWithBackend(email, password)` from `frontend/src/lib/auth/session.ts`, which does:

```ts
response = await fetch(apiPath("/auth/login"), {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ email, password }),
});
```

This resolves to `POST /api/auth/login` on the `api-gateway`, handled by
**`api-gateway/src/main/java/com/wealth/gateway/AuthController.java`**:

```java
@PostMapping("/login")
public ResponseEntity<?> login(@RequestBody LoginDtos.LoginRequest request) {
    if (!authEmail.equals(request.email()) || !authPassword.equals(request.password())) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)...
    }
    String token = jwtSigner.signHs256(authUserId, authEmail, authName);
    return ResponseEntity.ok(new LoginDtos.LoginResponse(token, authUserId, authEmail, authName));
}
```

`authEmail`, `authPassword`, `authUserId`, and `authName` are bound from
`app.auth.{email,password,user-id,name}` in `application.yml` — a **single** configured
credential pair (default `dev@localhost.local` / `password`, user id `user-001`), checked by plain
string equality. There is no database lookup, no per-user record, and no way for a second distinct
credential pair to succeed. The JWT is minted server-side by `JwtSigner` (Nimbus JOSE, HS256, 1-hour
expiry, claims `sub`/`email`/`name`) — this is the "edge, stateless" minting v1 referred to, and it
is accurate as far as it goes: it really is stateless, because there is no user table involved at
all in this path.

Session storage on the client is a hand-rolled `localStorage` blob
(`frontend/src/lib/auth/session.ts`, key `wmpt.auth.session`), read by `useAuthSession()` and used
by every dashboard page via `fetchWithAuthClient`. On `401`, the session is cleared and the user is
redirected to `/login`.

### 2.2 Better Auth exists in the codebase but is not wired into any route

`frontend/src/lib/auth.ts` configures a full Better Auth instance:

```ts
export const auth = betterAuth({
  database: new Pool({ connectionString: process.env.DATABASE_URL }),
  emailAndPassword: { enabled: true },
  session: { modelName: "ba_session", ... },
  user: { modelName: "ba_user" },
  account: { modelName: "ba_account" },
  verification: { modelName: "ba_verification" },
});
```

`emailAndPassword.enabled: true` means Better Auth's own sign-up endpoint
(`POST /api/auth/sign-up/email`) would work *if it were exposed*. It isn't:
`frontend/src/app` contains **no `api/` directory and no `route.ts` file anywhere** (verified by a
full recursive listing) — there is no Next.js catch-all handler (the conventional
`app/api/auth/[...all]/route.ts`) that would mount Better Auth's HTTP API. `auth-client.ts`
(`createAuthClient()`, exporting `useSession`/`signIn`/`signOut`) is likewise never imported by the
login page or anywhere else that runs in the browser.

The only code that calls into Better Auth's session API is
`frontend/src/lib/api/fetchWithAuth.server.ts` (`auth.api.getSession(...)`), and its only real
caller is its own test file — it is not invoked from any dashboard page. `mintToken.ts` (which
would turn a Better Auth session into an HS256 JWT matching the gateway's expectations) has the
same profile: fully implemented and unit-tested, never called from a live path.

### 2.3 Why this is stuck: the frontend is a static export

`frontend/next.config.ts`:

```ts
const nextConfig: NextConfig = {
  output: "export", // Static export for S3/Static Web Apps hosting; `npm run build` emits `frontend/out/`
};
```

This is the structural reason Better Auth's server routes can't simply be "turned on." A static
export produces plain HTML/JS/CSS with **no Next.js server process at runtime** — there is nowhere
for `app/api/auth/[...all]/route.ts` to run in production (Azure Static Web Apps free tier /
S3 both serve static files only). `auth.ts`'s direct Postgres connection and `auth.api.getSession`
calls are server-only code that can, at most, execute during the build's static-generation pass —
not per-request against a live browser session. This is not a wiring oversight; it's a genuine
architectural mismatch between "Better Auth as designed" (needs a live Node server) and "how this
frontend is actually deployed" (static export, no server). Any real signup implementation has to
resolve this mismatch explicitly — see Section 4.

### 2.4 Two parallel "user" tables already exist in Postgres, kept in sync only by hand

- **`users`** (Flyway `V1__Initial_Schema.sql`): `id UUID`, `email`, `created_at` — nothing else.
  `PortfolioService.requireUserExists()` (Java) queries **this** table, and `portfolios.user_id`
  (not FK-constrained) must match a row here for any portfolio API call to succeed.
- **`ba_user` / `ba_session` / `ba_account` / `ba_verification`** (Flyway `V8__Better_Auth_Schema.sql`):
  Better Auth's own schema, created "additively" — the migration comment states it "does NOT
  modify any existing Flyway-managed tables."

Nothing in the codebase provisions a `users` row when a `ba_user` row is created, or vice versa.
The only place both tables are populated together is in **seed migrations**, done by hand:
`V9__Seed_Better_Auth_Dev_User.sql` seeds only `ba_user`/`ba_account` (the dev user), while
`V10__Seed_E2E_Test_User.sql` explicitly seeds **both** `users` and `ba_user`/`ba_account` for the
E2E test user — its own comment explains why: *"portfolios.user_id is not FK-constrained, but
`PortfolioService.requireUserExists()` queries \[the `users`\] table at the application layer, so
every authenticated API call... needs the E2E user to exist here too — not just in `ba_user`."*
This is the exact reconciliation problem a real signup flow must solve: today it's done once, by
hand, in a migration. A live signup needs it to happen automatically, atomically, for every new
user.

Separately, `V7__Fix_Portfolio_User_Id_To_UUID.sql` is a one-off historical patch that reassigned a
seeded portfolio from the string `'user-001'` to the dev user's Better Auth UUID — further evidence
that these two identity concepts have already caused at least one manual data-fix in this repo's
history.

### 2.5 No profile fields exist beyond the bare minimum

`ba_user` has `name` and `image`, but `image` is not used anywhere in the UI. No risk tolerance, no
preferences, no avatar upload flow. (Personalization built on top of a real profile is tracked
separately — `roadmap_enhancements_v3.md` Section 3.2 — and depends on this item landing first.)

## 3. What "genuinely doesn't exist" vs. "exists but disconnected"

To avoid re-litigating settled work, here's the split:

| Piece | Status |
|---|---|
| Postgres schema for a real user model | **Exists** (`ba_user` et al., Flyway V8) |
| A library that can hash passwords, issue sessions, verify email/password | **Exists** (Better Auth, `emailAndPassword.enabled: true`) |
| An HTTP route exposing that library to a browser | **Does not exist** (no `app/api` directory) |
| A signup/registration UI | **Does not exist** (no page anywhere) |
| A live login path for more than one credential pair | **Does not exist** (hardcoded single check in `AuthController`) |
| Automatic reconciliation between `users` and `ba_user` on account creation | **Does not exist** (manual, migration-only today) |
| A deployment model that can run Better Auth's server routes as currently configured | **Does not exist** (`output: "export"` — no Next.js server at runtime) |

## 4. Decision Points (product/architecture calls, not filled in here)

This spec deliberately stops short of prescribing an implementation, because the right answer
depends on constraints (hosting cost, whether the static-export/Azure-Static-Web-Apps choice is
negotiable, how much of Better Auth's existing schema work should be kept) that aren't decidable
from the code alone.

1. **Keep the frontend static, and make the gateway the real auth server** — extend
   `AuthController` (or a new controller) to do a real per-user database lookup (against either
   `users` extended with a password-hash column, or `ba_user`/`ba_account` read directly from
   Java/JDBC) instead of the single hardcoded pair, while retaining `output: "export"`. This keeps
   the current deployment model (no live Node server needed) but leaves Better Auth's Node-side
   library code (`auth.ts`, `auth-client.ts`, `mintToken.ts`) unused — likely removed to avoid two
   parallel, half-built auth systems in the same repo.
2. **Move the frontend off static export** so Better Auth's own `app/api/auth/[...all]/route.ts`
   can be added and actually run per-request, keeping the existing `ba_user` schema and Better
   Auth's built-in sign-up endpoint as the real implementation. This is a larger change: it
   affects hosting (Azure Static Web Apps' free tier and the current S3-based AWS standby path
   both assume static files) and would need its own deployment/cost analysis.
3. **Either way**, a decision is needed on the two-tables problem (Section 2.4): does `ba_user`
   become the single source of truth (with `users` dropped or turned into a view/FK'd), or does
   the legacy `users` table stay authoritative and Better Auth's tables get dropped instead?
4. **Whichever direction is chosen**, new-user provisioning must be atomic: creating a login-
   capable account and creating the corresponding `portfolio-service`-visible user row (so
   `requireUserExists()` succeeds) cannot be two independently-failable steps, or new users will
   hit exactly the class of bug `V10`'s comment describes.
5. **Demo/recruiter login** (`NEXT_PUBLIC_DEMO_EMAIL`/`PASSWORD`) is a real, intentional product
   requirement today (README's "Live demo" framing) — whatever replaces the current single-
   credential check needs to preserve a working one-click demo path, not just support arbitrary
   signups.

## 5. Suggested Scope for a First Implementation Pass (once Section 4 is decided)

Regardless of which direction is chosen, the following are implied by the facts above and are not
themselves open questions:

- A real signup UI (currently zero pages exist for this).
- Server-side validation that queries an actual per-user store (replacing the string-equality
  check in `AuthController`, or replacing it entirely with a routed Better Auth endpoint).
- Automatic, atomic provisioning of whatever `portfolio-service` needs to recognize the new user
  (at minimum, a `users` row it can `requireUserExists()` against) at signup time — not a
  follow-up migration.
- Test coverage mirroring the existing pattern (`.kiro/specs/auth-identity-layer`,
  `docs/specs/better-auth-migration`) — this repo already has a convention of requirements/design/
  tasks specs per feature and Testcontainers-backed integration tests; a signup feature should
  follow the same shape given the amount of prior auth churn (NextAuth → Better Auth → still not
  wired) this area has already seen.
