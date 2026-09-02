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
