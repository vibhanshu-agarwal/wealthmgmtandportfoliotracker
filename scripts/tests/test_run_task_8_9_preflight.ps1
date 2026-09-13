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
            '-EvidenceOutput', $evidence
        )
        # Defaults are omitted when -Extra supplies them, so a test can drive a
        # parameter to a value the helper would otherwise fix. Passing the same
        # parameter twice is a binding error, which would surface as exit 1 and
        # look like a wrapper fault rather than a harness one.
        # Command overrides follow the same rule, so a test can leave one at its
        # real default to prove the SkipWake guard refuses it.
        if ($Extra -notcontains '-AzCommand') { $argv += @('-AzCommand', (Join-Path $stubs 'stub_az.cmd')) }
        if ($Extra -notcontains '-DockerCommand') { $argv += @('-DockerCommand', (Join-Path $stubs 'stub_docker.cmd')) }
        if ($Extra -notcontains '-CurlCommand') { $argv += @('-CurlCommand', (Join-Path $stubs 'stub_curl.cmd')) }
        if ($Extra -notcontains '-PythonCommand') { $argv += @('-PythonCommand', $(if ($PythonCommand) { $PythonCommand } else { Join-Path $stubs 'stub_python.cmd' })) }
        if ($Extra -notcontains '-ReplicaWaitSeconds') { $argv += @('-ReplicaWaitSeconds', "$WaitSeconds") }
        if ($Extra -notcontains '-ReplicaPollSeconds') { $argv += @('-ReplicaPollSeconds', '1') }
        $argv += $Extra
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
Check 'the wake uses exactly the authorized argument vector, once, with no stub violation' {
    # Counting curl lines was not enough: --retry makes several HTTP requests
    # from ONE invocation, and -L follows a redirect. Only the full vector says
    # which request was actually made.
    $lines = @($r.Capture -split "`r?`n" | Where-Object { $_ -match '^curl ' })
    if ($lines.Count -ne 1) { throw "expected exactly one curl invocation, found $($lines.Count)" }
    $want = 'curl -q -sS -o NUL -w %{http_code} https://api.vibhanshu-ai-portfolio.dev/actuator/health'
    if ($lines[0].TrimEnd() -cne $want) { throw "curl was called as:`n  $($lines[0])`nexpected exactly:`n  $want" }
    if ($r.Capture -match '(?m)^curl-violation') { throw 'the curl stub recorded an argument-vector violation' }
}
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
# --- Only an exact 200 may reach the verifier --------------------------------
# One 503 case is not a pin. Review demonstrated bypasses that keep the suite
# fully green while a non-200 proceeds: widening the comparison
# (-notin @('200','502')), pattern-matching the status (-like '5*'), or gating
# on something other than a declared parameter. Those all survive a test that
# only ever sends 503. This sweeps the classes and asserts the same three
# things for each: exit 3, no verifier, exactly one wake.
$nonOk = @(
    @{ Name = '000 (curl transport sentinel)'; Env = @{ STUB_WAKE_STATUS = '000' } },
    @{ Name = 'empty status';                  Env = @{ STUB_CURL_EMPTY = '1' } },
    @{ Name = '100 informational';             Env = @{ STUB_WAKE_STATUS = '100' } },
    @{ Name = '201 non-200 success';           Env = @{ STUB_WAKE_STATUS = '201' } },
    @{ Name = '204 non-200 success';           Env = @{ STUB_WAKE_STATUS = '204' } },
    @{ Name = '301 redirect';                  Env = @{ STUB_WAKE_STATUS = '301' } },
    @{ Name = '302 redirect';                  Env = @{ STUB_WAKE_STATUS = '302' } },
    @{ Name = '400 bad request';               Env = @{ STUB_WAKE_STATUS = '400' } },
    @{ Name = '404 not found';                 Env = @{ STUB_WAKE_STATUS = '404' } },
    @{ Name = '429 throttled';                 Env = @{ STUB_WAKE_STATUS = '429' } },
    @{ Name = '500 server error';              Env = @{ STUB_WAKE_STATUS = '500' } },
    @{ Name = '502 bad gateway';               Env = @{ STUB_WAKE_STATUS = '502' } },
    @{ Name = '503 unavailable';               Env = @{ STUB_WAKE_STATUS = '503' } },
    @{ Name = '504 gateway timeout';           Env = @{ STUB_WAKE_STATUS = '504' } },
    @{ Name = '307 temporary redirect';        Env = @{ STUB_WAKE_STATUS = '307' } },
    @{ Name = '308 permanent redirect';        Env = @{ STUB_WAKE_STATUS = '308' } },
    @{ Name = '401 unauthorized';              Env = @{ STUB_WAKE_STATUS = '401' } },
    @{ Name = '403 forbidden';                 Env = @{ STUB_WAKE_STATUS = '403' } },
    @{ Name = '405 method not allowed';        Env = @{ STUB_WAKE_STATUS = '405' } },
    @{ Name = '407 proxy auth required';       Env = @{ STUB_WAKE_STATUS = '407' } },
    @{ Name = '408 request timeout';           Env = @{ STUB_WAKE_STATUS = '408' } },
    @{ Name = '501 not implemented';           Env = @{ STUB_WAKE_STATUS = '501' } },
    @{ Name = '505 http version not supported'; Env = @{ STUB_WAKE_STATUS = '505' } }
)
Write-Host 'Only an exact 200 may reach the verifier'
foreach ($case in $nonOk) {
    $e = $good.Clone()
    foreach ($k in $case.Env.Keys) { $e[$k] = $case.Env[$k] }
    $r = Invoke-Wrapper -Env $e
    Check "$($case.Name) => exit 3, no verifier, exactly one wake" {
        if ($r.Exit -ne 3) { throw "exit $($r.Exit) (expected 3); output: $($r.Output)" }
        if ($r.Capture -match '(?m)^python .*--mode ') { throw 'the verifier ran after a non-200 wake' }
        $n = ([regex]::Matches($r.Capture, '(?m)^curl ')).Count
        if ($n -ne 1) { throw "wake issued $n times, expected exactly 1" }
    }
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
# --- The wake decision, pinned through the AST --------------------------------
# The invariant: the verifier may start only when exactly one invocation of the
# authorized wake request used the fixed URL and fixed argument vector, exited
# successfully, and returned exactly the scalar string '200'. No parameter,
# environment value, configuration input, alternate function or surrounding
# control flow may alter that decision.
#
# Earlier versions of these pins checked the SHAPE of one condition and were
# defeated from just outside it: a variable pre-assigned before the check, the
# Fail inside the branch wrapped in another if, Fail itself redefined, an
# environment read through a provider path, curl's -w changed so every response
# read 200, --retry, -L, a different URL. Pinning the literal text of the block
# would not help either -- it fails on the same edits made one line outside the
# pinned extent, and turns comment and formatting changes into security events.
#
# So the decision is built as one safety unit (see Invoke-AuthorizedWake in the
# wrapper), and the checks below are targeted regression guards over its
# structure, not its spelling: they catch accidental drift -- a stray variable
# reference, a second curl call, a decision that no longer exits -- that the
# behavioural tests above might not surface.
#
# They are NOT proof against a deliberate adversarial rewrite, and are not meant
# to be. A determined author can still edit inside the unit to pass these guards
# while misbehaving only in production (e.g. rewrite the status before the
# comparison, or branch on whether $Curl is a test stub). Catching that is a
# code-review responsibility; the acceptance bar here is fail-closed runtime
# behaviour plus meaningful regression coverage, not malicious-author detection.
$wrapperErrs = $null
$wrapperAst = [System.Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $repo 'scripts/run_task_8_9_preflight.ps1'), [ref]$null, [ref]$wrapperErrs)

