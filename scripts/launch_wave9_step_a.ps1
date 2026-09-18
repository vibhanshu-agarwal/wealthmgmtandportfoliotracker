#Requires -Version 5.1
<#
.SYNOPSIS
    Stage 1 launcher for Wave 9 Step A credential injection.

.DESCRIPTION
    Runs run_task_8_9_preflight.ps1, then launches verify_wave9_step_a.py via
    System.Diagnostics.ProcessStartInfo with WAVE9_STEP_A_PASSWORD injected
    only into the child's environment dictionary.

    The credential is NEVER placed in:
      - $psi.Arguments (argv, visible via Win32_Process.CommandLine)
      - $env:WAVE9_STEP_A_PASSWORD of this process (would be inherited by any
        other child and visible via process listing)

    The owner must supply the credential interactively (default) or name a
    source env var for offline testing (-SecretSource 'env:<NAME>').

.PARAMETER WrapperScript
    Path to the preflight wrapper script to run before Step A.
    Default: scripts\run_task_8_9_preflight.ps1

.PARAMETER WrapperParameters
    Named parameters forwarded to the wrapper script via hashtable splatting.
    Keys must match the wrapper's declared parameter names exactly.

.PARAMETER StepAScript
    Path to the Python verify script.
    Default: scripts\verify_wave9_step_a.py

.PARAMETER StepAArgs
    Additional arguments forwarded to the Step A script.

.PARAMETER PythonCommand
    Python executable name or path. Default: python

.PARAMETER SecretSource
    How to obtain the credential:
      'read-host'   -- prompt interactively via Read-Host (default, production)
      'env:<NAME>'  -- read from the named env var (offline tests only)
#>
param(
    [string]    $WrapperScript    = 'scripts\run_task_8_9_preflight.ps1',
    [hashtable] $WrapperParameters = @{},
    [string]   $StepAScript   = 'scripts\verify_wave9_step_a.py',
    [string[]] $StepAArgs     = @(),
    [string]   $PythonCommand = 'python',
    [string]   $SecretSource  = 'read-host'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Guard: must run under Windows PowerShell 5.1 (powershell.exe), not pwsh.
if ($PSVersionTable.PSEdition -eq 'Core') {
    [Console]::Error.WriteLine('launch_wave9_step_a.ps1 requires Windows PowerShell 5.1 (powershell.exe), not PowerShell Core (pwsh).')
    exit 2
}

# Pre-validate that $StepAArgs contains required flags before prompting for the
# credential -- gives immediate feedback if the caller forgot a flag.
$_hasEvidenceOutput = [bool]($StepAArgs | Where-Object { $_ -eq '--evidence-output' -or $_ -like '--evidence-output=*' })
if (-not $_hasEvidenceOutput) {
    [Console]::Error.WriteLine("launch_wave9_step_a: -StepAArgs must include '--evidence-output' before the wrapper runs.")
    exit 2
}
$_hasBaselineCommit = [bool]($StepAArgs | Where-Object { $_ -eq '--baseline-commit' -or $_ -like '--baseline-commit=*' })
if (-not $_hasBaselineCommit) {
    [Console]::Error.WriteLine("launch_wave9_step_a: -StepAArgs must include '--baseline-commit' before the wrapper runs.")
    exit 2
}

# Capture credential into a local variable only.
# Result is a plain string in memory; it never touches $env: or argv.
[string]$plain = $null

if ($SecretSource -eq 'read-host') {
    $ss = Read-Host -Prompt 'Wave 9 Step A password' -AsSecureString
    $ptr = [System.Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($ss)
    try {
        $plain = [System.Runtime.InteropServices.Marshal]::PtrToStringUni($ptr)
    } finally {
        [System.Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($ptr)
    }
    if ([string]::IsNullOrEmpty($plain)) {
        [Console]::Error.WriteLine('Read-Host returned an empty credential -- aborting.')
        exit 2
    }
} elseif ($SecretSource -like 'env:*') {
    $envVarName = $SecretSource.Substring(4)
    $plain = [System.Environment]::GetEnvironmentVariable($envVarName)
    if ([string]::IsNullOrEmpty($plain)) {
        [Console]::Error.WriteLine("Secret source env var '$envVarName' is not set or is empty.")
        exit 2
    }
} else {
    [Console]::Error.WriteLine("Unknown -SecretSource '$SecretSource'. Use 'read-host' or 'env:<NAME>'.")
    exit 2
}

# Step 1: run the wrapper script.
& $WrapperScript @WrapperParameters
$wrapperExit = $LASTEXITCODE
if ($wrapperExit -ne 0) {
    Write-Warning "Wrapper '$WrapperScript' exited $wrapperExit."
    $plain = $null
    exit $wrapperExit
}

# Step 2: launch verify_wave9_step_a.py via ProcessStartInfo.
#
# Credential is injected ONLY into the child's EnvironmentVariables dictionary.
# .EnvironmentVariables, when UseShellExecute = $false, lazily copies the
# current process environment (including STUB_CAPTURE for offline tests) on
# first access, then we append WAVE9_STEP_A_PASSWORD.  The parent process
# environment is never modified.
$psi = New-Object System.Diagnostics.ProcessStartInfo
# Set WorkingDirectory from the PS location (not [Environment]::CurrentDirectory,
# which diverges when the operator cd's to the repo without using -File invocation).
$psi.WorkingDirectory = (Get-Location -PSProvider FileSystem).ProviderPath
$psi.UseShellExecute = $false
$psi.FileName        = $PythonCommand

# Build Arguments string.  Script path is quoted; StepAArgs appended verbatim.
$quotedScript = '"' + $StepAScript.Replace('"', '\"') + '"'
$psi.Arguments = ((@($quotedScript) + $StepAArgs) -join ' ')

# Inject credential into child env dict (pre-populated from parent env by .NET).
$psi.EnvironmentVariables['WAVE9_STEP_A_PASSWORD'] = $plain
$plain = $null  # clear local; the string object lives in GC heap until collected

$proc = [System.Diagnostics.Process]::Start($psi)
$proc.WaitForExit()
exit $proc.ExitCode
