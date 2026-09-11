# Backlog: Unauthenticated Production Actuator Exposure Guard

**Status:** Open — application hardening is in PR #254; deployment remains pending
**Owner:** unassigned
**Tracked in:** Task 3.7 production pre-checks, 2026-09-11

---

## What was observed

The Azure-serving `api-gateway` exposed management endpoints publicly because
`management.endpoints.web.exposure.include` was `*` and `/actuator/**` was `permitAll()`.
Fourteen endpoints were readable without authentication. The exposed surface included `heapdump`,
which can contain application secrets, and endpoints including `loggers`, `refresh`, and
`gateway/routes` that accept POST requests.

The application fix is in PR #254. The production exposure remains until that fix is deployed and
read back from the serving revision.

## Follow-up

1. Assess whether the other three Container Apps, despite `external_ingress = false`, need the same
   hardening.
2. Decide whether `static-guard` should reject broad actuator exposure together with unauthenticated
   actuator authorization.
3. After an explicitly approved deployment, verify the serving gateway no longer exposes the
   management surface publicly.

## Non-claims

- Does not authorize deployment, a production probe, or an actuator write request.
- Does not claim that the other Container Apps are exposed.