function Find-Ast {
    param($Root, [scriptblock]$Pred)
    if ($null -eq $Root) { return @() }
    @($Root.FindAll($Pred, $true))
}
function Norm { param([string]$T) ($T -replace '\s+', ' ').Trim() }
function Target-Var {
    # The variable an assignment writes, looking through a type conversion such
    # as [string]$x = ..., which would otherwise hide the target.
    param($Left)
    while ($Left -is [System.Management.Automation.Language.ConvertExpressionAst]) { $Left = $Left.Child }
    if ($Left -is [System.Management.Automation.Language.VariableExpressionAst]) { return $Left.VariablePath.UserPath }
    return $null
}

$unitDefs = @(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Invoke-AuthorizedWake' })
$script:unit = if ($unitDefs.Count -eq 1) { $unitDefs[0] } else { $null }
function Inside-Unit {
    param($n)
    if ($null -eq $script:unit) { return $false }
    ($n.Extent.StartOffset -ge $script:unit.Extent.StartOffset) -and ($n.Extent.EndOffset -le $script:unit.Extent.EndOffset)
}
$wakeCalls = @(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'Invoke-AuthorizedWake' })
$skipIfs = @(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.IfStatementAst] -and (Norm $n.Clauses[0].Item1.Extent.Text) -eq '$SkipWake' })
$wakeBranch = @($skipIfs | Where-Object { $null -ne $_.ElseClause })
$skipGuard = @($skipIfs | Where-Object { $null -eq $_.ElseClause })
$childCalls = @(Find-Ast $wrapperAst {
    param($n)
    $n -is [System.Management.Automation.Language.CommandAst] -and
    $n.CommandElements[0] -is [System.Management.Automation.Language.VariableExpressionAst] -and
    @('AzCommand', 'DockerCommand', 'PythonCommand', 'Curl', 'CurlCommand') -contains $n.CommandElements[0].VariablePath.UserPath
})

