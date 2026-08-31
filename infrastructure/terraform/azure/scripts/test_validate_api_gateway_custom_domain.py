#!/usr/bin/env python3
"""Unit tests for validate_api_gateway_custom_domain.py."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import validate_api_gateway_custom_domain as sut  # noqa: E402

HOSTNAME = sut.EXPECTED_HOSTNAME
CERT_ID = (
    "/subscriptions/sub/resourceGroups/wealth-azure-prod-rg/providers/"
    "Microsoft.App/managedEnvironments/wealth-prod-aca-env/managedCertificates/"
    "mc-wealth-prod-ac-api-vibhanshu-ai-5159"
)
GATEWAY_ID = (
    "/subscriptions/sub/resourceGroups/wealth-azure-prod-rg/providers/"
    "Microsoft.App/containerApps/api-gateway"
)
FQDN = "api-gateway.lemonmoss-ecef29d7.centralindia.azurecontainerapps.io"
VERIFICATION_ID = "public-verification-id-1234"
REVISION = "api-gateway--0000077"
SECRET = "never-print-this-secret-value"


def _ingress(**overrides):
    base = {
        "external": True,
        "allowInsecure": False,
        "targetPort": 8080,
        "transport": "Auto",
        "traffic": [{"latestRevision": True, "weight": 100}],
    }
    base.update(overrides)
    return base


def _app(**overrides):
    base = {
        "id": GATEWAY_ID,
        "fqdn": FQDN,
        "customDomainVerificationId": VERIFICATION_ID,
        "latestRevisionName": REVISION,
        "latestReadyRevisionName": REVISION,
        "ingress": _ingress(),
        "customDomains": None,
    }
    base.update(overrides)
    return base


def _certificate(**overrides):
    base = {
        "name": sut.EXPECTED_CERTIFICATE_NAME,
        "id": CERT_ID,
        "subjectName": HOSTNAME,
        "provisioningState": "Succeeded",
        "validationMethod": "CNAME",
    }
    base.update(overrides)
    return base


def _bound_domain():
    return [{
        "name": HOSTNAME,
        "bindingType": "SniEnabled",
        "certificateId": CERT_ID,
    }]


class ValidateApiGatewayCustomDomainTests(unittest.TestCase):
    def test_restore_preflight_passes(self):
        result = sut.validate_restore_preflight(
            _app(),
            [_certificate()],
            FQDN,
            [VERIFICATION_ID],
        )
        self.assertEqual(result["certificate_id"], CERT_ID)
        self.assertEqual(result["gateway_id"], GATEWAY_ID)
        self.assertEqual(result["revision_name"], REVISION)

    def test_restore_preflight_rejects_existing_binding(self):
        with self.assertRaises(sut.CustomDomainValidationError):
            sut.validate_restore_preflight(
                _app(customDomains=_bound_domain()),
                [_certificate()],
                FQDN,
                [VERIFICATION_ID],
            )

    def test_restore_preflight_rejects_wrong_cname(self):
        with self.assertRaises(sut.CustomDomainValidationError):
            sut.validate_restore_preflight(
                _app(),
                [_certificate()],
                "wrong.example.com",
                [VERIFICATION_ID],
            )

    def test_restore_preflight_rejects_missing_txt(self):
        with self.assertRaises(sut.CustomDomainValidationError):
            sut.validate_restore_preflight(
                _app(),
                [_certificate()],
                FQDN,
                ["other-id"],
            )

    def test_restore_preflight_rejects_duplicate_certificate(self):
        with self.assertRaises(sut.CustomDomainValidationError):
            sut.validate_restore_preflight(
                _app(),
                [_certificate(), _certificate(id=CERT_ID + "-duplicate")],
                FQDN,
                [VERIFICATION_ID],
            )

    def test_remove_preflight_passes(self):
        result = sut.validate_remove_preflight(
            _app(customDomains=_bound_domain()),
            [_certificate()],
        )
        self.assertEqual(result["certificate_id"], CERT_ID)

    def test_remove_preflight_rejects_absent_binding(self):
        with self.assertRaises(sut.CustomDomainValidationError):
            sut.validate_remove_preflight(_app(), [_certificate()])

    def test_post_bind_passes(self):
        expected = {
            "certificate_id": CERT_ID,
            "revision_name": REVISION,
        }
        sut.validate_post_bind(
            _app(customDomains=_bound_domain()),
            [_certificate()],
            expected,
        )

    def test_post_bind_rejects_revision_drift(self):
        with self.assertRaises(sut.CustomDomainValidationError):
            sut.validate_post_bind(
                _app(
                    customDomains=_bound_domain(),
                    latestRevisionName="api-gateway--0000099",
                    latestReadyRevisionName="api-gateway--0000099",
                ),
                [_certificate()],
                {"certificate_id": CERT_ID, "revision_name": REVISION},
            )

    def test_remove_post_passes(self):
        sut.validate_remove_post(_app(customDomains=None), [_certificate()])

    def test_remove_post_rejects_remaining_domain(self):
        with self.assertRaises(sut.CustomDomainValidationError):
            sut.validate_remove_post(
                _app(customDomains=_bound_domain()),
                [_certificate()],
            )

    def test_errors_do_not_leak_secrets(self):
        app = _app(secret=SECRET)
        with self.assertRaises(sut.CustomDomainValidationError) as ctx:
            sut.validate_restore_preflight(
                app,
                [_certificate(value=SECRET)],
                "wrong",
                [VERIFICATION_ID],
            )
        self.assertNotIn(SECRET, str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
