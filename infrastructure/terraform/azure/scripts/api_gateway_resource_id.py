"""Strict, case-insensitive comparison for the production api-gateway ARM ID."""

from __future__ import annotations

import re


_API_GATEWAY_ID = re.compile(
    r"^/subscriptions/(?P<subscription>[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/resourcegroups/"
    r"(?P<resource_group>wealth-azure-prod-rg)/providers/"
    r"(?P<provider>microsoft\.app)/containerapps/(?P<name>api-gateway)$",
    re.IGNORECASE | re.ASCII,
)


def is_api_gateway_resource_id(value: object) -> bool:
    return isinstance(value, str) and _API_GATEWAY_ID.fullmatch(value) is not None


def canonical_api_gateway_resource_id(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    match = _API_GATEWAY_ID.fullmatch(value)
    if match is None:
        return None
    return (
        f"/subscriptions/{match['subscription'].lower()}/resourcegroups/"
        f"{match['resource_group'].lower()}/providers/{match['provider'].lower()}/"
        f"containerapps/{match['name'].lower()}"
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


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("resource_id")
    args = parser.parse_args()
    if not is_api_gateway_resource_id(args.resource_id):
        print("ERROR: api-gateway ARM resource id is malformed.")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