Check 'the wake decision lives in exactly one safety unit, called once and alone from the non-SkipWake branch' {
    if ($wrapperErrs) { throw "wrapper does not parse: $($wrapperErrs[0].Message)" }
    if ($unitDefs.Count -ne 1) { throw "expected exactly one Invoke-AuthorizedWake definition, found $($unitDefs.Count)" }
    if ($wakeCalls.Count -ne 1) { throw "expected exactly one call to Invoke-AuthorizedWake, found $($wakeCalls.Count)" }
    if ($skipIfs.Count -ne 2 -or $wakeBranch.Count -ne 1 -or $skipGuard.Count -ne 1) {
        throw "expected exactly two if-statements on `$SkipWake (the early guard, and the wake branch with an else); found $($skipIfs.Count)"
    }
    $w = $wakeBranch[0]
    if ($w.Clauses.Count -ne 1) { throw 'the wake branch has extra clauses' }
    # Nothing may sit between the script body and the wake branch except the
    # single top-level try/finally that restores credentials.
    $p = $w.Parent; $tries = 0
    while ($null -ne $p.Parent) {
        foreach ($bad in @('IfStatementAst', 'LoopStatementAst', 'SwitchStatementAst', 'FunctionDefinitionAst', 'TrapStatementAst', 'CatchClauseAst', 'ScriptBlockExpressionAst')) {
            if ($p -is [type]"System.Management.Automation.Language.$bad") { throw "the wake branch is nested inside a $bad" }
        }
        if ($p -is [System.Management.Automation.Language.TryStatementAst]) {
            $tries++
            if (@($p.CatchClauses).Count) { throw 'the wake branch is inside a try that has catch clauses' }
        }
        $p = $p.Parent
    }
    if ($tries -gt 1) { throw "the wake branch is inside $tries try statements" }
    $elseStmts = @($w.ElseClause.Statements)
    if ($elseStmts.Count -ne 1) { throw "the else branch must contain only the wake call; it has $($elseStmts.Count) statements" }
    $only = $elseStmts[0]
    if (-not ($only -is [System.Management.Automation.Language.PipelineAst]) -or @($only.PipelineElements).Count -ne 1 -or -not [object]::ReferenceEquals($only.PipelineElements[0], $wakeCalls[0])) {
        throw "the else branch's only statement is not the bare wake call: $($only.Extent.Text)"
    }
    if ((Norm $wakeCalls[0].Extent.Text) -cne 'Invoke-AuthorizedWake -Curl $CurlCommand') { throw "the wake call is '$($wakeCalls[0].Extent.Text)'" }
}

