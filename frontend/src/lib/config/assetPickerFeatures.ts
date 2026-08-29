/**
 * B2 Task 1.1 — build-time feature flags for the Asset Picker's user-facing entry points.
 *
 * `next.config.ts` sets `output: "export"` (a static export, no server), so there is no
 * runtime configuration mechanism for this frontend: `NEXT_PUBLIC_*` values are inlined
 * by the bundler at build time. "Enabling a flag" is therefore always a new
 * build-and-deploy, never a config toggle — Wave 10 owns that mechanics, not this module.
 *
 * Two independent flags, because the picker and the manual-reset control are gated on
 * different, independent conditions (B2 Wave 2's decimal write-safety gate vs. Wave 6's
 * B1-5.1 gate), and requirements.md 7.6 has not decided whether the reset control even
 * lives inside the picker:
 *
 * - `NEXT_PUBLIC_ENABLE_ASSET_PICKER`       gates `EditHoldingsButton` and everything it opens.
 * - `NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL` gates Wave 6's manual-reset control.
 *
 * Both default to disabled whenever unset, so an environment that has not been told about
 * them (a fresh checkout, a workflow not yet updated) stays safe by omission.
 */

/**
 * Exact-match flag parse — enabled iff the normalized value is exactly `"true"`.
 *
 * Deliberately NOT a truthiness check: a `NEXT_PUBLIC_*` value is always a string at
 * runtime, so `if (rawValue)` would treat the rollback value `"false"` as truthy and
 * defeat Wave 10's own rollback mechanism the moment it was used.
 */
export function parseFeatureFlag(rawValue: string | undefined): boolean {
  return rawValue?.trim().toLowerCase() === "true";
}

/**
 * Whether B2's Asset Picker entry point is built in.
 *
 * The `process.env.NEXT_PUBLIC_*` member expression is written out literally so the
 * Next.js bundler can substitute the build-time value — it cannot inline a dynamic
 * lookup such as `process.env[name]`.
 */
export function isAssetPickerEnabled(): boolean {
  return parseFeatureFlag(process.env.NEXT_PUBLIC_ENABLE_ASSET_PICKER);
}

/** Whether Wave 6's manual demo-reset control is built in. */
export function isDemoResetControlEnabled(): boolean {
  return parseFeatureFlag(process.env.NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL);
}
