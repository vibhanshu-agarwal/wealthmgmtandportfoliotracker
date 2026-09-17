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
        $params = @{
            WrapperScript = $wrapperStub
            StepAScript   = $childStub
        } + $ExtraParams

        & $launcher @params
        return $LASTEXITCODE
    } finally {
        foreach ($k in $saved.Keys) {
            [System.Environment]::SetEnvironmentVariable($k, $saved[$k])
        }
    }
}

# ---------------------------------------------------------------------------
# Verify prerequisites
# ---------------------------------------------------------------------------
Write-Host "`ntest_launch_wave9_step_a.ps1"
Write-Host "-----------------------------"

if (-not (Test-Path $launcher)) {
    Write-Error "Launcher not found: $launcher"
    exit 2
}
if (-not (Test-Path $wrapperStub)) {
    Write-Error "Wrapper stub not found: $wrapperStub"
    exit 2
}
if (-not (Test-Path $childStub)) {
    Write-Error "Child stub not found: $childStub"
    exit 2
}

# Check Python availability -- these tests require a working Python on PATH.
try {
    $pyVer = & python --version 2>&1
    Write-Host "  python: $pyVer"
} catch {
    Write-Error "Python not found on PATH -- tests cannot run."
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
    } -ExtraEnv @{
        STEP_A_TEST_SENTINEL = $sentinel
        STUB_CAPTURE         = $capFile
    }

    $captureLines = Get-Content $capFile -ErrorAction SilentlyContinue

    # (a) sentinel reached the child's env via ProcessStartInfo
    $envLine = $captureLines | Where-Object { $_ -like 'step-a-child-env*' }
    Assert-True (
        $envLine -like "*WAVE9_STEP_A_PASSWORD=[$sentinel]*"
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

$wrapperFailStub = Join-Path ([System.IO.Path]::GetTempPath()) 'stub_wrapper_fail.ps1'
[System.IO.File]::WriteAllText($wrapperFailStub, "#Requires -Version 5.1`nexit 7`n")

$capFile2 = New-TempCapture
try {
    $rc2 = Invoke-Launcher -ExtraParams @{
        WrapperScript = $wrapperFailStub
        SecretSource  = 'env:STEP_A_TEST_SENTINEL'
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
    } -ExtraEnv @{
        STUB_CAPTURE = $capFile4
    }
    Assert-True ($rc4 -eq 2) '(f) EMPTY_ENV_VAR_EXITS_2'
} finally {
    Remove-Item $capFile4 -Force -ErrorAction SilentlyContinue
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
