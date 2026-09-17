#!/usr/bin/env python3
"""
Child stub for launch_wave9_step_a.ps1 offline tests.

Reports its argv and WAVE9_STEP_A_PASSWORD env var to STUB_CAPTURE, then
exits with STUB_STEP_A_EXIT (from env) or 0.

This script is launched via ProcessStartInfo — not via the parent shell —
so it cannot receive values through argv that the launcher doesn't put there.
"""
import os
import sys

capture_file = os.environ.get("STUB_CAPTURE", "")
if capture_file:
    with open(capture_file, "a", encoding="utf-8") as f:
        f.write(f"step-a-child argv {sys.argv[1:]}\n")
        pw = os.environ.get("WAVE9_STEP_A_PASSWORD", "")
        f.write(f"step-a-child-env WAVE9_STEP_A_PASSWORD=[{pw}]\n")

sys.exit(int(os.environ.get("STUB_STEP_A_EXIT", "0")))
