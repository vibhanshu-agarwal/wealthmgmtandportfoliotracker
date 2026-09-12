<#
    Offline tests for scripts/run_task_8_9_preflight.ps1.

    Nothing here contacts Azure, the production host, or a registry. Every
    external command is replaced by a .cmd stub that records its argv, so the
    tests assert on exactly what survived the PowerShell -> cmd boundary --
    the fault line that broke three hand-issued commands during the 2026-09-12
    attempt.

    Run:  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/test_run_task_8_9_preflight.ps1
#>
Set-StrictMode -Version Latest
# 'Continue', deliberately: under 'Stop' a native command writing to stderr
# becomes a terminating error in Windows PowerShell 5.1, which aborts the whole
# run instead of recording a FAIL -- and the stub-fidelity check below exists
# precisely to make a native command fail. Exit codes are asserted explicitly.
$ErrorActionPreference = 'Continue'

$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$script = Join-Path $repo 'scripts/run_task_8_9_preflight.ps1'
$stubs = $PSScriptRoot
$failures = 0
$tests = 0

function Invoke-Wrapper {
    param([hashtable]$Env = @{}, [string[]]$Extra = @())
    $capture = Join-Path ([IO.Path]::GetTempPath()) "t89-capture-$([guid]::NewGuid()).txt"
    $evidence = Join-Path ([IO.Path]::GetTempPath()) "t89-evidence-$([guid]::NewGuid()).json"
    $saved = @{}
    $Env['STUB_CAPTURE'] = $capture
    foreach ($k in $Env.Keys) {
        $saved[$k] = [Environment]::GetEnvironmentVariable($k)
        [Environment]::SetEnvironmentVariable($k, $Env[$k])
    }
    try {
        $argv = @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $script,
            '-AzCommand', (Join-Path $stubs 'stub_az.cmd'),
            '-DockerCommand', (Join-Path $stubs 'stub_docker.cmd'),
            '-CurlCommand', (Join-Path $stubs 'stub_curl.cmd'),
            '-PythonCommand', (Join-Path $stubs 'stub_python.cmd'),
            '-EvidenceOutput', $evidence,
            '-ReplicaWaitSeconds', '2',
            '-ReplicaPollSeconds', '1'
        ) + $Extra
        $out = & powershell @argv 2>&1 | Out-String
        $code = $LASTEXITCODE
        $cap = if (Test-Path $capture) { Get-Content $capture -Raw } else { '' }
        return [pscustomobject]@{ Exit = $code; Capture = $cap; Output = ($out -join "`n") }
    } finally {
        foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k]) }
        Remove-Item $capture, $evidence -ErrorAction SilentlyContinue
    }
}

function Check {
    param([string]$Name, [scriptblock]$Assert)
    $script:tests++
    try {
        & $Assert
        Write-Host "  PASS  $Name"
    } catch {
        $script:failures++
        Write-Host "  FAIL  $Name" -ForegroundColor Red
        Write-Host "        $($_.Exception.Message)" -ForegroundColor Red
    }
}

$prov = Get-Content (Join-Path $repo 'docs/evidence/b2-task-8-9/deployment-provenance-20260911.json') -Raw | ConvertFrom-Json
$subId = (Get-Content (Join-Path $repo 'docs/evidence/b2-task-8-9/rehearsal-20260911.json') -Raw | ConvertFrom-Json).target.subscriptionId

$good = @{
    STUB_SERVING_REVISION   = $prov.services.'api-gateway'.revision
    STUB_SERVING_IMAGE      = $prov.services.'api-gateway'.repository + '@' + $prov.services.'api-gateway'.digest
    STUB_PORTFOLIO_REVISION = $prov.services.'portfolio-service'.revision
    STUB_PORTFOLIO_IMAGE    = $prov.services.'portfolio-service'.repository + '@' + $prov.services.'portfolio-service'.digest
    STUB_SUBSCRIPTION_ID    = $subId
}

