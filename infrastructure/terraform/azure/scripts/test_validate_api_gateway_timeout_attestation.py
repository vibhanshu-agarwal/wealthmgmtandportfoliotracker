#!/usr/bin/env python3
"""Adversarial tests for the api-gateway timeout rollout live attestation."""

from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import validate_api_gateway_timeout_attestation as sut  # noqa: E402

GATEWAY_ID = (
    "/subscriptions/11111111-1111-1111-1111-111111111111/resourceGroups/"
    "wealth-azure-prod-rg/providers/Microsoft.App/containerApps/api-gateway"
)
REVISION = "api-gateway--0000079"
DIGEST = "sha256:" + "a1" * 32


def _app():
    return {
        "id": GATEWAY_ID,
        "latestRevision": REVISION,
        "traffic": [{"latestRevision": True, "weight": 100}],
    }


def _revision():
    return {
        "name": REVISION,
        "active": True,
        "trafficWeight": 100,
        "containers": [{"name": "api-gateway", "image": f"wealthprodacr.azurecr.io/api-gateway@{DIGEST}"}],
    }


class ValidateApiGatewayTimeoutAttestationTests(unittest.TestCase):
    def test_valid_attestation_returns_the_trusted_three_fields(self):
        self.assertEqual(sut.validate_attestation(_app(), _revision()), (GATEWAY_ID, REVISION, DIGEST))

    def test_revision_reread_rejects_active_or_traffic_races(self):
        for field, value in (("active", False), ("trafficWeight", 0)):
            with self.subTest(field=field):
                revision = _revision()
                revision[field] = value
                with self.assertRaises(sut.AttestationValidationError):
                    sut.validate_attestation(_app(), revision)

    def test_revalidation_rejects_changed_id_revision_or_digest(self):
        for field, value in (
            ("id", GATEWAY_ID.replace("api-gateway", "other-gateway")),
            ("latestRevision", "api-gateway--0000080"),
        ):
            with self.subTest(field=field):
                app = _app()
                app[field] = value
                with self.assertRaises(sut.AttestationValidationError):
                    sut.validate_attestation(app, _revision(), (GATEWAY_ID, REVISION, DIGEST))

        revision = _revision()
        revision["containers"][0]["image"] = f"wealthprodacr.azurecr.io/api-gateway@sha256:{'b2' * 32}"
        with self.assertRaises(sut.AttestationValidationError):
            sut.validate_attestation(_app(), revision, (GATEWAY_ID, REVISION, DIGEST))

    def test_non_ascii_arm_id_lookalikes_are_rejected(self):
        for value in (
            GATEWAY_ID.replace("api-gateway", "ap\u0131-gateway"),
            GATEWAY_ID.replace("api-gateway", "ap\u0130-gateway"),
            GATEWAY_ID.replace("subscriptions", "\u017fubscriptions"),
        ):
            with self.subTest(value=value):
                app = _app()
                app["id"] = value
                with self.assertRaises(sut.AttestationValidationError):
                    sut.validate_attestation(app, _revision())
