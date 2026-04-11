# E2E Remediation Round 2 — Better Auth Login Hydration + Redis

**Date:** 2026-04-11
**Status:** Ready to execute
**Progress:** 6/9 passing (up from 3/9)

---

## Remaining Failures (3 tests)

### Failure 1: Login form still submitting as native GET (Test 1)

**URL after login:** `http://localhost:3000/login?email=dev%40localhost.local&password=password`

The 300ms `waitForTimeout` after `networkidle` is not enough for React hydration in the standalone build. The form's `onSubmit` handler is still not attached when Playwright clicks.

**Fix:** Replace the timing-based approach with a deterministic hydration signal. Add a `data-hydrated="true"` attribute to the login form via `useEffect`, then wait for it in Playwright:

In `frontend/src/app/(auth)/login/page.tsx`:

```tsx
const [hydrated, setHydrated] = useState(false);
useEffect(() => { setHydrated(true); }, []);
// ...
<form onSubmit={handleSubmit} data-hydrated={hydrated ? "true" : undefined} ...>
```

In `frontend/tests/e2e/helpers/auth.ts`:

```ts
await page.waitForSelector('form[data-hydrated="true"]', { timeout: 10_000 });
```

This guarantees React has mounted and the `onSubmit` handler is attached before Playwright interacts with the form.

### Failure 2: total-value not found (Tests 2 and 5)

These cascade from Failure 1. If login fails, the user is never authenticated, `PortfolioPageContent` returns `null`, and `data-testid="total-value"` never appears.

**No independent fix needed** — resolves when Failure 1 is fixed.

### Infrastructure: Redis not running

The API Gateway logs show `Connection refused: localhost/127.0.0.1:6379` — Redis is down. This doesn't directly cause test failures (the gateway still routes requests), but it generates noisy error logs and may cause rate limiting to fail silently.

**Fix:** Start Redis before running tests:

```bash
docker compose up -d redis
```

---

## Remediation Plan

### Step 1: Add hydration signal to Login page

- File: `frontend/src/app/(auth)/login/page.tsx`
- Add `data-hydrated` attribute that flips to `"true"` after React mounts

### Step 2: Update auth helper to wait for hydration signal

- File: `frontend/tests/e2e/helpers/auth.ts`
- Replace `waitForTimeout(300)` with `waitForSelector('form[data-hydrated="true"]')`

### Step 3: Rebuild standalone server

- The Login page change is app code, so a rebuild is required:
  ```bash
  cd frontend
  npm run build
  cp .env.local .next/standalone/.env.local
  npm run start:standalone
  ```

### Step 4: Start Redis

- `docker compose up -d redis`

### Step 5: Run tests

- `cd frontend && npm run test:e2e`

---

## Files to Modify

| File                                     | Change                                                   |
| ---------------------------------------- | -------------------------------------------------------- |
| `frontend/src/app/(auth)/login/page.tsx` | Add `data-hydrated` attribute via useEffect              |
| `frontend/tests/e2e/helpers/auth.ts`     | Wait for `form[data-hydrated="true"]` instead of timeout |