Check '-SkipWake is refused before any child process unless all four commands are overridden' {
    if ($skipGuard.Count -ne 1) { throw 'no early SkipWake guard' }
    $g = $skipGuard[0]
    if (@($childCalls | Where-Object { $_.Extent.StartOffset -lt $g.Extent.StartOffset -and -not (Inside-Unit $_) }).Count) {
        throw 'a child process can run before the SkipWake guard'
    }
    $cmps = @(Find-Ast $g { param($n) $n -is [System.Management.Automation.Language.BinaryExpressionAst] -and $n.Operator -eq 'Ieq' } | ForEach-Object { Norm $_.Extent.Text } | Sort-Object)
    $want = @("`$AzCommand -eq 'az'", "`$CurlCommand -eq 'curl.exe'", "`$DockerCommand -eq 'docker'", "`$PythonCommand -eq 'python'") | Sort-Object
    if (($cmps -join ' | ') -cne ($want -join ' | ')) { throw "SkipWake guard compares:`n  $($cmps -join "`n  ")" }
    $fails = @(Find-Ast $g { param($n) $n -is [System.Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'Fail' })
    if ($fails.Count -ne 1 -or (Norm @($fails[0].CommandElements)[-1].Extent.Text) -ne '2') { throw 'the SkipWake guard must Fail with exit 2' }
}

Check 'the safety unit makes the only curl call, with exactly the fixed arguments and URL' {
    $curlCalls = @(Find-Ast $wrapperAst {
        param($n)
        $n -is [System.Management.Automation.Language.CommandAst] -and (
            ($n.CommandElements[0] -is [System.Management.Automation.Language.VariableExpressionAst] -and
             @('Curl', 'CurlCommand') -contains $n.CommandElements[0].VariablePath.UserPath) -or
            ("$($n.GetCommandName())" -match '(?i)^curl(\.exe)?$'))
    })
    if ($curlCalls.Count -ne 1) { throw "expected exactly one curl invocation in the script, found $($curlCalls.Count)" }
    if (-not (Inside-Unit $curlCalls[0])) { throw 'the curl invocation is outside the safety unit' }
    $actual = @($curlCalls[0].CommandElements | ForEach-Object { $_.Extent.Text })
    $expected = @('$Curl', '-q', '-sS', '-o', 'NUL', '-w', "'%{http_code}'", "'https://api.vibhanshu-ai-portfolio.dev/actuator/health'")
    if (($actual -join ' ') -cne ($expected -join ' ')) { throw "curl argument vector is: $($actual -join ' ')" }
    $ps = @($script:unit.Body.ParamBlock.Parameters)
    if ($ps.Count -ne 1 -or $ps[0].Name.VariablePath.UserPath -ne 'Curl' -or $ps[0].StaticType -ne [string]) {
        throw 'the safety unit must take exactly one [string]$Curl parameter'
    }
}

Check 'every decision in the safety unit ends in a direct exit 3 that nothing can intercept' {
    $top = @($script:unit.Body.EndBlock.Statements | Where-Object { $_ -is [System.Management.Automation.Language.IfStatementAst] })
    $conds = @($top | ForEach-Object { Norm $_.Clauses[0].Item1.Extent.Text })
    $expected = @('$wakeCurlExit -ne 0', '-not ($wakeStatus -is [string]) -or $wakeStatus.Length -eq 0', "`$wakeStatus -cne '200'")
    if (($conds -join ' | ') -cne ($expected -join ' | ')) {
        throw "safety-unit decisions are, in order:`n  $($conds -join "`n  ")`nexpected exactly:`n  $($expected -join "`n  ")"
    }
    foreach ($i in $top) {
        if ($i.Clauses.Count -ne 1 -or $null -ne $i.ElseClause) { throw "decision '$(Norm $i.Clauses[0].Item1.Extent.Text)' has extra clauses" }
        $last = @($i.Clauses[0].Item2.Statements)[-1]
        if (-not ($last -is [System.Management.Automation.Language.ExitStatementAst]) -or (Norm $last.Pipeline.Extent.Text) -ne '3') {
            throw "decision '$(Norm $i.Clauses[0].Item1.Extent.Text)' does not end in a direct exit 3"
        }
    }
    foreach ($kind in @('ReturnStatementAst', 'TrapStatementAst', 'TryStatementAst', 'FunctionDefinitionAst', 'BreakStatementAst', 'ContinueStatementAst', 'ScriptBlockExpressionAst')) {
        $t = [type]"System.Management.Automation.Language.$kind"
        if (@(Find-Ast $script:unit.Body { param($n) $n -is $t }.GetNewClosure()).Count) { throw "the safety unit contains a $kind" }
    }
    if (@(Find-Ast $script:unit { param($n) $n -is [System.Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'Fail' }).Count) {
        throw 'the safety unit calls Fail, which is ordinary code that could be redefined'
    }
    $exits = @(Find-Ast $script:unit { param($n) $n -is [System.Management.Automation.Language.ExitStatementAst] })
    if ($exits.Count -ne 3) { throw "expected exactly 3 exit statements in the safety unit, found $($exits.Count)" }
}

Check 'the safety unit reads only its own decision variables, each assigned once' {
    $allowed = @('Curl', 'script:WakeIssued', 'wakeStatus', 'wakeCurlExit', 'LASTEXITCODE', 'true', 'false', 'null')
    $vars = @(Find-Ast $script:unit { param($n) $n -is [System.Management.Automation.Language.VariableExpressionAst] } | ForEach-Object { $_.VariablePath.UserPath } | Sort-Object -Unique)
    $stray = @($vars | Where-Object { $allowed -notcontains $_ })
    if ($stray.Count) { throw "the safety unit reads variables outside its own decision: $($stray -join ', ')" }
    foreach ($v in @('wakeStatus', 'wakeCurlExit')) {
        $asg = @(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] -and (Target-Var $n.Left) -eq $v }.GetNewClosure())
        if ($asg.Count -ne 1) { throw "`$$v must be assigned exactly once, found $($asg.Count)" }
        if (-not (Inside-Unit $asg[0])) { throw "`$$v is assigned outside the safety unit" }
    }
}