# --- The rig must reproduce the fault it exists to catch --------------------
# If this fails, every "argument survived" assertion below is vacuous.
Write-Host 'Stub fidelity: does it reproduce the az.cmd parenthesised-block fault?'
$fidCapture = Join-Path ([IO.Path]::GetTempPath()) "t89-fid-$([guid]::NewGuid()).txt"
[Environment]::SetEnvironmentVariable('STUB_CAPTURE', $fidCapture)
& (Join-Path $stubs 'stub_az.cmd') --query 'length(@)' -o tsv 2>&1 | Out-Null
$fidExit = $LASTEXITCODE
$fidCap = if (Test-Path $fidCapture) { Get-Content $fidCapture -Raw } else { '' }
Remove-Item $fidCapture -ErrorAction SilentlyContinue
[Environment]::SetEnvironmentVariable('STUB_CAPTURE', $null)
Check 'a parenthesised --query breaks the stub, as it breaks real az.cmd' {
    if ($fidExit -eq 0 -and $fidCap -match 'length\(@\)') {
        throw "the stub passed 'length(@)' through intact (exit $fidExit) - it does NOT reproduce the az.cmd boundary, so the survival assertions below prove nothing"
    }
}
Check 'a paren-free --query passes through the same stub' {
    $c = Join-Path ([IO.Path]::GetTempPath()) "t89-fid2-$([guid]::NewGuid()).txt"
    [Environment]::SetEnvironmentVariable('STUB_CAPTURE', $c)
    & (Join-Path $stubs 'stub_az.cmd') containerapp revision list --query '[].name' -o tsv 2>&1 | Out-Null
    $e = $LASTEXITCODE
    $cap = if (Test-Path $c) { Get-Content $c -Raw } else { '' }
    Remove-Item $c -ErrorAction SilentlyContinue
    [Environment]::SetEnvironmentVariable('STUB_CAPTURE', $null)
    if ($e -ne 0 -or $cap -notmatch '\[\]\.name') { throw "paren-free query did not survive (exit $e): $cap" }
}

Write-Host 'Happy path'
$r = Invoke-Wrapper -Env $good.Clone()
Check 'exits 0' { if ($r.Exit -ne 0) { throw "exit $($r.Exit); output: $($r.Output)" } }
Check 'JMESPath [].name survives the cmd boundary intact' {
    if ($r.Capture -notmatch '\[\]\.name') { throw "no intact [].name in capture:`n$($r.Capture)" }
}
Check 'containers[0].image query survives intact' {
    if ($r.Capture -notmatch 'properties\.template\.containers\[0\]\.image') { throw "mangled image query:`n$($r.Capture)" }
}
Check 'no truncated JMESPath (the length(@ bug class)' {
    if ($r.Capture -match '\([^)]*$') { throw "unbalanced paren in captured argv:`n$($r.Capture)" }
}
Check 'verifier is invoked with --mode preflight' {
    if ($r.Capture -notmatch '--mode preflight') { throw "mode not preflight:`n$($r.Capture)" }
}
Check 'verifier never receives --threshold-override' {
    if ($r.Capture -match 'threshold-override') { throw 'threshold override was passed' }
}
Check 'verifier never receives execute' {
    if ($r.Capture -match '--mode execute') { throw 'execute was requested' }
}
Check 'no credential env var is passed to the verifier' {
    if ($r.Capture -match 'TASK8_9_ACCESS_TOKEN|TASK8_9_DEMO_PASSWORD') { throw 'credential name leaked into argv' }
}
Check 'the wake is issued exactly once' {
    $n = ([regex]::Matches($r.Capture, '(?m)^curl ')).Count
    if ($n -ne 1) { throw "wake issued $n times" }
}
Check 'the subscription id is not printed in output' {
    if ($r.Output -match 'ee625b3f') { throw 'subscription id was printed' }
}

Write-Host 'Wake returns 503'
$e = $good.Clone(); $e['STUB_WAKE_STATUS'] = '503'
$r = Invoke-Wrapper -Env $e
Check 'stops, as the packet requires, and does not run the verifier' {
    if ($r.Exit -ne 3) { throw "exit $($r.Exit); output: $($r.Output)" }
    if ($r.Capture -match '(?m)^python .*--mode ') { throw 'verifier ran after a non-200 wake' }
}
Check 'still only one wake request' {
    $n = ([regex]::Matches($r.Capture, '(?m)^curl ')).Count
    if ($n -ne 1) { throw "wake issued $n times" }
}
Check 'names the owner opt-in rather than deciding for them' {
    if ($r.Output -notmatch 'ProceedOnNon200') { throw "did not name the opt-in:`n$($r.Output)" }
}

