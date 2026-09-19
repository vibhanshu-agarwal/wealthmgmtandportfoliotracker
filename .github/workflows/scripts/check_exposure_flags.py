#!/usr/bin/env python3
"""Report and validate the Wave 10 exposure flags before the static-export build.

``output: "export"`` inlines ``NEXT_PUBLIC_*`` at build time, so the value the build sees
is the value the bundle carries for good; correcting a wrong one costs another build and
deploy. The app enables a control only when the trimmed, lower-cased value is ``"true"``
(``parseFeatureFlag``), which makes some spellings easy to get wrong in either direction:
``"True"`` and ``" true"`` enable it, while ``"1"``, ``"yes"`` and ``"on"`` quietly do not.

So this accepts only the three unambiguous states -- unset/empty (hidden), ``true``
(exposed) and ``false`` (hidden, explicitly) -- prints what each flag will do, and fails the
run before it builds on anything else.

The workflow maps ``ENABLE_ASSET_PICKER`` and ``ENABLE_DEMO_RESET_CONTROL`` in from the
repository variables of the same names.
"""

from __future__ import annotations

import os
import sys

FLAGS = ("ENABLE_ASSET_PICKER", "ENABLE_DEMO_RESET_CONTROL")
ACCEPTED = ("", "true", "false")


def main() -> int:
    errors: list[str] = []
    for name in FLAGS:
        value = os.environ.get(name, "")
        if value not in ACCEPTED:
            print(f"{name}={value!r} -> REJECTED")
            errors.append(
                f"{name}={value!r} is ambiguous. Use exactly 'true' to expose the control or "
                "'false' (or leave the variable unset) to keep it hidden; the app trims and "
                "lower-cases before comparing, so other spellings do not mean what they say."
            )
            continue
        state = "enabled" if value == "true" else "disabled"
        origin = " (unset)" if value == "" else ""
        print(f"{name}='{value}' -> {state}{origin}")
    for error in errors:
        print(f"::error::{error}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