Check 'the decision variables and wake target are not referenced or reassigned outside the safety unit' {
    foreach ($v in @('wakeStatus', 'wakeCurlExit')) {
        $out = @(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.VariableExpressionAst] -and $n.VariablePath.UserPath -eq $v }.GetNewClosure() | Where-Object { -not (Inside-Unit $_) })
        if ($out.Count) { throw "`$$v is referenced outside the safety unit" }
    }
    if (@(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] -and (Target-Var $n.Left) -eq 'CurlCommand' }).Count) {
        throw '$CurlCommand is reassigned after binding, which could redirect the wake'
    }
    $wi = @(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] -and (Target-Var $n.Left) -eq 'script:WakeIssued' })
    $inUnit = @($wi | Where-Object { Inside-Unit $_ })
    $outUnit = @($wi | Where-Object { -not (Inside-Unit $_) })
    if ($inUnit.Count -ne 1 -or (Norm $inUnit[0].Right.Extent.Text) -ne '$true') { throw 'the safety unit must set $script:WakeIssued = $true exactly once' }
    if ($outUnit.Count -ne 1 -or (Norm $outUnit[0].Right.Extent.Text) -ne '$false') { throw '$script:WakeIssued must be initialised to $false exactly once outside the unit and set nowhere else' }
    $fns = @(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] } | ForEach-Object { $_.Name } | Sort-Object)
    if (($fns -join ',') -cne 'Fail,Invoke-AuthorizedWake,Write-Step') { throw "function definitions are '$($fns -join ',')'; any other definition could shadow a guarded one" }
}

