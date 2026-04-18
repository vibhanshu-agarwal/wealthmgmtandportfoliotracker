# Backlog: [E2E] Resolve `total-value` Visibility and NextAuth Hydration in Standalone Build

**Status:** ✅ Closed (2026-04-11)  
**Priority:** Medium  
**Area:** Frontend / E2E Testing (Playwright + NextAuth)  
**Resolution:** Root cause was the Next.js standalone build missing `.next/static/` (JS bundles), `public/`, and `.env.local`. Without JS bundles, React never hydrated, so `useSession()` never ran and the page stayed as static server-rendered HTML with permanent skeletons. Fixed by updating `start:standalone` in `package.json` to copy all three assets before starting the server. See `docs/changes/CHANGES_INFRA_SUMMARY_2026-04-11_v1.md`.

---

## Summary

E2E tests 4 and 5 (`dashboard-data` and `golden-path`) are timing out waiting for
`[data-testid="total-value"]` to become visible. The backend is verified 100% functional,
and the database is seeding correctly. The issue is isolated to the React component lifecycle
or NextAuth client hydration inside the standalone build during headless Playwright execution.

---

## What is Verified Working (Do Not Debug)

- **Backend JPA & DB:** Portfolio and nested Holdings are persisting properly with
  `CascadeType.ALL`.
- **API Gateway:** Successfully routes and returns hydrated JSON data via `JsonMapper`.
- **Frontend Authentication:** The Playwright UI login successfully establishes a secure
  `authjs.session-token` cookie.
- **Component Rendering:** The Holdings Table successfully renders the seeded AAPL and BTC
  tickers (Test 9 passes).

---

## The Symptoms

Even with a valid server-side session cookie and a hard `page.reload()` in Playwright, the
`SummaryCards` component does not render the `total-value` element within the 30-second timeout.

---

## Attempted Fixes (Preserve these notes)

1. **The Session Gate:** Gated `PortfolioPageContent` behind `status === "authenticated"` to
   prevent TanStack Query from firing without a token.

2. **Hard Reload:** Added `page.reload({ waitUntil: "networkidle" })` after login to force SSR
   hydration of the NextAuth cookie.

3. **Provider Seeding:** Passed the server `auth()` session directly into
   `<SessionProvider session={session}>` in the root layout.

4. **Standalone Env Vars:** Added `AUTH_TRUST_HOST=true` to `.env.local` to prevent NextAuth
   from dropping cookies outside of Vercel.

---

## Fresh Approach / Next Steps for Investigation

When revisiting, focus on these three isolated areas before changing NextAuth logic:

1. **Endpoint Audit:** Verify that the TanStack Query inside `SummaryCards` is calling the new
   Phase 3 `GET /api/portfolio/analytics` endpoint. If it is still calling `/summary`, it may
   be failing silently.

2. **DOM Audit:** Confirm that the `data-testid="total-value"` attribute actually exists in the
   compiled `.next/standalone` output, and wasn't placed on a conditionally hidden sub-component.

3. **Environment Isolation:** Run `npm run test:e2e` against the `npm run dev` hot-reloading
   server instead of the standalone build to definitively prove if this is a Next.js compilation
   bug or a Playwright timing bug.

---

## Files Involved

| File                                                         | Role                                                |
| ------------------------------------------------------------ | --------------------------------------------------- |
| `frontend/src/components/portfolio/SummaryCards.tsx`         | Renders `total-value`; skeleton logic               |
| `frontend/src/components/portfolio/PortfolioPageContent.tsx` | Session gate wrapping data components               |
| `frontend/src/lib/hooks/usePortfolio.ts`                     | `usePortfolioSummary` and `usePortfolio` hooks      |
| `frontend/src/lib/apiService.ts`                             | `fetchPortfolioSummary` fetch function              |
| `frontend/src/app/layout.tsx`                                | Server-side session pre-fetch for `SessionProvider` |
| `frontend/tests/e2e/helpers/auth.ts`                         | CSRF + credentials POST login helper                |
| `frontend/tests/e2e/golden-path.spec.ts`                     | Failing test (total-value assertion)                |
| `frontend/tests/e2e/dashboard-data.spec.ts`                  | Failing test (total-value assertion)                |
| `frontend/.env.local`                                        | `AUTH_TRUST_HOST=true`, `AUTH_URL`                  |
