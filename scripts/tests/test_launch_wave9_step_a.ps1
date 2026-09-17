#Requires -Version 5.1
<#
.SYNOPSIS
    Offline contract tests for scripts/launch_wave9_step_a.ps1.

    Three security properties verified:
      (a) SENTINEL_IS_IN_CHILD_ENV  -- ProcessStartInfo injection reaches the
          child process environment (WAVE9_STEP_A_PASSWORD present and correct).
      (b) SENTINEL_NOT_IN_ARGV      -- the credential does NOT appear in the
          child's argv / command line (never passed via $psi.Arguments).
      (c) SENTINEL_NOT_IN_PARENT_ENV -- the parent process $env:WAVE9_STEP_A_PASSWORD
          remains null/empty after the launcher exits.

    The tests also verify:
      (d) WRAPPER_FAILURE_PROPAGATED -- a non-zero wrapper exit code is passed
          through; Step A is never launched.
      (e) UNKNOWN_SECRET_SOURCE_EXITS_2 -- an unsupported -SecretSource exits 2.
      (f) EMPTY_ENV_VAR_EXITS_2      -- an unset source env var exits 2.
      (g) MISSING_STEPARGS_FLAGS_EXIT_2 -- absent --evidence-output or
          --baseline-commit in -StepAArgs exits 2 before the wrapper runs.
      (h) RELATIVE_STEPA_SCRIPT_RESOLVES_AGAINST_PS_LOCATION -- when the
          process CWD differs from the PS location, a relative -StepAScript
          must still resolve correctly (WorkingDirectory must track Get-Location,
          not [Environment]::CurrentDirectory).

Run:
    powershell.exe -NoProfile -ExecutionPolicy Bypass `
        -File scripts\tests\test_launch_wave9_step_a.ps1

Exit codes: 0 = all pass, 1 = one or more failures.
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
$repoRoot     = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$launcher     = Join-Path $repoRoot 'scripts\launch_wave9_step_a.ps1'
$wrapperStub  = Join-Path $PSScriptRoot 'stub_wrapper_noop.ps1'
$childStub    = Join-Path $PSScriptRoot 'stub_step_a_child.py'

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
$pass     = 0
$fail     = 0
$failures = [System.Collections.Generic.List[string]]::new()

function Assert-True {
    param([bool]$Condition, [string]$Name)
    if ($Condition) {
        $script:pass++
        Write-Host "  ok   $Name"
    } else {
        $script:fail++
        $script:failures.Add($Name)
        Write-Host "  FAIL $Name" -ForegroundColor Red
    }
}

function Assert-False {
    param([bool]$Condition, [string]$Name)
    Assert-True (-not $Condition) $Name
}

function New-TempCapture {
    $path = [System.IO.Path]::GetTempFileName()
    # GetTempFileName creates the file; we want it empty for appending.
    [System.IO.File]::WriteAllText($path, '')
    return $path
}

function Invoke-Launcher {
    param(
        [hashtable]$ExtraParams = @{},
        [hashtable]$ExtraEnv   = @{}
    )
    # Apply extra env vars for this call only, restoring afterwards.
    $saved = @{}
    foreach ($k in $ExtraEnv.Keys) {
        $saved[$k] = [System.Environment]::GetEnvironmentVariable($k)
        [System.Environment]::SetEnvironmentVariable($k, $ExtraEnv[$k])
    }
    try {
        # Build base params then merge ExtraParams with a foreach loop.
        # The hashtable '+' operator throws ArgumentException on duplicate keys
        # (e.g. when $ExtraParams overrides WrapperScript), which under
        # $ErrorActionPreference='Stop' aborts before any test summary is printed.
        $params = @{
            WrapperScript = $wrapperStub
            StepAScript   = $childStub
        }
        foreach ($k in $ExtraParams.Keys) {
            $params[$k] = $ExtraParams[$k]
        }

        & $launcher @params
        return $LASTEXITCODE
    } finally {
        foreach ($k in $saved.Keys) {
            [System.Environment]::SetEnvironmentVariable($k, $saved[$k])
        }
    }
}

# Dummy StepAArgs that satisfy the launcher's pre-validation.
# All test groups that need to exercise credential or wrapper behavior must
# pass these; tests that specifically test the pre-validation (group g) use
# @() or a partial list to trigger exit 2.
$validStepAArgs = @('--evidence-output', 'out.json', '--baseline-commit', 'test-sha')

# ---------------------------------------------------------------------------
# Verify prerequisites
# ---------------------------------------------------------------------------
Write-Host "`ntest_launch_wave9_step_a.ps1"
Write-Host "-----------------------------"

if (-not (Test-Path $launcher)) {
    [Console]::Error.WriteLine("Launcher not found: $launcher")
    exit 2
}
if (-not (Test-Path $wrapperStub)) {
    [Console]::Error.WriteLine("Wrapper stub not found: $wrapperStub")
    exit 2
}
if (-not (Test-Path $childStub)) {
    [Console]::Error.WriteLine("Child stub not found: $childStub")
    exit 2
}

