#!/bin/bash
# SessionEnd hook — clears any Azure CLI delegated token before the session ends.
#
# Entra interactive login leaves a refresh token in ~/.azure that can act as the
# signed-in user against production Azure. Container teardown destroys it, but
# teardown is not immediate and is not guaranteed to be observed. Logging out
# here clears the token cache deterministically.
#
# Never fails the session: every branch exits 0.
set -uo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

AZ=""
for candidate in "$(command -v az 2>/dev/null || true)" /tmp/azvenv/bin/az; do
  if [ -n "$candidate" ] && [ -x "$candidate" ]; then AZ="$candidate"; break; fi
done

if [ -z "$AZ" ]; then
  exit 0
fi

if "$AZ" account show >/dev/null 2>&1; then
  echo "SessionEnd: active Azure CLI session found; logging out."
  "$AZ" logout >/dev/null 2>&1 || true
  # Belt and braces: clear the MSAL token cache even if logout misbehaved.
  rm -f "$HOME/.azure/msal_token_cache.json" "$HOME/.azure/accessTokens.json" 2>/dev/null || true
  echo "SessionEnd: Azure CLI logged out and token cache cleared."
else
  echo "SessionEnd: no active Azure CLI session; nothing to clear."
fi

exit 0