Check 'Fail always exits with the code it is given' {
    $f = @(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Fail' })
    if ($f.Count -ne 1) { throw "expected exactly one Fail definition, found $($f.Count)" }
    $last = @($f[0].Body.EndBlock.Statements)[-1]
    if (-not ($last -is [System.Management.Automation.Language.ExitStatementAst]) -or (Norm $last.Pipeline.Extent.Text) -ne '$Code') { throw 'Fail does not end in exit $Code' }
    foreach ($kind in @('IfStatementAst', 'ReturnStatementAst', 'TrapStatementAst', 'TryStatementAst', 'SwitchStatementAst', 'LoopStatementAst')) {
        $t = [type]"System.Management.Automation.Language.$kind"
        if (@(Find-Ast $f[0].Body { param($n) $n -is $t }.GetNewClosure()).Count) { throw "Fail contains a $kind, so it could return without exiting" }
    }
}

Check 'the script forbids dynamic execution, alias or function replacement, and ambient input channels' {
    if (@($wrapperAst.ParamBlock.Attributes | ForEach-Object { $_.TypeName.Name }) -notcontains 'CmdletBinding') {
        throw '[CmdletBinding()] is absent, so unknown parameters would land in $args instead of being rejected'
    }
    if ($wrapperAst.Extent.Text -match '(?im)^\s*dynamicparam\b') { throw 'dynamicparam block present' }
    $bannedCmds = @('Invoke-Expression', 'iex', 'Set-Alias', 'New-Alias', 'sal', 'nal', 'Import-Alias', 'Set-Item', 'si', 'New-Item', 'ni',
                    'Set-Variable', 'sv', 'New-Variable', 'nv', 'Clear-Variable', 'clv', 'Remove-Variable', 'rv', 'Start-Process', 'saps', 'start',
                    'Invoke-Command', 'icm', 'Add-Type', 'Import-Module', 'ipmo', 'Invoke-Item', 'ii', 'Get-Item', 'gi', 'Get-ChildItem', 'gci', 'ls', 'dir')
    $allowedHeads = @('AzCommand', 'DockerCommand', 'PythonCommand', 'Curl', 'expr')
    foreach ($c in (Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.CommandAst] })) {
        $head = $c.CommandElements[0]
        if ($head -is [System.Management.Automation.Language.VariableExpressionAst]) {
            if ($allowedHeads -notcontains $head.VariablePath.UserPath) { throw "forbidden dynamic invocation: $($c.Extent.Text)" }
        } elseif (-not ($head -is [System.Management.Automation.Language.StringConstantExpressionAst])) {
            throw "forbidden computed command: $($c.Extent.Text)"
        } elseif ($bannedCmds -contains "$($c.GetCommandName())") {
            throw "forbidden command: $($c.GetCommandName())"
        }
    }
    foreach ($s in (Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.StringConstantExpressionAst] -or $n -is [System.Management.Automation.Language.ExpandableStringExpressionAst] })) {
        if ("$($s.Value)" -match '(?i)\b(env|alias|function|variable):') { throw "forbidden provider path in a string: $($s.Extent.Text)" }
    }
    foreach ($t in (Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.TypeExpressionAst] -or $n -is [System.Management.Automation.Language.TypeConstraintAst] })) {
        if ("$($t.TypeName.FullName)" -match '(?i)(^|\.)(Environment|ScriptBlock|PowerShell|Runspace\w*|SessionState\w*|Process)$') { throw "forbidden type: [$($t.TypeName.FullName)]" }
    }
    foreach ($m in (Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.MemberExpressionAst] })) {
        if ("$($m.Member.Extent.Text)" -match '(?i)^(InvokeScript|NewScriptBlock|Create|GetEnvironmentVariables?|ExpandEnvironmentVariables|SetEnvironmentVariable|Invoke|InvokeReturnAsIs|Start)$') {
            throw "forbidden member: $($m.Extent.Text)"
        }
    }
    $bannedVars = @('args', 'PSBoundParameters', 'ExecutionContext', 'input', 'MyInvocation', 'PSCmdlet')
    foreach ($v in (Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.VariableExpressionAst] })) {
        if ($bannedVars -contains $v.VariablePath.UserPath) { throw "forbidden variable: `$$($v.VariablePath.UserPath)" }
        if ($v.VariablePath.UserPath -match '(?i)^env:' -and @('env:TASK8_9_ACCESS_TOKEN', 'env:TASK8_9_DEMO_PASSWORD') -notcontains $v.VariablePath.UserPath) {
            throw "forbidden environment read: `$$($v.VariablePath.UserPath)"
        }
    }
}

