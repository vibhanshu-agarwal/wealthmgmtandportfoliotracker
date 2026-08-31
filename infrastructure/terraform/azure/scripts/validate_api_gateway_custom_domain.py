#!/usr/bin/env python3
"""Read-only preflight and post-bind validation for api-gateway custom-domain recovery.

Pure functions accept sanitized JSON projections from the workflow — never raw Container App
objects, secrets, or caller-supplied certificate IDs.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from typing import Any

EXPECTED_HOSTNAME = "api.vibhanshu-ai-portfolio.dev"
EXPECTED_CERTIFICATE_NAME = "mc-wealth-prod-ac-api-vibhanshu-ai-5159"
EXPECTED_TARGET_PORT = 8080


class CustomDomainValidationError(ValueError):
    pass


def _require_dict(value: Any, label: str) -> dict:
    if not isinstance(value, dict):
        raise CustomDomainValidationError(f"{label} must be a JSON object.")
    return value


def _require_list(value: Any, label: str) -> list:
    if not isinstance(value, list):
        raise CustomDomainValidationError(f"{label} must be a JSON array.")
    return value


def _ingress(app: dict) -> dict:
    ingress = app.get("ingress")
    if not isinstance(ingress, dict):
        raise CustomDomainValidationError("app ingress projection is missing or invalid.")
    return ingress


def _custom_domains(app: dict) -> list[dict]:
    domains = app.get("customDomains")
    if domains is None:
        return []
    if not isinstance(domains, list):
        raise CustomDomainValidationError("app customDomains must be null or a JSON array.")
    for entry in domains:
        if not isinstance(entry, dict):
            raise CustomDomainValidationError("each customDomains entry must be a JSON object.")
    return domains


def _traffic_ok(traffic: Any) -> bool:
    if not isinstance(traffic, list) or len(traffic) != 1:
        return False
    weight = traffic[0]
    if not isinstance(weight, dict):
        return False
    latest = weight.get("latestRevision")
    if latest is None:
        latest = weight.get("latest_revision")
    percentage = weight.get("weight")
    if percentage is None:
        percentage = weight.get("percentage")
    return latest is True and percentage == 100


def _ingress_contract_ok(app: dict) -> None:
    ingress = _ingress(app)
    if ingress.get("external") is not True:
        raise CustomDomainValidationError("gateway ingress must be external.")
    allow_insecure = ingress.get("allowInsecure")
    if allow_insecure is None:
        allow_insecure = ingress.get("allow_insecure_connections")
    if allow_insecure is not False:
        raise CustomDomainValidationError("gateway ingress must keep allowInsecure=false.")
    target_port = ingress.get("targetPort")
    if target_port is None:
        target_port = ingress.get("target_port")
    if target_port != EXPECTED_TARGET_PORT:
        raise CustomDomainValidationError(
            f"gateway ingress target port must be {EXPECTED_TARGET_PORT}."
        )
    transport = ingress.get("transport")
    if not isinstance(transport, str) or transport.lower() != "auto":
        raise CustomDomainValidationError("gateway ingress transport must be Auto.")
    traffic = ingress.get("traffic")
    if traffic is None:
        traffic = ingress.get("traffic_weight")
    if not _traffic_ok(traffic):
        raise CustomDomainValidationError(
            "gateway ingress must route 100% to the latest revision."
        )


def _resolve_certificate(certificates: list[dict]) -> dict:
    matches = [
        cert
        for cert in certificates
        if isinstance(cert, dict) and cert.get("name") == EXPECTED_CERTIFICATE_NAME
    ]
    if len(matches) != 1:
        raise CustomDomainValidationError(
            f"expected exactly one managed certificate named {EXPECTED_CERTIFICATE_NAME!r}."
        )
    cert = matches[0]
    subject = cert.get("subjectName")
    if subject is None:
        subject = cert.get("subject_name")
    if subject != EXPECTED_HOSTNAME:
        raise CustomDomainValidationError(
            f"certificate subject must be {EXPECTED_HOSTNAME!r}."
        )
    state = cert.get("provisioningState")
    if state is None:
        state = cert.get("provisioning_state")
    if state != "Succeeded":
        raise CustomDomainValidationError("certificate provisioning state must be Succeeded.")
    method = cert.get("validationMethod")
    if method is None:
        method = cert.get("validation_method")
    if method != "CNAME":
        raise CustomDomainValidationError("certificate validation method must be CNAME.")
    cert_id = cert.get("id")
    if not isinstance(cert_id, str) or not cert_id:
        raise CustomDomainValidationError("certificate id is missing.")
    return cert


def validate_restore_preflight(
    app: dict,
    certificates: list[dict],
    cname_target: str,
    txt_values: list[str],
) -> dict[str, str]:
    app = _require_dict(app, "app")
    certificates = _require_list(certificates, "certificates")
    if not isinstance(cname_target, str) or not cname_target.strip():
        raise CustomDomainValidationError("cname_target must be a non-empty string.")
    if not isinstance(txt_values, list) or not txt_values:
        raise CustomDomainValidationError("txt_values must be a non-empty array.")

    _ingress_contract_ok(app)
    if _custom_domains(app):
        raise CustomDomainValidationError(
            "customDomains must be empty before restore; an existing binding requires a "
            "separate idempotency review."
        )

    cert = _resolve_certificate(certificates)
    verification_id = app.get("customDomainVerificationId")
    if verification_id is None:
        verification_id = app.get("custom_domain_verification_id")
    if not isinstance(verification_id, str) or not verification_id:
        raise CustomDomainValidationError("customDomainVerificationId is missing.")

    fqdn = app.get("fqdn")
    if not isinstance(fqdn, str) or not fqdn:
        raise CustomDomainValidationError("gateway fqdn is missing.")
    if cname_target.strip().rstrip(".") != fqdn.rstrip("."):
        raise CustomDomainValidationError(
            "public DNS CNAME must resolve directly to the gateway ACA FQDN."
        )
    if verification_id not in txt_values:
        raise CustomDomainValidationError(
            "asuid TXT record must contain the live customDomainVerificationId."
        )

    revision = app.get("latestRevisionName")
    if revision is None:
        revision = app.get("latest_revision_name")
    if not isinstance(revision, str) or not revision:
        raise CustomDomainValidationError("latestRevisionName is missing.")

    app_id = app.get("id")
    if not isinstance(app_id, str) or not app_id:
        raise CustomDomainValidationError("gateway resource id is missing.")

    return {
        "certificate_id": cert["id"],
        "gateway_id": app_id,
        "revision_name": revision,
        "default_fqdn": fqdn,
        "hostname": EXPECTED_HOSTNAME,
        "verification_id": verification_id,
    }


def validate_remove_preflight(app: dict, certificates: list[dict]) -> dict[str, str]:
    app = _require_dict(app, "app")
    certificates = _require_list(certificates, "certificates")
    cert = _resolve_certificate(certificates)
    domains = _custom_domains(app)
    if len(domains) != 1:
        raise CustomDomainValidationError(
            "remove preflight requires exactly one bound custom domain."
        )
    domain = domains[0]
    name = domain.get("name")
    if name != EXPECTED_HOSTNAME:
        raise CustomDomainValidationError(
            f"bound hostname must be {EXPECTED_HOSTNAME!r}."
        )
    binding_type = domain.get("bindingType")
    if binding_type is None:
        binding_type = domain.get("binding_type")
    if binding_type != "SniEnabled":
        raise CustomDomainValidationError("bound domain must use SniEnabled.")
    certificate_id = domain.get("certificateId")
    if certificate_id is None:
        certificate_id = domain.get("certificate_id")
    if certificate_id != cert["id"]:
        raise CustomDomainValidationError(
            "bound certificate id must match the expected managed certificate."
        )
    app_id = app.get("id")
    if not isinstance(app_id, str) or not app_id:
        raise CustomDomainValidationError("gateway resource id is missing.")
    return {
        "certificate_id": cert["id"],
        "hostname": EXPECTED_HOSTNAME,
        "gateway_id": app_id,
    }


def validate_post_bind(
    app: dict,
    certificates: list[dict],
    expected: dict[str, str],
    *,
    tls_evidence: str,
) -> None:
    app = _require_dict(app, "app")
    certificates = _require_list(certificates, "certificates")
    expected = _require_dict(expected, "expected")

    _ingress_contract_ok(app)
    cert = _resolve_certificate(certificates)

    expected_cert_id = expected.get("certificate_id")
    if not isinstance(expected_cert_id, str) or not expected_cert_id:
        raise CustomDomainValidationError("expected certificate_id is missing.")
    if cert["id"] != expected_cert_id:
        raise CustomDomainValidationError("certificate id drifted from preflight capture.")

    domains = _custom_domains(app)
    if len(domains) != 1:
        raise CustomDomainValidationError("post-bind customDomains must contain one entry.")
    domain = domains[0]
    if domain.get("name") != EXPECTED_HOSTNAME:
        raise CustomDomainValidationError("post-bind hostname mismatch.")
    binding_type = domain.get("bindingType")
    if binding_type is None:
        binding_type = domain.get("binding_type")
    if binding_type != "SniEnabled":
        raise CustomDomainValidationError("post-bind binding type must be SniEnabled.")
    certificate_id = domain.get("certificateId")
    if certificate_id is None:
        certificate_id = domain.get("certificate_id")
    if certificate_id != expected_cert_id:
        raise CustomDomainValidationError("post-bind certificate id mismatch.")

    expected_revision = expected.get("revision_name")
    if not isinstance(expected_revision, str) or not expected_revision:
        raise CustomDomainValidationError("expected revision_name is missing.")
    latest = app.get("latestRevisionName")
    if latest is None:
        latest = app.get("latest_revision_name")
    latest_ready = app.get("latestReadyRevisionName")
    if latest_ready is None:
        latest_ready = app.get("latest_ready_revision_name")
    if latest != expected_revision or latest_ready != expected_revision:
        raise CustomDomainValidationError(
            "gateway revision changed during restore; no new revision was authorized."
        )

    validate_tls_certificate_evidence(tls_evidence)


def _parse_openssl_asn1_time(value: str) -> datetime:
    cleaned = value.strip()
    if not cleaned:
        raise CustomDomainValidationError("TLS certificate date is missing.")
    try:
        return datetime.strptime(cleaned, "%b %d %H:%M:%S %Y %Z").replace(tzinfo=timezone.utc)
    except ValueError as exc:
        raise CustomDomainValidationError("TLS certificate date is malformed.") from exc


def _extract_subject_cn(subject_line: str) -> str:
    match = re.search(r"(?:^subject=\s*|/)\s*CN\s*=\s*([^,/]+)", subject_line.strip())
    if not match:
        raise CustomDomainValidationError("TLS certificate subject CN is missing.")
    return match.group(1).strip()


def _extract_san_dns_names(evidence: str) -> list[str]:
    names: list[str] = []
    for line in evidence.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        if stripped.startswith("DNS:"):
            names.append(stripped.removeprefix("DNS:").strip())
            continue
        for token in stripped.split(","):
            token = token.strip()
            if token.startswith("DNS:"):
                names.append(token.removeprefix("DNS:").strip())
    return names


def parse_openssl_x509_evidence(evidence: str) -> dict[str, Any]:
    if not isinstance(evidence, str) or not evidence.strip():
        raise CustomDomainValidationError("TLS certificate evidence is missing.")

    subject_line = ""
    not_before_line = ""
    not_after_line = ""
    for line in evidence.splitlines():
        stripped = line.strip()
        if stripped.startswith("subject="):
            subject_line = stripped
        elif stripped.startswith("notBefore="):
            not_before_line = stripped
        elif stripped.startswith("notAfter="):
            not_after_line = stripped

    if not subject_line or not not_before_line or not not_after_line:
        raise CustomDomainValidationError("TLS certificate evidence is malformed.")

    return {
        "subject_cn": _extract_subject_cn(subject_line),
        "san_dns_names": _extract_san_dns_names(evidence),
        "not_before": _parse_openssl_asn1_time(not_before_line.removeprefix("notBefore=").strip()),
        "not_after": _parse_openssl_asn1_time(not_after_line.removeprefix("notAfter=").strip()),
    }


def validate_tls_certificate_evidence(
    evidence: str,
    *,
    expected_hostname: str = EXPECTED_HOSTNAME,
    reference_time: datetime | None = None,
) -> None:
    parsed = parse_openssl_x509_evidence(evidence)
    if parsed["subject_cn"] != expected_hostname:
        raise CustomDomainValidationError(
            f"TLS certificate subject must be {expected_hostname!r}."
        )
    san_names = parsed["san_dns_names"]
    if expected_hostname not in san_names:
        raise CustomDomainValidationError(
            f"TLS certificate SAN must include {expected_hostname!r}."
        )
    now = reference_time or datetime.now(timezone.utc)
    if now < parsed["not_before"]:
        raise CustomDomainValidationError("TLS certificate is not yet valid.")
    if now > parsed["not_after"]:
        raise CustomDomainValidationError("TLS certificate is expired.")


def validate_remove_post(app: dict, certificates: list[dict]) -> None:
    app = _require_dict(app, "app")
    certificates = _require_list(certificates, "certificates")
    if _custom_domains(app):
        raise CustomDomainValidationError("custom domain must be absent after remove.")
    _resolve_certificate(certificates)


def _write_github_output(name: str, value: str) -> None:
    output_path = __import__("os").environ.get("GITHUB_OUTPUT")
    if not output_path:
        return
    with open(output_path, "a", encoding="utf-8") as handle:
        handle.write(f"{name}<<EOF\n{value}\nEOF\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("restore-preflight", "remove-preflight", "post-bind", "remove-post"))
    parser.add_argument("--app-json", required=True)
    parser.add_argument("--certificates-json", default="[]")
    parser.add_argument("--cname-target", default="")
    parser.add_argument("--txt-values-json", default="[]")
    parser.add_argument("--expected-json", default="{}")
    parser.add_argument("--default-health-status", default="")
    parser.add_argument("--custom-health-status", default="")
    parser.add_argument("--tls-evidence", default="")
    args = parser.parse_args()

    try:
        app = json.loads(args.app_json)
        certificates = json.loads(args.certificates_json)
        txt_values = json.loads(args.txt_values_json)
        expected = json.loads(args.expected_json)
    except json.JSONDecodeError as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 1

    try:
        if args.mode == "restore-preflight":
            result = validate_restore_preflight(app, certificates, args.cname_target, txt_values)
            for key, value in result.items():
                print(f"{key}={value}")
                _write_github_output(key, value)
        elif args.mode == "remove-preflight":
            result = validate_remove_preflight(app, certificates)
            for key, value in result.items():
                print(f"{key}={value}")
                _write_github_output(key, value)
        elif args.mode == "post-bind":
            if not args.tls_evidence.strip():
                raise CustomDomainValidationError(
                    "TLS certificate evidence is required for post-bind."
                )
            validate_post_bind(
                app,
                certificates,
                expected,
                tls_evidence=args.tls_evidence,
            )
            if args.default_health_status != "200":
                raise CustomDomainValidationError(
                    "default ACA /actuator/health must return HTTP 200."
                )
            if args.custom_health_status != "200":
                raise CustomDomainValidationError(
                    "custom domain /actuator/health must return HTTP 200."
                )
            print("post-bind validation passed")
        else:
            validate_remove_post(app, certificates)
            print("remove-post validation passed")
    except CustomDomainValidationError as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
