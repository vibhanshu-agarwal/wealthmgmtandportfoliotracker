# Login Page — Infrastructure Notice Banner

## Summary

Add a dismissible infrastructure notice banner to the login page to inform users of current AWS serverless concurrency throttling and the pending multi-cloud migration contingency.

---

## Banner Text

> **Infrastructure Notice:** The backend microservices for this application are currently experiencing strict serverless concurrency throttling on AWS. A quota increase request is pending, alongside a contingency plan to migrate to Azure Container Apps. During this window, you may experience timeouts or empty data views. Thank you for your patience while this multi-cloud architecture is optimized.

---

## File to Change

**`frontend/src/app/(auth)/login/page.tsx`**

This is the only file that needs to change. No new components, no new dependencies.

---

## Required Changes

### 1. Add a banner block above the login card

Insert a full-width warning banner **above** the `<div className="w-full max-w-sm ...">` card, but still inside the `<main>` element. The banner should:

- Span the full width of the viewport (not constrained to `max-w-sm`)
- Use a **yellow/amber warning** color scheme to signal a non-critical but important notice (e.g. Tailwind `bg-amber-50 border-amber-300 text-amber-900` in light mode)
- Display a warning icon (⚠️ or an SVG icon) to the left of the text
- Show the full notice text
- Be **dismissible** — include an `×` close button that hides the banner (local React state, no persistence needed)

### 2. Layout adjustment

The `<main>` element currently uses `items-center justify-center` which centers a single child. With the banner added, the layout should switch to a **column flex** so the banner sits at the top and the login card remains vertically centered in the remaining space.

Suggested approach:
- Change `<main>` to `flex-col` with the banner as the first child
- Wrap the login card in a `flex flex-1 items-center justify-center` container so it stays centered below the banner

### 3. State

Add a single piece of local state:

```ts
const [bannerVisible, setBannerVisible] = useState(true);
```

Conditionally render the banner only when `bannerVisible` is `true`. The close button calls `setBannerVisible(false)`.

---

## Accessibility Requirements

- The banner element should carry `role="alert"` so screen readers announce it on page load
- The close button must have an `aria-label="Dismiss notice"` attribute
- Color contrast between `text-amber-900` and `bg-amber-50` meets WCAG AA (ratio ≈ 7.5:1)

---

## No-Change Scope

The following are explicitly **out of scope** for this change:

- No new component files — the banner is inline in `login/page.tsx`
- No changes to any other page or layout
- No persistence of the dismissed state (localStorage, cookies, etc.)
- No backend changes
- No changes to Tailwind config or global CSS

---

## Visual Sketch

```
┌─────────────────────────────────────────────────────────────────┐
│ ⚠  Infrastructure Notice: The backend microservices for this…  × │  ← amber banner, full width
└─────────────────────────────────────────────────────────────────┘

                    ┌──────────────────────┐
                    │  Sign in             │
                    │  Email ____________  │
                    │  Password _________  │
                    │  [ Sign in ]         │
                    └──────────────────────┘
```

---

## Implementation Checklist

- [ ] Add `bannerVisible` state (`useState(true)`)
- [ ] Restructure `<main>` to `flex flex-col`
- [ ] Add banner `<div>` with `role="alert"`, amber styling, warning icon, notice text, and dismiss button
- [ ] Wrap existing login card in a centering container (`flex flex-1 items-center justify-center`)
- [ ] Verify dismiss button hides the banner without page reload
- [ ] Verify layout looks correct with banner visible and after dismissal
- [ ] Verify screen reader announces the banner on load (`role="alert"`)