Write-Host 'Wake returns 503 with the owner opt-in'
$e = $good.Clone(); $e['STUB_WAKE_STATUS'] = '503'
$r = Invoke-Wrapper -Env $e -Extra @('-ProceedOnNon200')
Check 'proceeds to the verifier and exits 0' { if ($r.Exit -ne 0) { throw "exit $($r.Exit); output: $($r.Output)" } }

Write-Host 'Wake transport failure after delivery'
$e = $good.Clone(); $e['STUB_CURL_EXIT'] = '52'
$r = Invoke-Wrapper -Env $e
Check 'does not claim the wake was unconsumed' {
    if ($r.Output -match 'probably NOT consumed') { throw 'claimed unconsumed for a post-delivery curl exit' }
    if ($r.Output -notmatch 'may be consumed') { throw "did not flag possible consumption:`n$($r.Output)" }
}

Write-Host 'No replica appears'
$e = $good.Clone(); $e['STUB_REPLICA'] = 'none'
$r = Invoke-Wrapper -Env $e
Check 'exits 3 (wake consumed)' { if ($r.Exit -ne 3) { throw "exit $($r.Exit); output: $($r.Output)" } }
Check 'reaches exit 3 by the intended path, not by crashing into the trap' {
    # An empty replica list used to throw on .name and hit the trap, which also
    # exits 3 -- so exit code alone cannot tell a wait from a crash.
    if ($r.Output -match 'FAIL \(unhandled\)') { throw "crashed into the trap:`n$($r.Output)" }
    if ($r.Output -notmatch 'no ready replica appeared') { throw "did not reach the wait's own failure message:`n$($r.Output)" }
}
Check 'actually polls more than once before giving up' {
    $n = ([regex]::Matches($r.Capture, 'replica list')).Count
    if ($n -lt 2) { throw "polled $n time(s); the wait did not run" }
}
Check 'does not run the verifier' {
    # the pre-wake '--help' probe is also a python line; only --mode is a real run
    if ($r.Capture -match '(?m)^python .*--mode ') { throw 'verifier ran without a replica' }
}
Check 'does not re-issue the wake' {
    $n = ([regex]::Matches($r.Capture, '(?m)^curl ')).Count
    if ($n -ne 1) { throw "wake issued $n times" }
}

Write-Host 'Two replicas, the first one ready'
$e = $good.Clone(); $e['STUB_REPLICA'] = 'multi'
$r = Invoke-Wrapper -Env $e
Check 'accepts on the first listed replica, as the verifier execs replicas[0]' {
    # This is the discriminating test for the ConvertFrom-Json wrapping bug.
    # Correct code sees [rep-a, rep-b], takes rep-a (Running), and proceeds.
    # Code that double-wraps sees one element containing both, evaluates their
    # states together, finds a NotRunning among them, and refuses -- exit 3.
    if ($r.Exit -ne 0) { throw "exit $($r.Exit) - the decoded list is probably being double-wrapped; output: $($r.Output)" }
}

Write-Host 'Replica is listed but not Running'
$e = $good.Clone(); $e['STUB_REPLICA_STATE'] = 'Pending'
$r = Invoke-Wrapper -Env $e
Check 'exits 3 rather than running the verifier at a not-ready replica' {
    if ($r.Exit -ne 3) { throw "exit $($r.Exit); output: $($r.Output)" }
    if ($r.Capture -match '(?m)^python .*--mode ') { throw 'verifier ran against a not-ready replica' }
}

Write-Host 'az actually fails during the replica poll'
$e = $good.Clone(); $e['STUB_POLL_EXIT'] = '1'
$r = Invoke-Wrapper -Env $e
Check 'exits 3 and names the tooling fault rather than blaming scale-to-zero' {
    if ($r.Exit -ne 3) { throw "exit $($r.Exit); output: $($r.Output)" }
    if ($r.Output -notmatch 'failed to reach Azure') { throw "poll errors not reported:`n$($r.Output)" }
    if ($r.Output -match 'FAIL \(unhandled\)') { throw 'crashed instead of reporting' }
}

Write-Host 'Wake transport failure before delivery (DNS)'
$e = $good.Clone(); $e['STUB_CURL_EXIT'] = '6'
$r = Invoke-Wrapper -Env $e
Check 'reports the wake as probably NOT consumed' {
    if ($r.Output -notmatch 'probably NOT consumed') { throw "output did not flag an unconsumed wake: $($r.Output)" }
}

