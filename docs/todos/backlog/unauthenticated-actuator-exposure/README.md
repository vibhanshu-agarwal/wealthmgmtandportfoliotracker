# Backlog: Unauthenticated Production Actuator Exposure Guard

**Status:** Open — gateway hardening merged and deployed; live-negative proof/remaining assessment incomplete. Audited 2026-09-26 UTC.
**Owner:** unassigned
**Tracked in:** Task 3.7 production pre-checks, 2026-09-11

---

## What was observed

The Azure-serving `api-gateway` exposed management endpoints publicly because
`management.endpoints.web.exposure.include` was `*` and `/actuator/**` was `permitAll()`.
Fourteen endpoints were readable without authentication. The exposed surface included `heapdump`,
which can contain application secrets, and endpoints including `loggers`, `refresh`, and
`gateway/routes` that accept POST requests.

The application fix merged in PR #254 at `006aa9e6`. The published
[deployment completion record](../../../evidence/b2-task-8-9/deployment-completion-20260911.json)
binds that source to run `34588465283`, gateway revision `0000081` and 100% traffic;
the old "deployment pending" statement is superseded. Current `SecurityConfig` allows public
health and denies other management routes, and `ActuatorExposureSecurityTest` covers sensitive
GET/POST denial. No accepted live negative management-endpoint proof was located in this audit.
Do not infer that the old broad exposure persists, or that runtime denial was independently
verified here. Other-service assessment and a static-guard decision remain open.

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