Check 'the verifier starts exactly once, and only after the wake decision' {
    $py = @($childCalls | Where-Object { $_.CommandElements[0].VariablePath.UserPath -eq 'PythonCommand' })
    if ($py.Count -ne 2) { throw "expected exactly two python invocations (the --help probe and the verifier), found $($py.Count)" }
    $w = $wakeBranch[0]
    $probe = @($py | Where-Object { (Norm $_.Extent.Text) -match '--help' })
    $run = @($py | Where-Object { (Norm $_.Extent.Text) -notmatch '--help' })
    if ($probe.Count -ne 1 -or $probe[0].Extent.StartOffset -gt $w.Extent.StartOffset) { throw 'the --help probe must be the only python call before the wake' }
    if ($run.Count -ne 1 -or $run[0].Extent.StartOffset -lt $w.Extent.EndOffset) { throw 'the verifier run must come after the wake decision' }
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

# --- the trap, and what it tells the operator about the wake -----------------
# These two messages decide whether an operator believes their single
# authorized wake was spent. Both were previously untested: deleting the trap,
# or the $script:WakeIssued assignment, left the suite fully green while the
# run reported the opposite of the truth about consumption.
Write-Host 'An unhandled error reports wake consumption correctly'

# Pre-wake: a provenance file that exists but is not JSON throws inside
# ConvertFrom-Json, which reaches the trap before any wake.
$e = $good.Clone()
$r = Invoke-Wrapper -Env $e -Extra @('-ProvenancePath', (Join-Path $stubs 'stub_az.cmd'))
Check 'an unhandled PRE-wake error exits 2 and states nothing was consumed' {
    if ($r.Exit -ne 2) { throw "exit $($r.Exit) (expected 2); output: $($r.Output)" }
    if ($r.Output -notmatch 'No wake had been issued') {
        throw "did not state that nothing was consumed:`n$($r.Output)"
    }
    if ($r.Capture -match '(?m)^curl ') { throw 'a wake was issued before the failure' }
}

# Post-wake: a negative poll interval makes Start-Sleep throw on the first
# not-ready poll, which reaches the trap after the wake has been issued.
$e = $good.Clone(); $e['STUB_REPLICA'] = 'none'
$r = Invoke-Wrapper -Env $e -Extra @('-ReplicaPollSeconds', '-1')
Check 'an unhandled POST-wake error exits 3 and states the wake was consumed' {
    if ($r.Exit -ne 3) { throw "exit $($r.Exit) (expected 3); output: $($r.Output)" }
    if ($r.Output -notmatch 'THE WAKE WAS ISSUED') {
        throw "did not state that the wake was consumed:`n$($r.Output)"
    }
    $n = ([regex]::Matches($r.Capture, '(?m)^curl ')).Count
    if ($n -ne 1) { throw "wake issued $n times" }
    if ($r.Capture -match '(?m)^python .*--mode ') { throw 'the verifier ran after an unhandled error' }
}

Write-Host 'Post-wake child failures stop before the next phase'
$e = $good.Clone(); $e['STUB_POLL_EXIT'] = '3'
$r = Invoke-Wrapper -Env $e
Check 'replica poll failing => exit 3, no verifier' {
    if ($r.Exit -ne 3) { throw "exit $($r.Exit); output: $($r.Output)" }
    if ($r.Capture -match '(?m)^python .*--mode ') { throw 'the verifier ran without a resolved replica' }
}

Write-Host 'The curl stub enforces the authorized argument vector'
# The stub is part of the safety argument: if it answered any argument vector
# with a status, the wrapper tests could not tell an authorized wake from one
# that retried, followed a redirect, or hit another URL. So the stub's own
# enforcement is tested directly, case by case.
$stubCurl = Join-Path $stubs 'stub_curl.cmd'
$authUrl = 'https://api.vibhanshu-ai-portfolio.dev/actuator/health'
$stubCases = @(
    @{ Name = 'the authorized vector';         Ok = $true;  Args = @('-q', '-sS', '-o', 'NUL', '-w', '%{http_code}', $authUrl) },
    @{ Name = 'a retry';                       Ok = $false; Args = @('-q', '-sS', '-o', 'NUL', '-w', '%{http_code}', '--retry', '3', $authUrl) },
    @{ Name = 'a retry on all errors';         Ok = $false; Args = @('-q', '-sS', '-o', 'NUL', '-w', '%{http_code}', '--retry', '2', '--retry-all-errors', $authUrl) },
    @{ Name = 'a redirect follow';             Ok = $false; Args = @('-q', '-sS', '-L', '-o', 'NUL', '-w', '%{http_code}', $authUrl) },
    @{ Name = 'an altered -w';                 Ok = $false; Args = @('-q', '-sS', '-o', 'NUL', '-w', '200', $authUrl) },
    @{ Name = 'an alternate URL';              Ok = $false; Args = @('-q', '-sS', '-o', 'NUL', '-w', '%{http_code}', 'https://api.vibhanshu-ai-portfolio.dev/api/portfolio/holdings') },
    @{ Name = 'a second request in one call';  Ok = $false; Args = @('-q', '-sS', '-o', 'NUL', '-w', '%{http_code}', $authUrl, $authUrl) },
    # -q must be FIRST, not merely present: curl only honours it as the very
    # first argument, so both "no -q at all" and "-q after another flag" must be
    # refused -- otherwise an ambient curlrc could still inject retry/redirect.
    @{ Name = 'no -q at all (ambient config enabled)'; Ok = $false; Args = @('-sS', '-o', 'NUL', '-w', '%{http_code}', $authUrl) },
    @{ Name = '-q present but not first';      Ok = $false; Args = @('-sS', '-q', '-o', 'NUL', '-w', '%{http_code}', $authUrl) }
)
foreach ($sc in $stubCases) {
    $capFile = Join-Path ([IO.Path]::GetTempPath()) "t89-stubcurl-$([guid]::NewGuid()).txt"
    [Environment]::SetEnvironmentVariable('STUB_CAPTURE', $capFile)
    $sout = @(& $stubCurl @($sc.Args) 2>$null)
    $sexit = $LASTEXITCODE
    $scap = if (Test-Path $capFile) { Get-Content $capFile -Raw } else { '' }
    Remove-Item $capFile -ErrorAction SilentlyContinue
    [Environment]::SetEnvironmentVariable('STUB_CAPTURE', $null)
    Check "curl stub: $($sc.Name) is $(if ($sc.Ok) { 'accepted' } else { 'refused with no status' })" {
        if ($sc.Ok) {
            if ($sexit -ne 0 -or ($sout -join '') -ne '200' -or $scap -match 'curl-violation') {
                throw "authorized vector rejected (exit $sexit, out '$($sout -join ',')')"
            }
        } else {
            if ($sexit -ne 99) { throw "exit $sexit, expected 99" }
            if (($sout -join '').Trim()) { throw "printed a status '$($sout -join ',')' for an unauthorized vector" }
            if ($scap -notmatch 'curl-violation') { throw 'no violation was recorded' }
        }
    }
}

Write-Host 'SkipWake refuses every command left at its real default'
# A regression in this guard would let a run continue to REAL tools. So every
# default-value case runs with stub az, docker and python first on PATH and an
# empty Azure config directory. If the guard ever breaks -- including in a
# mutation run -- the name resolves to a stub, and no Azure call can be made.
# curl.exe needs no shadow: -SkipWake never invokes it.
$shadow = Join-Path ([IO.Path]::GetTempPath()) "t89-shadow-$([guid]::NewGuid())"
$azCfg = Join-Path $shadow 'azure-config'
New-Item -ItemType Directory -Path $azCfg -Force | Out-Null
Copy-Item (Join-Path $stubs 'stub_az.cmd') (Join-Path $shadow 'az.cmd')
Copy-Item (Join-Path $stubs 'stub_docker.cmd') (Join-Path $shadow 'docker.cmd')
Copy-Item (Join-Path $stubs 'stub_python.cmd') (Join-Path $shadow 'python.cmd')
foreach ($d in @(
    @{ P = '-AzCommand';     V = 'az' },
    @{ P = '-DockerCommand'; V = 'docker' },
    @{ P = '-CurlCommand';   V = 'curl.exe' },
    @{ P = '-PythonCommand'; V = 'python' }
)) {
    $e = $good.Clone()
    $e['PATH'] = "$shadow;$env:PATH"
    $e['AZURE_CONFIG_DIR'] = $azCfg
    $r = Invoke-Wrapper -Env $e -Extra @('-SkipWake', $d.P, $d.V)
    Check "SkipWake refuses $($d.P) left at its default '$($d.V)', before any child process runs" {
        if ($r.Exit -ne 2) { throw "exit $($r.Exit) (expected 2); output: $($r.Output)" }
        if ($r.Output -notmatch 'offline tests') { throw "did not explain why:`n$($r.Output)" }
        if ($r.Capture -match '(?m)^(az|docker|python|curl) ') { throw "a child process ran before the guard:`n$($r.Capture)" }
    }
}
Remove-Item $shadow -Recurse -Force -ErrorAction SilentlyContinue

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
