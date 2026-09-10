"""Strict, case-insensitive comparison for the production api-gateway ARM ID."""

from __future__ import annotations

import re


_API_GATEWAY_ID = re.compile(
    r"^/subscriptions/(?P<subscription>[^/]+)/resourcegroups/"
    r"(?P<resource_group>wealth-azure-prod-rg)/providers/"
    r"(?P<provider>microsoft\.app)/containerapps/(?P<name>api-gateway)$",
    re.IGNORECASE,
)


def same_api_gateway_resource_id(actual: object, expected: object) -> bool:
    if not isinstance(actual, str) or not isinstance(expected, str):
        return False
    actual_match = _API_GATEWAY_ID.fullmatch(actual)
    expected_match = _API_GATEWAY_ID.fullmatch(expected)
    if actual_match is None or expected_match is None:
        return False
    return all(
        actual_match[group].casefold() == expected_match[group].casefold()
        for group in ("subscription", "resource_group", "provider", "name")
    )
