# B2 Wave 6 — `ManualResetControl` visual evidence

Real screenshots (not descriptions) captured with Playwright against the local mock preview
described in [PR #214](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/214)
— no backend, a fake session, and a fetch mock installed at app-load time (temporary, env-gated,
never shipped). `NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL=true`, `NEXT_PUBLIC_MOCK_DEMO=true`.

| File | State |
|---|---|
| `01-idle-dark-wide.png` | Idle — dark theme, 1280px |
| `02-success-dark-wide.png` | Success (`Demo portfolio reset.`) — dark theme, 1280px |
| `03-conflict-dark-wide.png` | Conflict (`409`), draft-free notice + "Refresh & try again" — dark theme, 1280px |
| `04-refresh-recovered-dark-wide.png` | After a successful refresh, back to idle — dark theme, 1280px |
| `05-failure-dark-wide.png` | Generic failure (`503`), `Reset failed. You can try again.` — dark theme, 1280px |
| `06-submitting-dark-wide.png` | Submitting (`disabled`, `Resetting…`), captured by holding the mocked PUT open indefinitely — dark theme, 1280px |
| `07-idle-light-wide.png` | Idle — light theme, 1280px |
| `08-success-light-wide.png` | Success — light theme, 1280px |
| `09-conflict-light-wide.png` | Conflict — light theme, 1280px |
| `10-idle-dark-narrow-375.png` | Idle at 375×812 — dark theme. Shows the pre-existing sidebar clipping tracked in [`docs/todos/backlog/responsive-dashboard-sidebar/`](../../todos/backlog/responsive-dashboard-sidebar/README.md), unrelated to this control. |
| `11-idle-light-narrow-375.png` | Idle at 375×812 — light theme. Same clipping. |
| `12-hover-light-wide.png` | Enabled button, hovered — light theme, 1280px. New in the text/hover contrast fix below. |

## Text/hover contrast fix

A follow-up review measured four states below WCAG 2.2 SC 1.4.3's 4.5:1 minimum for small text:
success text (light, 3.51:1), conflict/failure text (light, 3.52:1; dark, 4.32:1), and the button's
hovered state (2.59:1, both themes — the shared `Button` `outline` variant's `hover:bg-accent
hover:text-accent-foreground` resolves to white-on-accent-green at that size). Fixed with a local,
scoped change in `ManualResetControl.tsx` only (no shared `Button`/token edits): feedback text moved
from `text-emerald-600`/`text-destructive` to `text-emerald-700`/`text-red-700 dark:text-red-400`,
and the two buttons gained a local `hover:bg-emerald-700` override. Re-measured via
`getComputedStyle` + the WCAG relative-luminance formula against each control's actual rendered
background: success 5.11:1 (light) / ~10:1 (dark, unchanged), conflict/failure 6.04:1 (light) /
6.97:1 (dark), hover 5.48:1 (both themes — `--accent-foreground` is white in both). Screenshots
`03`, `05`, `08`, `09` were re-captured against the fixed colors; `12` is new.
