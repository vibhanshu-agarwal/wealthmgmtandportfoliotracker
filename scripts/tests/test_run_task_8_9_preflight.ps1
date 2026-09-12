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
    param([hashtable]$Env = @{}, [string[]]$Extra = @(), [int]$WaitSeconds = 2, [string]$PythonCommand)
    $capture = Join-Path ([IO.Path]::GetTempPath()) "t89-capture-$([guid]::NewGuid()).txt"
    $evidence = Join-Path ([IO.Path]::GetTempPath()) "t89-evidence-$([guid]::NewGuid()).json"
    $saved = @{}
    $Env['STUB_CAPTURE'] = $capture
    $stateFile = Join-Path ([IO.Path]::GetTempPath()) "t89-state-$([guid]::NewGuid()).txt"
    $Env['STUB_STATE_FILE'] = $stateFile
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
            '-PythonCommand', $(if ($PythonCommand) { $PythonCommand } else { Join-Path $stubs 'stub_python.cmd' }),
            '-EvidenceOutput', $evidence,
            '-ReplicaWaitSeconds', "$WaitSeconds",
            '-ReplicaPollSeconds', '1'
        ) + $Extra
        $out = & powershell @argv 2>&1 | Out-String
        $code = $LASTEXITCODE
        $cap = if (Test-Path $capture) { Get-Content $capture -Raw } else { '' }
        return [pscustomobject]@{ Exit = $code; Capture = $cap; Output = ($out -join "`n") }
    } finally {
        foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k]) }
        Remove-Item $capture, $evidence, $stateFile -ErrorAction SilentlyContinue
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
Check 'no credential env var is passed to the verifier on the command line' {
    $argvLines = [regex]::Matches($r.Capture, '(?m)^python (?!-env).*$')
    foreach ($m in $argvLines) {
        if ($m.Value -match 'TASK8_9_ACCESS_TOKEN|TASK8_9_DEMO_PASSWORD') { throw "credential name in argv: $($m.Value)" }
    }
}
Check 'task credentials are scrubbed from every child process environment' {
    $e2 = $good.Clone()
    $e2['TASK8_9_ACCESS_TOKEN'] = 'sentinel-token-value'
    $e2['TASK8_9_DEMO_PASSWORD'] = 'sentinel-password-value'
    $r2 = Invoke-Wrapper -Env $e2
    if ($r2.Exit -ne 0) { throw "exit $($r2.Exit)" }
    # The stub reports its inherited environment, so this fails if the scrub is
    # removed. Asserting only that the sentinel is absent from argv would pass
    # either way, which is what the previous version of this test did.
    $envLines = [regex]::Matches($r2.Capture, '(?m)^\w+-env .*$')
    $kinds = @($envLines | ForEach-Object { ($_.Value -split '-env')[0] } | Sort-Object -Unique)
    foreach ($k in @('az','curl','docker','python')) {
        if ($k -notin $kinds) { throw "no $k child reported its environment; the scrub is unproven for it" }
    }
    if ($envLines.Count -lt 10) { throw "expected every child to report its env; got $($envLines.Count)" }
    foreach ($m in $envLines) {
        if ($m.Value -match 'sentinel-') { throw "a credential value reached a child process: $($m.Value)" }
    }
}
Check 'the replica-list call passes no --query, so the raw ARM shape is what the gate sees' {
    # The production gate is only valid against raw output. `az containerapp
    # replica list` is a passthrough, so a --query here would silently reshape
    # what the readiness gate reads -- which is exactly how the fixtures came to
    # be modelled on a projected record.
    $lines = [regex]::Matches($r.Capture, '(?m)^az .*replica list.*$')
    if ($lines.Count -lt 1) { throw 'no replica list call was captured' }
    foreach ($m in $lines) {
        if ($m.Value -match '--query') { throw "replica list passed --query, so the gate would see a projection: $($m.Value)" }
        if ($m.Value -notmatch '-o json') { throw "replica list did not request json: $($m.Value)" }
    }
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
Check 'offers no override -- continuing is a fresh owner decision' {
    if ($r.Output -notmatch 'no override') { throw "did not state that there is no override:`n$($r.Output)" }
}
Check 'every external command has its exit code classified' {
    # $ErrorActionPreference is Continue, so a failing native no longer throws.
    # Explicit $LASTEXITCODE handling is the only thing standing between a
    # failed az call and the script carrying on with an empty result.
    $src = Get-Content (Join-Path $repo 'scripts/run_task_8_9_preflight.ps1')
    $nativeLines = @()
    for ($i = 0; $i -lt $src.Count; $i++) {
        if ($src[$i] -match '&\s+\$(Az|Docker|Curl|Python)Command') { $nativeLines += $i }
    }
    if ($nativeLines.Count -lt 8) { throw "expected the native call sites to be found; got $($nativeLines.Count)" }
    foreach ($n in $nativeLines) {
        $window = ($src[$n..([Math]::Min($n + 4, $src.Count - 1))] -join "`n")
        if ($window -notmatch 'LASTEXITCODE') {
            throw "native call at line $($n + 1) has no exit-code check within 4 lines: $($src[$n].Trim())"
        }
    }
}
Check 'the script exposes exactly the pinned parameter surface and reads only the two task credentials' {
    # Asserting a single banned name would miss -ContinueOnNon200, -Force, or an
    # env-var backdoor. Parse the script instead and pin the whole surface.
    #
    # Pin NAMES AND TYPES, not just the switches. An earlier version filtered to
    # StaticType -eq [switch], so it pinned only SkipWake and a non-switch
    # override sailed past it: independent review demonstrated that adding
    # [string]$ProceedOnNon200 and `-and -not $ProceedOnNon200` to the non-200
    # check left the suite fully green, while a 503 wake with that flag reached
    # "preflight passed" and started the verifier. The packet's non-200 stop is
    # absolute and this test is what keeps it that way, so it has to see every
    # parameter of every type. The mutation harness carries a matching mutant.
    $errs = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile(
        (Join-Path $repo 'scripts/run_task_8_9_preflight.ps1'), [ref]$null, [ref]$errs)
    if ($errs) { throw "script does not parse: $($errs[0].Message)" }
    $expected = @(
        'AzCommand:String'
        'CurlCommand:String'
        'DockerCommand:String'
        'EvidenceOutput:String'
        'GatewayApp:String'
        'GatewayUrl:String'
        'OperationTimeoutSeconds:Int32'
        'PortfolioApp:String'
        'ProvenancePath:String'
        'PythonCommand:String'
        'Registry:String'
        'ReplicaPollSeconds:Int32'
        'ReplicaWaitSeconds:Int32'
        'ResourceGroup:String'
        'SkipWake:SwitchParameter'
        'SubscriptionId:String'
        'SubscriptionSourcePath:String'
        'Target:String'
        'VerifierPath:String'
        'WakePath:String'
        'Workspace:String'
    ) -join ','
    $actual = ($ast.ParamBlock.Parameters |
        ForEach-Object { '{0}:{1}' -f $_.Name.VariablePath.UserPath, $_.StaticType.Name } |
        Sort-Object) -join ','
    if ($actual -ne $expected) {
        throw "parameter surface changed.`n  expected: $expected`n  actual:   $actual"
    }
    $envReads = $ast.FindAll({
        param($n)
        $n -is [System.Management.Automation.Language.VariableExpressionAst] -and
        $n.VariablePath.UserPath -like 'env:*'
    }, $true) | ForEach-Object { $_.VariablePath.UserPath } | Sort-Object -Unique
    foreach ($e in $envReads) {
        if ($e -notin @('env:TASK8_9_ACCESS_TOKEN','env:TASK8_9_DEMO_PASSWORD')) {
            throw "unexpected environment read: $e"
        }
    }
}

Write-Host 'Wake transport failure after delivery'
$e = $good.Clone(); $e['STUB_CURL_EXIT'] = '52'
$r = Invoke-Wrapper -Env $e
Check 'does not claim the wake was unconsumed, exits 3, runs nothing further' {
    if ($r.Output -match 'probably NOT consumed') { throw 'claimed unconsumed for a post-delivery curl exit' }
    if ($r.Output -notmatch 'may be consumed') { throw "did not flag possible consumption:`n$($r.Output)" }
    if ($r.Exit -ne 3) { throw "exit $($r.Exit)" }
    if ($r.Capture -match '(?m)^python .*--mode ') { throw 'verifier ran after a transport failure' }
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
    if ($r.Exit -ne 0) { throw "exit $($r.Exit); output: $($r.Output)" }
}
Check 'selects exactly one replica name, not the whole decoded list' {
    # THE discriminating test for the ConvertFrom-Json double-wrap. Exit code
    # alone no longer distinguishes it: with the real container-shaped payloads,
    # a double-wrapped element member-enumerates to the same accept/refuse
    # answer on both multi fixtures. What it cannot fake is the identity of the
    # chosen replica -- double-wrapping makes $replica the entire list, so the
    # announcement carries both names.
    $line = ([regex]::Match($r.Output, '(?m)^.*replica up:.*$')).Value
    if (-not $line) { throw "no 'replica up:' announcement in output:`n$($r.Output)" }
    if ($line -notmatch 'zeta-9f2') { throw "wrong replica selected: $line" }
    if ($line -match 'alpha-3c1') { throw "selected more than one replica (decoded list is being double-wrapped): $line" }
}

Write-Host 'Replica ripens across polls: [] then NotRunning then Running'
$e = $good.Clone(); $e['STUB_REPLICA'] = 'ripening'
$r = Invoke-Wrapper -Env $e -WaitSeconds 20
Check 'keeps polling until the replica is ready, rather than giving up early' {
    # Stateless stubs cannot see this: a mutation that abandons the wait on the
    # first empty or not-ready poll passes every other test while spending the
    # wake for nothing against real Azure.
    if ($r.Exit -ne 0) { throw "exit $($r.Exit); output: $($r.Output)" }
    $n = ([regex]::Matches($r.Capture, 'replica list')).Count
    if ($n -lt 3) { throw "polled $n time(s); expected at least 3 across the ripening sequence" }
}

Write-Host 'Only the container ready flag says not-ready'
$e = $good.Clone(); $e['STUB_REPLICA'] = 'notready-only'
$r = Invoke-Wrapper -Env $e
Check 'refuses on containers[0].ready alone' {
    if ($r.Exit -ne 3) { throw "exit $($r.Exit); output: $($r.Output)" }
    if ($r.Capture -match '(?m)^python .*--mode ') { throw 'verifier ran at a not-ready container' }
}

Write-Host 'Only the container runningState says not-ready'
$e = $good.Clone(); $e['STUB_REPLICA'] = 'badstate-only'
$r = Invoke-Wrapper -Env $e
Check 'refuses on containers[0].runningState alone' {
    if ($r.Exit -ne 3) { throw "exit $($r.Exit); output: $($r.Output)" }
    if ($r.Capture -match '(?m)^python .*--mode ') { throw 'verifier ran at a Waiting container' }
}

Write-Host 'A projected (flat) payload still works'
$e = $good.Clone(); $e['STUB_REPLICA'] = 'flat'
$r = Invoke-Wrapper -Env $e
Check 'accepts a --query-projected replica record' {
    if ($r.Exit -ne 0) { throw "exit $($r.Exit); output: $($r.Output)" }
}

Write-Host 'Replica reports only the ARM-style properties.runningState'
$e = $good.Clone(); $e['STUB_REPLICA'] = 'armshape'
$r = Invoke-Wrapper -Env $e
Check 'refuses a not-Running replica in the ARM shape too' {
    if ($r.Exit -ne 3) { throw "exit $($r.Exit); output: $($r.Output)" }
    if ($r.Capture -match '(?m)^python .*--mode ') { throw 'verifier ran at a not-ready replica' }
}

Write-Host 'Two replicas, the first one NOT ready'
$e = $good.Clone(); $e['STUB_REPLICA'] = 'multi-reversed'
$r = Invoke-Wrapper -Env $e
Check 'refuses even though a later replica is Running' {
    # The mirror of the test above, and the one that pins selection rather than
    # the gate's operator: any-of-many selection would accept here because
    # rep-b is Running, but the verifier execs replicas[0] = rep-a.
    if ($r.Exit -ne 3) { throw "exit $($r.Exit) - selection is probably any-of-many, not first; output: $($r.Output)" }
    if ($r.Capture -match '(?m)^python .*--mode ') { throw 'verifier ran at a not-ready replicas[0]' }
    if ($r.Output -notmatch 'never reached Running') { throw "did not report the not-ready replica:`n$($r.Output)" }
}

Write-Host 'Replica reports no runningState'
$e = $good.Clone(); $e['STUB_REPLICA'] = 'stateless'
$r = Invoke-Wrapper -Env $e
Check 'accepts deliberately and says so' {
    if ($r.Exit -ne 0) { throw "exit $($r.Exit); output: $($r.Output)" }
    if ($r.Output -notmatch 'no runningState') { throw "fail-open not announced:`n$($r.Output)" }
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
Check 'reports the wake as probably NOT consumed, exits 3, runs nothing further' {
    if ($r.Output -notmatch 'probably NOT consumed') { throw "output did not flag an unconsumed wake: $($r.Output)" }
    if ($r.Exit -ne 3) { throw "exit $($r.Exit)" }
    if ($r.Capture -match '(?m)^python .*--mode ') { throw 'verifier ran after a transport failure' }
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


# --- Behavioural proof that every child's exit code is classified -----------
# The structural scan above is only a completeness alarm: "a $LASTEXITCODE
# within four lines" would accept a stale read, and a call-site count survives
# deleting one child. These drive each phase to fail for real and assert both
# the exit code AND the prohibited downstream action.

$phases = @(
    @{ Name = 'az account show (session)';      Env = @{ STUB_AZ_FAIL_MATCH = '--query name' } },
    @{ Name = 'az account show (subscription)'; Env = @{ STUB_AZ_FAIL_MATCH = '--query id' } },
    @{ Name = 'docker version';                 Env = @{ STUB_DOCKER_EXIT = '1' } },
    @{ Name = 'gateway revision read';          Env = @{ STUB_AZ_FAIL_MATCH = 'latestReadyRevisionName'; STUB_AZ_FAIL_MATCH2 = 'api-gateway' } },
    @{ Name = 'gateway image read';             Env = @{ STUB_AZ_FAIL_MATCH = 'containers[0].image'; STUB_AZ_FAIL_MATCH2 = 'api-gateway' } },
    @{ Name = 'portfolio revision read';        Env = @{ STUB_AZ_FAIL_MATCH = 'latestReadyRevisionName'; STUB_AZ_FAIL_MATCH2 = 'portfolio-service' } },
    @{ Name = 'portfolio image read';           Env = @{ STUB_AZ_FAIL_MATCH = 'containers[0].image'; STUB_AZ_FAIL_MATCH2 = 'portfolio-service' } },
    @{ Name = 'log analytics workspace read';   Env = @{ STUB_AZ_FAIL_MATCH = 'log-analytics' } },
    @{ Name = 'verifier --help probe';          Env = @{ STUB_PYTHON_HELP_EXIT = '2' } },
    @{ Name = 'gateway revision list';          Env = @{ STUB_AZ_FAIL_MATCH = 'revision list' } }
)
Write-Host 'Each pre-wake child failure stops before the wake'
foreach ($phase in $phases) {
    $e = $good.Clone()
    foreach ($k in $phase.Env.Keys) { $e[$k] = $phase.Env[$k] }
    $r = Invoke-Wrapper -Env $e
    Check "$($phase.Name) failing => exit 2, no wake, no verifier" {
        if ($r.Exit -ne 2) { throw "exit $($r.Exit) (expected 2); output: $($r.Output)" }
        if ($r.Capture -match '(?m)^curl ') { throw 'a wake was issued despite a failed precondition' }
        if ($r.Capture -match '(?m)^python .*--mode ') { throw 'the verifier ran despite a failed precondition' }
    }
}

# --- A read that succeeds but returns nothing --------------------------------
# `az ... --query <path> -o tsv` exits 0 and prints an empty line whenever the
# JMESPath does not resolve, so no exit-code branch sees it. Before 2026-09-12
# the two digest guards were inert in exactly this case: a no-output native
# command assigns AutomationNull, and -notlike against it yields an empty
# collection, which is FALSE. The wrapper would have proceeded to wake
# production with the serving digest unverified. Each case below asserts the
# run stops, and that it stops BEFORE the wake and the verifier.

$emptyPhases = @(
    @{ Name = 'az account show (session)';      Env = @{ STUB_AZ_EMPTY_MATCH = '--query name' } },
    @{ Name = 'az account show (subscription)'; Env = @{ STUB_AZ_EMPTY_MATCH = '--query id' } },
    @{ Name = 'gateway revision read';          Env = @{ STUB_AZ_EMPTY_MATCH = 'latestReadyRevisionName'; STUB_AZ_EMPTY_MATCH2 = 'api-gateway' } },
    @{ Name = 'gateway image read';             Env = @{ STUB_AZ_EMPTY_MATCH = 'containers[0].image'; STUB_AZ_EMPTY_MATCH2 = 'api-gateway' } },
    @{ Name = 'portfolio revision read';        Env = @{ STUB_AZ_EMPTY_MATCH = 'latestReadyRevisionName'; STUB_AZ_EMPTY_MATCH2 = 'portfolio-service' } },
    @{ Name = 'portfolio image read';           Env = @{ STUB_AZ_EMPTY_MATCH = 'containers[0].image'; STUB_AZ_EMPTY_MATCH2 = 'portfolio-service' } },
    @{ Name = 'log analytics workspace read';   Env = @{ STUB_AZ_EMPTY_MATCH = 'log-analytics' } },
    @{ Name = 'gateway revision list';          Env = @{ STUB_AZ_EMPTY_MATCH = 'revision list' } },
    @{ Name = 'docker version';                 Env = @{ STUB_DOCKER_EMPTY = '1' } }
)
Write-Host 'A read that exits 0 with no output stops before the wake'
foreach ($phase in $emptyPhases) {
    $e = $good.Clone()
    foreach ($k in $phase.Env.Keys) { $e[$k] = $phase.Env[$k] }
    $r = Invoke-Wrapper -Env $e
    Check "$($phase.Name) returning nothing => exit 2, no wake, no verifier" {
        if ($r.Exit -ne 2) { throw "exit $($r.Exit) (expected 2); output: $($r.Output)" }
        if ($r.Capture -match '(?m)^curl ') { throw 'a wake was issued on an empty precondition read' }
        if ($r.Capture -match '(?m)^python .*--mode ') { throw 'the verifier ran on an empty precondition read' }
    }
}

# --- A read that emits the right value and THEN fails ------------------------
# Only the $LASTEXITCODE branch can catch this: every downstream value
# comparison is satisfied by the payload. Branch-removal mutation showed that
# without these fixtures the classifications behind a value guard could be
# deleted with the suite still green.

$failAfterPhases = @(
    @{ Name = 'az account show (session)';      Env = @{ STUB_AZ_FAILAFTER_MATCH = '--query name' } },
    @{ Name = 'az account show (subscription)'; Env = @{ STUB_AZ_FAILAFTER_MATCH = '--query id' } },
    @{ Name = 'gateway revision read';          Env = @{ STUB_AZ_FAILAFTER_MATCH = 'latestReadyRevisionName'; STUB_AZ_FAILAFTER_MATCH2 = 'api-gateway' } },
    @{ Name = 'gateway image read';             Env = @{ STUB_AZ_FAILAFTER_MATCH = 'containers[0].image'; STUB_AZ_FAILAFTER_MATCH2 = 'api-gateway' } },
    @{ Name = 'portfolio revision read';        Env = @{ STUB_AZ_FAILAFTER_MATCH = 'latestReadyRevisionName'; STUB_AZ_FAILAFTER_MATCH2 = 'portfolio-service' } },
    @{ Name = 'portfolio image read';           Env = @{ STUB_AZ_FAILAFTER_MATCH = 'containers[0].image'; STUB_AZ_FAILAFTER_MATCH2 = 'portfolio-service' } },
    @{ Name = 'log analytics workspace read';   Env = @{ STUB_AZ_FAILAFTER_MATCH = 'log-analytics' } },
    @{ Name = 'gateway revision list';          Env = @{ STUB_AZ_FAILAFTER_MATCH = 'revision list' } },
    @{ Name = 'docker version';                 Env = @{ STUB_DOCKER_FAILAFTER = '1' } }
)
Write-Host 'A read that emits a plausible value and then fails stops before the wake'
foreach ($phase in $failAfterPhases) {
    $e = $good.Clone()
    foreach ($k in $phase.Env.Keys) { $e[$k] = $phase.Env[$k] }
    $r = Invoke-Wrapper -Env $e
    Check "$($phase.Name) failing after output => exit 2, no wake, no verifier" {
        if ($r.Exit -ne 2) { throw "exit $($r.Exit) (expected 2); output: $($r.Output)" }
        if ($r.Capture -match '(?m)^curl ') { throw 'a wake was issued after a child reported failure' }
        if ($r.Capture -match '(?m)^python .*--mode ') { throw 'the verifier ran after a child reported failure' }
    }
}

Write-Host 'A wake that reports no status at all stops the run'
$e = $good.Clone(); $e['STUB_CURL_EMPTY'] = '1'
$r = Invoke-Wrapper -Env $e
Check 'wake with no status code => exit 3, no verifier' {
    if ($r.Exit -ne 3) { throw "exit $($r.Exit) (expected 3); output: $($r.Output)" }
    if ($r.Capture -match '(?m)^python .*--mode ') { throw 'the verifier ran on an unverified wake status' }
}

Write-Host 'Post-wake child failures stop before the next phase'
$e = $good.Clone(); $e['STUB_POLL_EXIT'] = '3'
$r = Invoke-Wrapper -Env $e
Check 'replica poll failing => exit 3, no verifier' {
    if ($r.Exit -ne 3) { throw "exit $($r.Exit); output: $($r.Output)" }
    if ($r.Capture -match '(?m)^python .*--mode ') { throw 'the verifier ran without a resolved replica' }
}

Write-Host 'SkipWake refuses to run against real commands'
$r = Invoke-Wrapper -Env $good.Clone() -Extra @('-SkipWake') -PythonCommand 'python'
Check 'exits 2 rather than bypassing the non-200 stop with real tools' {
    if ($r.Exit -ne 2) { throw "exit $($r.Exit); output: $($r.Output)" }
    if ($r.Output -notmatch 'offline tests') { throw "did not explain why:`n$($r.Output)" }
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