# Check Python availability -- these tests require a working Python on PATH.
try {
    $pyVer = & python --version 2>&1
    Write-Host "  python: $pyVer"
} catch {
    [Console]::Error.WriteLine("Python not found on PATH -- tests cannot run.")
    exit 2
}

# ---------------------------------------------------------------------------
# (a) + (b) + (c) -- core injection / isolation properties
# ---------------------------------------------------------------------------
Write-Host "`n[Group 1] Credential injection / isolation"

$sentinel = 'sentinel-step-a-password'
$capFile  = New-TempCapture

# Ensure the parent env var is clean before the test.
$env:WAVE9_STEP_A_PASSWORD = $null

try {
    $rc = Invoke-Launcher -ExtraParams @{
        SecretSource = 'env:STEP_A_TEST_SENTINEL'
        StepAArgs    = $validStepAArgs
    } -ExtraEnv @{
        STEP_A_TEST_SENTINEL = $sentinel
        STUB_CAPTURE         = $capFile
    }

    $captureLines = Get-Content $capFile -ErrorAction SilentlyContinue

    # (a) sentinel reached the child's env via ProcessStartInfo.
    # Uses -contains (exact match) not -like: the literal [$sentinel] in a
    # -like pattern is parsed as a PS wildcard character class; when $sentinel
    # contains 'p-a' the range is invalid and WildcardPatternException fires
    # under EAP=Stop.  @($envLine) coerces AutomationNull to an empty array
    # so -contains returns $false instead of throwing.
    $envLine = $captureLines | Where-Object { $_ -like 'step-a-child-env*' }
    Assert-True (
        [bool](@($envLine) -contains "step-a-child-env WAVE9_STEP_A_PASSWORD=[$sentinel]")
    ) '(a) SENTINEL_IS_IN_CHILD_ENV'

    # (b) sentinel is NOT in the child's argv
    $argvLine = $captureLines | Where-Object { $_ -like 'step-a-child argv*' }
    Assert-False (
        [bool]($argvLine -like "*$sentinel*")
    ) '(b) SENTINEL_NOT_IN_ARGV'

    # (c) parent env var is null after launcher returns
    Assert-True (
        [string]::IsNullOrEmpty($env:WAVE9_STEP_A_PASSWORD)
    ) '(c) SENTINEL_NOT_IN_PARENT_ENV'

    # Launcher itself should have exited 0 (child stub default exit is 0)
    Assert-True ($rc -eq 0) '(group1) launcher exit code 0 on success'

} finally {
    Remove-Item $capFile -Force -ErrorAction SilentlyContinue
    $env:WAVE9_STEP_A_PASSWORD = $null
}

# ---------------------------------------------------------------------------
# (d) Wrapper failure is propagated; Step A is NOT launched
# ---------------------------------------------------------------------------
Write-Host "`n[Group 2] Wrapper failure propagation"

$wrapperFailStub = Join-Path ([System.IO.Path]::GetTempPath()) ("stub_wrapper_fail_$([System.Guid]::NewGuid().ToString('N')).ps1")
[System.IO.File]::WriteAllText($wrapperFailStub, "#Requires -Version 5.1`nexit 7`n")

$capFile2 = New-TempCapture
try {
    $rc2 = Invoke-Launcher -ExtraParams @{
        WrapperScript = $wrapperFailStub
        SecretSource  = 'env:STEP_A_TEST_SENTINEL'
        StepAArgs     = $validStepAArgs
    } -ExtraEnv @{
        STEP_A_TEST_SENTINEL = $sentinel
        STUB_CAPTURE         = $capFile2
    }

    Assert-True ($rc2 -eq 7) '(d) wrapper exit code propagated'

    $lines2 = Get-Content $capFile2 -ErrorAction SilentlyContinue
    $stepALaunched = [bool]($lines2 | Where-Object { $_ -like 'step-a-child*' })
    Assert-False $stepALaunched '(d) step-a NOT launched when wrapper fails'

} finally {
    Remove-Item $capFile2 -Force -ErrorAction SilentlyContinue
    Remove-Item $wrapperFailStub -Force -ErrorAction SilentlyContinue
}

# ---------------------------------------------------------------------------
# (e) Unknown -SecretSource exits 2
# ---------------------------------------------------------------------------
Write-Host "`n[Group 3] Bad SecretSource / unset source var"

$capFile3 = New-TempCapture
try {
    $rc3 = Invoke-Launcher -ExtraParams @{
        SecretSource = 'bogus-source'
        StepAArgs    = $validStepAArgs
    } -ExtraEnv @{
        STUB_CAPTURE = $capFile3
    }
    Assert-True ($rc3 -eq 2) '(e) UNKNOWN_SECRET_SOURCE_EXITS_2'
} finally {
    Remove-Item $capFile3 -Force -ErrorAction SilentlyContinue
}