Write-Host 'Unauthorized wake path is refused'
$r = Invoke-Wrapper -Env $good.Clone() -Extra @('-WakePath','/api/portfolio/holdings')
Check 'exits 2 without waking' {
    if ($r.Exit -ne 2) { throw "exit $($r.Exit); output: $($r.Output)" }
    if ($r.Capture -match '(?m)^curl ') { throw 'wake issued to an unauthorized path' }
}

Write-Host 'Subscription mismatch'
$e = $good.Clone(); $e['STUB_SUBSCRIPTION_ID'] = '00000000-0000-0000-0000-000000000000'
$r = Invoke-Wrapper -Env $e
Check 'exits 2 before the wake' {
    if ($r.Exit -ne 2) { throw "exit $($r.Exit); output: $($r.Output)" }
    if ($r.Capture -match '(?m)^curl ') { throw 'wake issued with the wrong active subscription' }
}

Write-Host 'portfolio-service does not match its attestation'
$e = $good.Clone(); $e['STUB_PORTFOLIO_REVISION'] = 'portfolio-service--0000097'
$r = Invoke-Wrapper -Env $e
Check 'exits 2 before the wake' {
    if ($r.Exit -ne 2) { throw "exit $($r.Exit); output: $($r.Output)" }
    if ($r.Capture -match '(?m)^curl ') { throw 'wake issued despite a portfolio-service mismatch' }
}

Write-Host 'Serving revision does not match the attestation'
$e = $good.Clone(); $e['STUB_SERVING_REVISION'] = 'api-gateway--0000082'
$r = Invoke-Wrapper -Env $e
Check 'exits 2 before the wake' { if ($r.Exit -ne 2) { throw "exit $($r.Exit); output: $($r.Output)" } }
Check 'no wake was issued' {
    if ($r.Capture -match '(?m)^curl ') { throw 'wake issued despite a stale attestation' }
}

Write-Host 'Serving digest does not match the attestation'
$e = $good.Clone(); $e['STUB_SERVING_IMAGE'] = 'wealthprodacr.azurecr.io/api-gateway@sha256:deadbeef'
$r = Invoke-Wrapper -Env $e
Check 'exits 2 before the wake' { if ($r.Exit -ne 2) { throw "exit $($r.Exit); output: $($r.Output)" } }
Check 'no wake was issued' {
    if ($r.Capture -match '(?m)^curl ') { throw 'wake issued despite a digest mismatch' }
}

Write-Host 'A newer revision exists'
$e = $good.Clone(); $e['STUB_EXTRA_REVISION'] = 'api-gateway--0000082'
$r = Invoke-Wrapper -Env $e
Check 'exits 2 before the wake' { if ($r.Exit -ne 2) { throw "exit $($r.Exit); output: $($r.Output)" } }
Check 'no wake was issued' {
    if ($r.Capture -match '(?m)^curl ') { throw 'wake issued despite a newer revision' }
}

Write-Host 'Docker is in Windows-containers mode'
$e = $good.Clone(); $e['STUB_DOCKER_OS'] = 'windows'
$r = Invoke-Wrapper -Env $e
Check 'exits 2 before the wake' { if ($r.Exit -ne 2) { throw "exit $($r.Exit); output: $($r.Output)" } }
Check 'no wake was issued' {
    if ($r.Capture -match '(?m)^curl ') { throw 'wake issued with the wrong Docker mode' }
}

Write-Host 'Verifier fails'
$e = $good.Clone(); $e['STUB_VERIFIER_EXIT'] = '1'
$r = Invoke-Wrapper -Env $e
Check 'exits 4' { if ($r.Exit -ne 4) { throw "exit $($r.Exit); output: $($r.Output)" } }
Check 'does not retry the verifier' {
    $n = ([regex]::Matches($r.Capture, '(?m)^python .*--mode ')).Count
    if ($n -ne 1) { throw "verifier ran $n times" }
}

Write-Host 'SkipWake'
$r = Invoke-Wrapper -Env $good.Clone() -Extra @('-SkipWake')
Check 'exits 0 without issuing a wake' {
    if ($r.Exit -ne 0) { throw "exit $($r.Exit); output: $($r.Output)" }
    if ($r.Capture -match '(?m)^curl ') { throw 'wake issued despite -SkipWake' }
}

Write-Host ''
Write-Host "$($tests - $failures)/$tests passed"
if ($failures -gt 0) { exit 1 }
exit 0