# ---------------------------------------------------------------------------
# (f) Unset env var exits 2
# ---------------------------------------------------------------------------
$capFile4 = New-TempCapture
try {
    # Ensure the source env var does NOT exist.
    [System.Environment]::SetEnvironmentVariable('STEP_A_MISSING_VAR', $null)
    $rc4 = Invoke-Launcher -ExtraParams @{
        SecretSource = 'env:STEP_A_MISSING_VAR'
        StepAArgs    = $validStepAArgs
    } -ExtraEnv @{
        STUB_CAPTURE = $capFile4
    }
    Assert-True ($rc4 -eq 2) '(f) EMPTY_ENV_VAR_EXITS_2'
} finally {
    Remove-Item $capFile4 -Force -ErrorAction SilentlyContinue
}

# ---------------------------------------------------------------------------
# (g) Pre-validation: missing --evidence-output or --baseline-commit exits 2
#     before the wrapper (and therefore the child) is ever launched.
# ---------------------------------------------------------------------------
Write-Host "`n[Group 4] Pre-validation of required StepAArgs flags"

$sentinel2 = 'sentinel-step-a-2'
$capFile5   = New-TempCapture

try {
    # No StepAArgs at all -- both flags are absent.
    $rc5 = Invoke-Launcher -ExtraParams @{
        SecretSource = 'env:STEP_A_TEST_SENTINEL2'
        StepAArgs    = @()
    } -ExtraEnv @{
        STEP_A_TEST_SENTINEL2 = $sentinel2
        STUB_CAPTURE          = $capFile5
    }
    Assert-True ($rc5 -eq 2) '(g) MISSING_EVIDENCE_OUTPUT_EXITS_2'

    # stub_step_a_child.py writes 'step-a-child-env*' / 'step-a-child argv*' to
    # STUB_CAPTURE when it runs.  Absence of those lines confirms the child was
    # not launched (the pre-validation exits before the wrapper runs).
    $lines5 = Get-Content $capFile5 -ErrorAction SilentlyContinue
    $stepAChildCalled = [bool]($lines5 | Where-Object { $_ -like 'step-a-child*' })
    Assert-False $stepAChildCalled '(g) step-a child NOT launched when StepAArgs missing required flags'
} finally {
    Remove-Item $capFile5 -Force -ErrorAction SilentlyContinue
}

$capFile6 = New-TempCapture
try {
    # Has --evidence-output but missing --baseline-commit -> still exits 2.
    $rc6 = Invoke-Launcher -ExtraParams @{
        SecretSource = 'env:STEP_A_TEST_SENTINEL2'
        StepAArgs    = @('--evidence-output', 'evidence.json')
    } -ExtraEnv @{
        STEP_A_TEST_SENTINEL2 = $sentinel2
        STUB_CAPTURE          = $capFile6
    }
    Assert-True ($rc6 -eq 2) '(g) MISSING_BASELINE_COMMIT_EXITS_2'
} finally {
    Remove-Item $capFile6 -Force -ErrorAction SilentlyContinue
}

# ---------------------------------------------------------------------------
# (h) Relative StepAScript resolves against PS location, not process CWD
# ---------------------------------------------------------------------------
Write-Host "`n[Group 5] Relative StepAScript resolves against Get-Location"

$capFile7     = New-TempCapture
$savedProcCwd = [System.Environment]::CurrentDirectory
try {
    # Force process CWD to a different directory (repo root) while the PS
    # location is $PSScriptRoot (scripts\tests).  Without $psi.WorkingDirectory
    # the launcher would pass 'stub_step_a_child.py' to Process.Start with the
    # repo root as CWD and Python would fail to open the file.
    [System.Environment]::CurrentDirectory = $repoRoot
    Push-Location $PSScriptRoot

    # Precondition: the two directories must actually differ for this test to mean anything.
    Assert-True (
        [System.Environment]::CurrentDirectory -ne (Get-Location -PSProvider FileSystem).ProviderPath
    ) '(h) precondition: process CWD differs from PS location'

    $rc7 = Invoke-Launcher -ExtraParams @{
        WrapperScript = '.\stub_wrapper_noop.ps1'
        StepAScript   = 'stub_step_a_child.py'
        SecretSource  = 'env:STEP_A_TEST_SENTINEL'
        StepAArgs     = $validStepAArgs
    } -ExtraEnv @{
        STEP_A_TEST_SENTINEL = $sentinel
        STUB_CAPTURE         = $capFile7
    }
    Assert-True ($rc7 -eq 0) '(h) RELATIVE_STEPA_SCRIPT_RESOLVES_AGAINST_PS_LOCATION'

    $lines7 = Get-Content $capFile7 -ErrorAction SilentlyContinue
    Assert-True (
        [bool](@($lines7) -contains "step-a-child-env WAVE9_STEP_A_PASSWORD=[$sentinel]")
    ) '(h) child ran and received the credential'
} finally {
    Pop-Location
    [System.Environment]::CurrentDirectory = $savedProcCwd
    Remove-Item $capFile7 -Force -ErrorAction SilentlyContinue
}

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
Write-Host ''
Write-Host "Results: $pass passed, $fail failed."
if ($fail -gt 0) {
    Write-Host "Failed tests:" -ForegroundColor Red
    foreach ($name in $failures) { Write-Host "  - $name" -ForegroundColor Red }
    exit 1
}
Write-Host 'All tests passed.' -ForegroundColor Green
exit 0
