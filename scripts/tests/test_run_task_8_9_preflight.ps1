<#
    Offline tests for scripts/run_task_8_9_preflight.ps1.

    Nothing here contacts Azure, the production host, or a registry. Every
    external command is replaced by a .cmd stub that records its argv, so the
    tests assert on exactly what survived the PowerShell -> cmd boundary --
    the fault line that broke three hand-issued commands during the 2026-09-12
    attempt.

    The activation sequence is exercised through a curl stub that plays back a
    per-call script (status, curl exit, metadata, body bytes, content type), so
    a multi-probe cold start -- timeout, 503, 503, 200 -- is reproduced offline
    call by call, and the stub refuses any argument vector but the authorized
    one. No real curl ever reaches the network: almost every wrapper invocation
    below passes the stub outright, and the handful of tests that instead leave
    -CurlCommand at its literal default 'curl.exe' -- to prove a live-invocation
    guard fires before any child process -- use one shared shadow-PATH rig
    that pairs a controlled, already-proven earlier `az` failure with a local
    non-executable curl.exe placeholder. A regressed guard therefore cannot
    reach a real Production client even if the earlier stop also misbehaves.

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
    # -TempDir points the child's TMP/TEMP somewhere else, so the wrapper's
    # generated body files land in a directory the test controls. That is the
    # operating system's own temp resolution, not a wrapper input.
    # -DefaultInterval omits -WakeProbeIntervalSeconds so the production default
    # is what runs; otherwise every run passes 0 to keep the suite fast.
    param([hashtable]$Env = @{}, [string[]]$Extra = @(), [int]$WaitSeconds = 2, [string]$PythonCommand, [string]$TempDir, [switch]$DefaultInterval)
    $capture = Join-Path ([IO.Path]::GetTempPath()) "t89-capture-$([guid]::NewGuid()).txt"
    $evidence = Join-Path ([IO.Path]::GetTempPath()) "t89-evidence-$([guid]::NewGuid()).json"
    $saved = @{}
    $Env['STUB_CAPTURE'] = $capture
    $stateFile = Join-Path ([IO.Path]::GetTempPath()) "t89-state-$([guid]::NewGuid()).txt"
    $Env['STUB_STATE_FILE'] = $stateFile
    if ($TempDir) { $Env['TMP'] = $TempDir; $Env['TEMP'] = $TempDir }
    $bodies = @()
    foreach ($k in $Env.Keys) {
        $saved[$k] = [Environment]::GetEnvironmentVariable($k)
        [Environment]::SetEnvironmentVariable($k, $Env[$k])
    }
    try {
        $argv = @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $script
        )
        # Defaults are omitted when -Extra supplies them, so a test can drive a
        # parameter to a value the helper would otherwise fix. Passing the same
        # parameter twice is a binding error, which would surface as exit 1 and
        # look like a wrapper fault rather than a harness one.
        # Command overrides follow the same rule, so a test can leave one at its
        # real default to prove the SkipWake guard refuses it.
        if ($Extra -notcontains '-EvidenceOutput') { $argv += @('-EvidenceOutput', $evidence) }
        if ($Extra -notcontains '-AzCommand') { $argv += @('-AzCommand', (Join-Path $stubs 'stub_az.cmd')) }
        if ($Extra -notcontains '-DockerCommand') { $argv += @('-DockerCommand', (Join-Path $stubs 'stub_docker.cmd')) }
        if ($Extra -notcontains '-CurlCommand') { $argv += @('-CurlCommand', (Join-Path $stubs 'stub_curl.cmd')) }
        if ($Extra -notcontains '-PythonCommand') { $argv += @('-PythonCommand', $(if ($PythonCommand) { $PythonCommand } else { Join-Path $stubs 'stub_python.cmd' })) }
        if ($Extra -notcontains '-ReplicaWaitSeconds') { $argv += @('-ReplicaWaitSeconds', "$WaitSeconds") }
        if ($Extra -notcontains '-ReplicaPollSeconds') { $argv += @('-ReplicaPollSeconds', '1') }
        if (-not $DefaultInterval -and $Extra -notcontains '-WakeProbeIntervalSeconds') { $argv += @('-WakeProbeIntervalSeconds', '0') }
        $argv += $Extra
        # The wrapper, and so every stub, runs with the repository root as its
        # working directory: the wrapper's relative default paths need it, and
        # it holds files, so a stub that globbed * would record file names
        # (Assert-ProbesSound checks what arrived after --noproxy).
        Push-Location -LiteralPath $repo
        try {
            $out = & powershell @argv 2>&1 | Out-String
            $code = $LASTEXITCODE
        } finally { Pop-Location }
        $cap = if (Test-Path $capture) { Get-Content $capture -Raw } else { '' }
        # One entry per probe the stub received, in order: its argv after 'curl '.
        $probes = @([regex]::Matches($cap, '(?m)^curl (.*)$') | ForEach-Object { $_.Groups[1].Value.TrimEnd() })
        # The body file each probe was told to write, and which of them still
        # exist now that the wrapper has exited. Checked before this helper's own
        # cleanup below removes them.
        $bodies = @($probes | ForEach-Object { if ($_ -match '(?:^| )-o (\S+)') { $Matches[1] } })
        $left = @($bodies | Where-Object { Test-Path -LiteralPath $_ })
        return [pscustomobject]@{ Exit = $code; Capture = $cap; Output = ($out -join "`n"); Probes = $probes; Bodies = $bodies; Leftovers = $left }
    } finally {
        foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k]) }
        Remove-Item $capture, $evidence, $stateFile, "$stateFile.curl" -ErrorAction SilentlyContinue
        foreach ($b in $bodies) { Remove-Item -LiteralPath $b -Force -ErrorAction SilentlyContinue }
    }
}

# --- Helpers for the activation sequence ------------------------------------

$script:fixtureFiles = @()
function Utf8 { param([string]$Text) , ([Text.UTF8Encoding]::new($false).GetBytes($Text)) }
function Sha256Hex { param([byte[]]$Bytes) (Get-FileHash -InputStream ([IO.MemoryStream]::new($Bytes)) -Algorithm SHA256).Hash.ToLowerInvariant() }
function New-BodyFixture {
    # Exact response bytes for the stub to copy into the wrapper's body file.
    param([byte[]]$Bytes)
    $f = Join-Path ([IO.Path]::GetTempPath()) "t89-fixture-$([guid]::NewGuid().ToString('N')).bin"
    [IO.File]::WriteAllBytes($f, $Bytes)
    $script:fixtureFiles += $f
    return $f
}
function Get-Fingerprints {
    # Parsed fingerprint lines, in output order. 'Rest' is everything after the
    # timestamp, so a test can compare it exactly.
    param($R)
    $pattern = '^==>   probe (?<n>[1-6])/6 started-utc=(?<utc>[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{7}Z) (?<rest>curl-exit=.*)$'
    @($R.Output -split "`r?`n" | ForEach-Object {
        $m = [regex]::Match($_, $pattern)
        if ($m.Success) {
            [pscustomobject]@{
                N    = [int]$m.Groups['n'].Value
                Utc  = [DateTime]::Parse($m.Groups['utc'].Value, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind)
                Rest = $m.Groups['rest'].Value
            }
        }
    })
}
function Assert-NoVerifier { param($R) if ($R.Capture -match '(?m)^python .*--mode ') { throw "the verifier ran; output:`n$($R.Output)" } }
function Assert-NoReplicaWait { param($R) if ($R.Capture -match 'replica list') { throw "the replica wait ran; output:`n$($R.Output)" } }
function Assert-ProbeCount {
    param($R, [int]$N)
    if ($R.Probes.Count -ne $N) { throw "expected exactly $N probe(s), the stub received $($R.Probes.Count); output:`n$($R.Output)" }
}
function Assert-ProbesSound {
    # Every probe used exactly the authorized vector with a fresh body path, no
    # probe went past the budget or consumed a never-to-be-issued call, and
    # every body file is gone.
    param($R)
    $vector = '^-q --noproxy \* -sS -o (?<body>\S+) -w t89:%\{http_code\}:%\{time_total\}:%\{content_type\} --max-time 90 https://api\.vibhanshu-ai-portfolio\.dev/actuator/health$'
    foreach ($p in $R.Probes) { if ($p -cnotmatch $vector) { throw "a probe used an unauthorized vector: curl $p" } }
    # What the stub actually received in positions 2, 3 and 4, read
    # positionally (not from %*): exactly --noproxy, one literal *, then -sS.
    # The runs start in a directory holding files, so an argument walk that
    # globbed * would show file names here, or shift -sS out of position 4.
    $argLines = @([regex]::Matches($R.Capture, '(?m)^curl-noproxy-arg .*$') | ForEach-Object { $_.Value.TrimEnd() })
    if ($argLines.Count -ne $R.Probes.Count) { throw "expected one curl-noproxy-arg capture line per probe ($($R.Probes.Count)), found $($argLines.Count)" }
    foreach ($l in $argLines) {
        if ($l -cne 'curl-noproxy-arg [--noproxy] [*] [-sS]') { throw "the stub did not receive exactly one literal * after --noproxy: $l" }
    }
    if ($R.Capture -match '(?m)^curl-violation') { throw 'the curl stub recorded an argument-vector violation' }
    if ($R.Capture -match '(?m)^curl-over-budget') { throw 'a probe beyond the six-request budget was issued' }
    if ($R.Capture -match '(?m)^curl-sentinel-consumed') { throw 'a probe the test marked as never-to-be-issued was issued' }
    if (@($R.Bodies | Sort-Object -Unique).Count -ne $R.Bodies.Count) { throw 'a body file path was reused across probes' }
    if ($R.Leftovers.Count) { throw "$($R.Leftovers.Count) temporary body file(s) were left behind" }
}
function Probe-Env {
    # @(@{STATUS='503'}, @{EXIT='28'; STATUS='000'}) -> STUB_CURL_1_STATUS=503, ...
    param([hashtable]$Base, [object[]]$Calls)
    $e = $Base.Clone()
    for ($i = 0; $i -lt $Calls.Count; $i++) {
        foreach ($k in $Calls[$i].Keys) { $e["STUB_CURL_$($i + 1)_$k"] = $Calls[$i][$k] }
    }
    return $e
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

function New-ShadowPathRig {
    # Return an isolated PATH prefix containing every external command name the
    # wrapper can invoke. curl.exe is deliberately an invalid executable: it is
    # the final network-safety net if a guard regression also bypasses the
    # controlled first-az failure used by the live-configuration tests.
    param([string]$Tag)
    $root = Join-Path ([IO.Path]::GetTempPath()) "t89-shadow-$Tag-$([guid]::NewGuid())"
    $azureConfig = Join-Path $root 'azure-config'
    New-Item -ItemType Directory -Path $azureConfig -Force | Out-Null
    Copy-Item (Join-Path $stubs 'stub_az.cmd') (Join-Path $root 'az.cmd')
    Copy-Item (Join-Path $stubs 'stub_docker.cmd') (Join-Path $root 'docker.cmd')
    Copy-Item (Join-Path $stubs 'stub_python.cmd') (Join-Path $root 'python.cmd')
    Set-Content -LiteralPath (Join-Path $root 'curl.exe') -Value 'not an executable; network-safety placeholder' -Encoding ASCII
    [pscustomobject]@{ Root = $root; AzureConfig = $azureConfig }
}

function New-ShadowEnvironment {
    param($Rig)
    $e = $good.Clone()
    $e['PATH'] = "$($Rig.Root);$env:PATH"
    $e['AZURE_CONFIG_DIR'] = $Rig.AzureConfig
    $e['STUB_AZ_FAIL_MATCH'] = '--query name'
    return $e
}

function Invoke-ShadowedPotentiallyLiveCase {
    # Keep literal-default command values so the wrapper applies its live
    # guards, while the shared PATH rig resolves every name locally. -Extra
    # may override a command deliberately; unspecified commands retain their
    # literal defaults without creating duplicate parameter bindings.
    param($Rig, [string[]]$Extra = @())
    $argv = @()
    foreach ($pair in @(
        @{ P = '-AzCommand'; V = 'az' },
        @{ P = '-DockerCommand'; V = 'docker' },
        @{ P = '-CurlCommand'; V = 'curl.exe' },
        @{ P = '-PythonCommand'; V = 'python' }
    )) {
        if ($Extra -notcontains $pair.P) { $argv += @($pair.P, $pair.V) }
    }
    $argv += $Extra
    Invoke-Wrapper -Env (New-ShadowEnvironment $Rig) -Extra $argv
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

Check 'wrapper runs start in a directory that holds files, so a globbing stub would be visible' {
    # Invoke-Wrapper runs from $repo. If it held no files, a stub that expanded
    # * against the working directory would still see a bare *, and the
    # curl-noproxy-arg assertions could not tell it apart from a correct one.
    if (@(Get-ChildItem -LiteralPath $repo -File -Force).Count -lt 1) { throw "the wrapper's working directory $repo holds no files" }
}

Write-Host 'Happy path'
$r = Invoke-Wrapper -Env $good.Clone()
Check 'exits 0' { if ($r.Exit -ne 0) { throw "exit $($r.Exit); output: $($r.Output)" } }
Check 'a first-probe 200 uses exactly the authorized argument vector, once, with no stub violation' {
    # Counting curl lines was not enough: --retry makes several HTTP requests
    # from ONE invocation, and -L follows a redirect. Only the full vector says
    # which request was actually made. Only the body path may vary.
    Assert-ProbeCount $r 1
    $want = '^curl -q --noproxy \* -sS -o ' + [regex]::Escape([IO.Path]::GetTempPath()) + 't89-wake-[0-9a-f]{32}\.body -w t89:%\{http_code\}:%\{time_total\}:%\{content_type\} --max-time 90 https://api\.vibhanshu-ai-portfolio\.dev/actuator/health$'
    $line = "curl $($r.Probes[0])"
    if ($line -cnotmatch $want) { throw "curl was called as:`n  $line`nexpected to match:`n  $want" }
    Assert-ProbesSound $r
}
Check 'a first-probe 200 goes on to the replica wait and exactly one verifier run' {
    $cap = $r.Capture
    $lastProbe = $cap.LastIndexOf('curl-call ')
    $firstPoll = $cap.IndexOf('replica list')
    if ($lastProbe -lt 0 -or $firstPoll -lt 0 -or $firstPoll -lt $lastProbe) { throw "the replica wait did not follow the probe:`n$cap" }
    $n = ([regex]::Matches($cap, '(?m)^python .*--mode ')).Count
    if ($n -ne 1) { throw "verifier ran $n times" }
    if ($r.Output -notmatch 'probe 1/6 returned HTTP 200: activation confirmed') { throw "activation not announced:`n$($r.Output)" }
}
Check 'a first-probe 200 prints one fingerprint with only allowlisted fields' {
    $fp = @(Get-Fingerprints $r)
    if ($fp.Count -ne 1 -or $fp[0].N -ne 1) { throw "expected one fingerprint for probe 1; output:`n$($r.Output)" }
    $body = Utf8 ('{"status":"UP"}' + "`r`n")
    $want = "curl-exit=0 http=200 duration-s=0.012345 content-type=application/json body-bytes=$($body.Length) body-sha256=$(Sha256Hex $body) body-class=actuator-json actuator-status=UP"
    if ($fp[0].Rest -cne $want) { throw "fingerprint is:`n  $($fp[0].Rest)`nexpected:`n  $want" }
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
Check 'verifier is invoked with the ratified Azure timeout arguments' {
    # Pins the four --eligibility-timeout/--reset-timeout/--overall-timeout/
    # --login-timeout-seconds literals in the actual invocation, not just
    # somewhere in the .ps1 text -- a Python regex over the file cannot tell
    # $verifierArgs apart from a comment quoting the same values.
    $want = '--eligibility-timeout 120s --reset-timeout 30s --overall-timeout 165s --login-timeout-seconds 225'
    if ($r.Capture -notmatch [regex]::Escape($want)) {
        throw "Azure timeout arguments missing, reordered, or altered:`n$($r.Capture)"
    }
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
        if ($m.Value -match 'TASK8_9_ACCESS_TOKEN|TASK8_9_DEMO_PASSWORD|WAVE9_STEP_A_PASSWORD') { throw "credential name in argv: $($m.Value)" }
    }
}
Check 'task credentials are scrubbed from every child process environment' {
    $e2 = $good.Clone()
    $e2['TASK8_9_ACCESS_TOKEN'] = 'sentinel-token-value'
    $e2['TASK8_9_DEMO_PASSWORD'] = 'sentinel-password-value'
    $e2['WAVE9_STEP_A_PASSWORD'] = 'sentinel-step-a-password'
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
Check 'a first-probe 200 issues no further probe and leaves no body file behind' {
    $n = ([regex]::Matches($r.Capture, '(?m)^curl ')).Count
    if ($n -ne 1) { throw "probes issued $n times" }
    if ($r.Bodies.Count -ne 1) { throw "expected one body path, found $($r.Bodies.Count)" }
    if ($r.Leftovers.Count) { throw 'the first-200 body file was left behind' }
}
Check 'the subscription id is not printed in output' {
    if ($r.Output -match 'ee625b3f') { throw 'subscription id was printed' }
}

# --- The activation sequence --------------------------------------------------
# The gateway's recorded cold start (runbook, and both Run A attempts) is a
# timeout and/or 503s before a 200. The sequence may issue at most six probes;
# only an exact 503 (curl exit 0) or a curl timeout (exit 28, status 000) leads
# to another probe, and the first exact 200 ends it. Each case asserts the
# probe count from the stub's own record, not from the wrapper's output.

Write-Host 'Activation: 503, 503, 200'
$r = Invoke-Wrapper -Env (Probe-Env $good @(@{ STATUS = '503' }, @{ STATUS = '503' }, @{ STATUS = '200' }, @{ SENTINEL = '1' }))
Check '503, 503, 200 => exactly three probes, then the replica wait and one verifier run' {
    if ($r.Exit -ne 0) { throw "exit $($r.Exit); output: $($r.Output)" }
    Assert-ProbeCount $r 3
    Assert-ProbesSound $r
    $cap = $r.Capture
    if ($cap.IndexOf('replica list') -lt $cap.LastIndexOf('curl-call ')) { throw "the replica wait started before the sequence ended:`n$cap" }
    $n = ([regex]::Matches($cap, '(?m)^python .*--mode ')).Count
    if ($n -ne 1) { throw "verifier ran $n times" }
}
Check '503, 503, 200 => one fingerprint per probe, in order, and activation on probe 3' {
    $fp = @(Get-Fingerprints $r)
    if ((($fp | ForEach-Object { $_.N }) -join ',') -ne '1,2,3') { throw "fingerprints for probes: $(($fp | ForEach-Object { $_.N }) -join ','); output:`n$($r.Output)" }
    foreach ($i in 0, 1) { if ($fp[$i].Rest -notmatch '^curl-exit=0 http=503 ') { throw "probe $($i + 1) fingerprint: $($fp[$i].Rest)" } }
    if ($fp[2].Rest -notmatch '^curl-exit=0 http=200 ') { throw "probe 3 fingerprint: $($fp[2].Rest)" }
    if ($r.Output -notmatch 'probe 3/6 returned HTTP 200: activation confirmed') { throw "activation not announced on probe 3:`n$($r.Output)" }
    # Tests pass -WakeProbeIntervalSeconds 0: two intervals of the production 5s
    # would put at least 10s between probe 1 and probe 3.
    if (($fp[2].Utc - $fp[0].Utc).TotalSeconds -ge 8) { throw "the interval did not honour 0: $(($fp[2].Utc - $fp[0].Utc).TotalSeconds)s between probes 1 and 3" }
}

Write-Host 'Activation: curl timeout, 503, 200'
$r = Invoke-Wrapper -Env (Probe-Env $good @(@{ EXIT = '28'; STATUS = '000'; BODY = 'none'; CT = 'none' }, @{ STATUS = '503' }, @{ STATUS = '200' }, @{ SENTINEL = '1' }))
Check 'timeout (28/000), 503, 200 => exactly three probes, then the success path' {
    if ($r.Exit -ne 0) { throw "exit $($r.Exit); output: $($r.Output)" }
    Assert-ProbeCount $r 3
    Assert-ProbesSound $r
    $n = ([regex]::Matches($r.Capture, '(?m)^python .*--mode ')).Count
    if ($n -ne 1) { throw "verifier ran $n times" }
    $fp = @(Get-Fingerprints $r)
    if ($fp.Count -ne 3 -or $fp[0].Rest -notmatch '^curl-exit=28 http=000 ') { throw "probe 1 was not recorded as a timeout; output:`n$($r.Output)" }
}

Write-Host 'Activation: six 503s exhaust the budget'
$six503 = @(1..6 | ForEach-Object { @{ STATUS = '503' } }) + @(@{ STATUS = '200'; SENTINEL = '1' })
$r = Invoke-Wrapper -Env (Probe-Env $good $six503)
Check 'six 503s => exit 3 after exactly six probes, no replica wait, no verifier' {
    # Call 7 would answer 200: a seventh probe would reach the verifier, and the
    # stub records it as over budget and as a consumed sentinel.
    if ($r.Exit -ne 3) { throw "exit $($r.Exit) (expected 3); output: $($r.Output)" }
    Assert-ProbeCount $r 6
    Assert-ProbesSound $r
    Assert-NoReplicaWait $r
    Assert-NoVerifier $r
}
Check 'exhaustion says the budget is spent and offers no override' {
    if ($r.Output -notmatch '6 of 6 activation probes') { throw "exhaustion not reported:`n$($r.Output)" }
    if ($r.Output -notmatch 'no override') { throw "did not state that there is no override:`n$($r.Output)" }
    if ($r.Output -match 'FAIL \(unhandled\)') { throw 'reached exit 3 through the trap, not the sequence' }
    if (@(Get-Fingerprints $r).Count -ne 6) { throw 'expected six fingerprints' }
}

Write-Host 'Activation: six curl timeouts exhaust the budget'
$sixTimeouts = @(1..6 | ForEach-Object { @{ EXIT = '28'; STATUS = '000'; BODY = 'none'; CT = 'none' } }) + @(@{ STATUS = '200'; SENTINEL = '1' })
$r = Invoke-Wrapper -Env (Probe-Env $good $sixTimeouts)
Check 'six timeouts => exit 3 after exactly six probes, no replica wait, no verifier' {
    if ($r.Exit -ne 3) { throw "exit $($r.Exit) (expected 3); output: $($r.Output)" }
    Assert-ProbeCount $r 6
    Assert-ProbesSound $r
    Assert-NoReplicaWait $r
    Assert-NoVerifier $r
}

Write-Host 'Activation: mixed timeouts and 503s exhaust the budget'
$mixed = @(@{ STATUS = '503' }, @{ EXIT = '28'; STATUS = '000' }, @{ STATUS = '503' }, @{ EXIT = '28'; STATUS = '000' }, @{ STATUS = '503' }, @{ EXIT = '28'; STATUS = '000' }, @{ STATUS = '200'; SENTINEL = '1' })
$r = Invoke-Wrapper -Env (Probe-Env $good $mixed)
Check '503, timeout, 503, timeout, 503, timeout => exit 3 after exactly six probes, no verifier' {
    if ($r.Exit -ne 3) { throw "exit $($r.Exit) (expected 3); output: $($r.Output)" }
    Assert-ProbeCount $r 6
    Assert-ProbesSound $r
    Assert-NoReplicaWait $r
    Assert-NoVerifier $r
}

Write-Host 'Activation: the first 200 ends the sequence'
$r = Invoke-Wrapper -Env (Probe-Env $good @(@{ STATUS = '503' }, @{ STATUS = '200' }, @{ STATUS = '200'; SENTINEL = '1' }))
Check '503, 200, <sentinel> => exactly two probes; the sentinel is never consumed' {
    if ($r.Exit -ne 0) { throw "exit $($r.Exit); output: $($r.Output)" }
    Assert-ProbeCount $r 2
    Assert-ProbesSound $r
}

Write-Host 'Activation: the production interval is five seconds'
$r = Invoke-Wrapper -Env (Probe-Env $good @(@{ STATUS = '503' }, @{ STATUS = '200' })) -DefaultInterval
Check 'with -WakeProbeIntervalSeconds omitted, the second probe starts at least 5s after the first' {
    if ($r.Exit -ne 0) { throw "exit $($r.Exit); output: $($r.Output)" }
    Assert-ProbeCount $r 2
    $fp = @(Get-Fingerprints $r)
    if ($fp.Count -ne 2) { throw "expected two fingerprints; output:`n$($r.Output)" }
    $gap = ($fp[1].Utc - $fp[0].Utc).TotalSeconds
    if ($gap -lt 5) { throw "only $gap s between probe 1 and probe 2" }
}
# --- Only the recorded cold-start outcomes may lead to another probe ----------
# One 503 case is not a pin. Review demonstrated bypasses that keep the suite
# fully green while a non-200 proceeds: widening the comparison
# (-notin @('200','502')), pattern-matching the status (-like '5*'), or gating
# on something other than a declared parameter. This sweeps every other class
# on probe 1 -- HTTP statuses, transport failures, and metadata the wrapper
# must refuse rather than repair -- and asserts, for each: exit 3, exactly one
# probe, no replica wait, no verifier, no body file left. Call 2 is marked
# never-to-be-issued and would answer 200, so a wrapper that probed again
# would visibly reach the verifier.
$ok = '0.012345:application/json'
$nonRetryable = @(
    @{ Name = '000 with curl exit 0';           Why = 'no override';           Call = @{ STATUS = '000' } },
    @{ Name = '100 informational';              Why = 'no override';           Call = @{ STATUS = '100' } },
    @{ Name = '201 non-200 success';            Why = 'no override';           Call = @{ STATUS = '201' } },
    @{ Name = '204 non-200 success';            Why = 'no override';           Call = @{ STATUS = '204' } },
    @{ Name = '301 redirect';                   Why = 'no override';           Call = @{ STATUS = '301' } },
    @{ Name = '302 redirect';                   Why = 'no override';           Call = @{ STATUS = '302' } },
    @{ Name = '307 temporary redirect';         Why = 'no override';           Call = @{ STATUS = '307' } },
    @{ Name = '308 permanent redirect';         Why = 'no override';           Call = @{ STATUS = '308' } },
    @{ Name = '400 bad request';                Why = 'no override';           Call = @{ STATUS = '400' } },
    @{ Name = '401 unauthorized';               Why = 'no override';           Call = @{ STATUS = '401' } },
    @{ Name = '403 forbidden';                  Why = 'no override';           Call = @{ STATUS = '403' } },
    @{ Name = '404 not found';                  Why = 'no override';           Call = @{ STATUS = '404' } },
    @{ Name = '405 method not allowed';         Why = 'no override';           Call = @{ STATUS = '405' } },
    @{ Name = '407 proxy auth required';        Why = 'no override';           Call = @{ STATUS = '407' } },
    @{ Name = '408 request timeout';            Why = 'no override';           Call = @{ STATUS = '408' } },
    @{ Name = '429 throttled';                  Why = 'no override';           Call = @{ STATUS = '429' } },
    @{ Name = '500 server error';               Why = 'no override';           Call = @{ STATUS = '500' } },
    @{ Name = '501 not implemented';            Why = 'no override';           Call = @{ STATUS = '501' } },
    @{ Name = '502 bad gateway';                Why = 'no override';           Call = @{ STATUS = '502' } },
    @{ Name = '504 gateway timeout';            Why = 'no override';           Call = @{ STATUS = '504' } },
    @{ Name = '505 http version not supported'; Why = 'no override';           Call = @{ STATUS = '505' } },
    # 6/7/35 are hedged: a TLS-handshake failure (35) happens after a TCP
    # connection to the ingress's TLS terminator, so "did not reach" alone
    # would overstate it.
    @{ Name = 'curl 6 DNS failure';             Why = 'probably did not reach the ingress as an HTTP request and was probably NOT consumed'; Call = @{ EXIT = '6'; STATUS = '000'; BODY = 'none'; CT = 'none' } },
    @{ Name = 'curl 7 connect failure';         Why = 'probably did not reach the ingress as an HTTP request and was probably NOT consumed'; Call = @{ EXIT = '7'; STATUS = '000'; BODY = 'none'; CT = 'none' } },
    @{ Name = 'curl 35 TLS handshake failure';  Why = 'probably did not reach the ingress as an HTTP request and was probably NOT consumed'; Call = @{ EXIT = '35'; STATUS = '000'; BODY = 'none'; CT = 'none' } },
    @{ Name = 'curl 60 certificate failure';    Why = 'may be consumed';       Call = @{ EXIT = '60'; STATUS = '000'; BODY = 'none'; CT = 'none' } },
    @{ Name = 'curl 52 empty reply';            Why = 'may be consumed';       Call = @{ EXIT = '52'; STATUS = '000'; BODY = 'none'; CT = 'none' } },
    @{ Name = 'curl 56 receive failure';        Why = 'may be consumed';       Call = @{ EXIT = '56'; STATUS = '000' } },
    @{ Name = 'curl 28 after a 200 status';     Why = 'may be consumed';       Call = @{ EXIT = '28'; STATUS = '200' } },
    @{ Name = 'curl 28 after a 503 status';     Why = 'may be consumed';       Call = @{ EXIT = '28'; STATUS = '503' } },
    @{ Name = 'curl 6 with no metadata';        Why = 'probably NOT consumed'; Call = @{ EXIT = '6'; META = 'none'; BODY = 'none' } },
    @{ Name = 'curl 28 with no metadata';       Why = 'may be consumed';       Call = @{ EXIT = '28'; META = 'none'; BODY = 'none' } },
    @{ Name = 'empty metadata';                 Why = 'well-formed';           Call = @{ META = 'none' } },
    @{ Name = 'garbage metadata';               Why = 'well-formed';           Call = @{ META = 'garbage' } },
    @{ Name = 'bare status (retired format)';   Why = 'well-formed';           Call = @{ META = '200' } },
    @{ Name = 'metadata missing fields';        Why = 'well-formed';           Call = @{ META = 't89:200' } },
    @{ Name = 'non-digit status';               Why = 'well-formed';           Call = @{ META = "t89:2OO:$ok" } },
    @{ Name = 'out-of-domain status 999';       Why = 'well-formed';           Call = @{ META = "t89:999:$ok" } },
    @{ Name = 'out-of-domain status 099';       Why = 'well-formed';           Call = @{ META = "t89:099:$ok" } },
    @{ Name = 'redirect-shaped doubled status'; Why = 'well-formed';           Call = @{ META = "t89:301200:$ok" } },
    @{ Name = 'comma decimal time';             Why = 'well-formed';           Call = @{ META = 't89:200:0,012345:application/json' } },
    @{ Name = 'empty time';                     Why = 'well-formed';           Call = @{ META = 't89:200::application/json' } },
    @{ Name = 'wrong record prefix';            Why = 'well-formed';           Call = @{ META = "T89:200:$ok" } },
    @{ Name = 'two identical 200 records';      Why = 'well-formed';           Call = @{ META = "t89:200:$ok"; META2 = "t89:200:$ok" } },
    @{ Name = 'redirect then 200 records';      Why = 'well-formed';           Call = @{ META = 't89:301:0.012345:text/html'; META2 = "t89:200:$ok" } },
    @{ Name = '503 then 200 records';           Why = 'well-formed';           Call = @{ META = "t89:503:$ok"; META2 = "t89:200:$ok" } }
)
Write-Host 'Everything but a 200, a 503 or a curl timeout stops after one probe'
foreach ($case in $nonRetryable) {
    $r = Invoke-Wrapper -Env (Probe-Env $good @($case.Call, @{ STATUS = '200'; SENTINEL = '1' }))
    Check "$($case.Name) => exit 3, exactly one probe, no replica wait, no verifier" {
        if ($r.Exit -ne 3) { throw "exit $($r.Exit) (expected 3); output: $($r.Output)" }
        Assert-ProbeCount $r 1
        Assert-ProbesSound $r
        Assert-NoReplicaWait $r
        Assert-NoVerifier $r
        if ($r.Output -notmatch [regex]::Escape($case.Why)) { throw "the stop message did not say '$($case.Why)':`n$($r.Output)" }
        if ($r.Output -match '(?<!probably )(did not|never) reach(ed)? the ingress') { throw "a stop message states unhedged that the request did not reach the ingress:`n$($r.Output)" }
        if ($r.Output -match 'FAIL \(unhandled\)') { throw 'reached exit 3 through the trap, not the classified stop' }
        if (@(Get-Fingerprints $r).Count -ne 1) { throw "expected one fingerprint; output:`n$($r.Output)" }
    }
}

Write-Host 'A non-retryable outcome later in the sequence stops at once'
$r = Invoke-Wrapper -Env (Probe-Env $good @(@{ STATUS = '503' }, @{ STATUS = '404' }, @{ STATUS = '200'; SENTINEL = '1' }))
Check '503 then 404 => exit 3 after exactly two probes, naming the probe and the earlier one' {
    if ($r.Exit -ne 3) { throw "exit $($r.Exit) (expected 3); output: $($r.Output)" }
    Assert-ProbeCount $r 2
    Assert-ProbesSound $r
    Assert-NoReplicaWait $r
    Assert-NoVerifier $r
    if ($r.Output -notmatch 'probe 2/6 returned HTTP 404') { throw "the stop did not name probe 2:`n$($r.Output)" }
    if ($r.Output -notmatch 'The 1 earlier probe') { throw "the stop did not state that the earlier probe was issued:`n$($r.Output)" }
}
$r = Invoke-Wrapper -Env (Probe-Env $good @(@{ EXIT = '28'; STATUS = '000' }, @{ EXIT = '6'; STATUS = '000'; BODY = 'none' }, @{ STATUS = '200'; SENTINEL = '1' }))
Check 'timeout then DNS failure => exit 3 after two probes; this probe probably unconsumed, the earlier one issued' {
    if ($r.Exit -ne 3) { throw "exit $($r.Exit) (expected 3); output: $($r.Output)" }
    Assert-ProbeCount $r 2
    Assert-ProbesSound $r
    Assert-NoVerifier $r
    if ($r.Output -notmatch 'probe 2/6 failed in transport \(curl exit 6\)') { throw "the stop did not name probe 2:`n$($r.Output)" }
    if ($r.Output -notmatch 'probably NOT consumed') { throw "did not flag this probe as probably unconsumed:`n$($r.Output)" }
    if ($r.Output -notmatch 'The 1 earlier probe') { throw "did not state that the earlier probe was issued:`n$($r.Output)" }
}

# --- The per-probe fingerprint --------------------------------------------------
# Each probe prints one bounded line of allowlisted metadata: enough to tell an
# actuator-shaped response from anything else, never the response itself. Each
# run below plays five probes (four retryable, then a 200) with a different
# body or content type per probe, and compares every fingerprint EXACTLY, with
# the SHA-256 computed here from the fixture bytes.
function New-FingerprintCase {
    param([string]$Status = '503', [string]$Exit = '0', [string]$Time = '0.012345', [string]$Ct = 'application/json', $Body, [switch]$NoBody,
          [string]$Label, [string]$Class, [string]$Token, [switch]$Stderr)
    # Assigned in each branch, not through an if-expression, which would unroll
    # the array (and turn an empty one into $null).
    if ($NoBody) { $bytes = [byte[]]@() } elseif ($Body -is [byte[]]) { $bytes = $Body } else { $bytes = Utf8 $Body }
    $call = @{ STATUS = $Status; EXIT = $Exit; TIME = $Time; CT = $Ct }
    if ($NoBody) { $call['BODY'] = 'none' } else { $call['BODY'] = New-BodyFixture $bytes }
    if ($Stderr) { $call['STDERR'] = '1' }
    $rest = "curl-exit=$Exit http=$Status duration-s=$Time content-type=$Label body-bytes=$($bytes.Length) body-sha256=$(Sha256Hex $bytes) body-class=$Class"
    if ($Token) { $rest += " actuator-status=$Token" }
    [pscustomobject]@{ Call = $call; Rest = $rest }
}
$fingerprintRuns = @(
    @{ Name = 'actuator shapes, HTML, a timeout, a lowercase status'; Cases = @(
        (New-FingerprintCase -Ct 'application/vnd.spring-boot.actuator.v3+json' -Body '{"status":"DOWN"}' -Time '56.374000' -Label 'application/*+json' -Class 'actuator-json' -Token 'DOWN'),
        (New-FingerprintCase -Ct 'text/html; charset=utf-8' -Body '<html><body>upstream connect error SENTINEL-BODY-HTML-5f1c</body></html>' -Label 'text/html' -Class 'unclassified'),
        (New-FingerprintCase -Exit '28' -Status '000' -Ct 'none' -NoBody -Time '30.001234' -Label 'unknown' -Class 'unclassified'),
        (New-FingerprintCase -Ct 'TEXT/PLAIN' -Body '{"status":"down-SENTINEL-LOWER-9a7e"}' -Label 'text/plain' -Class 'actuator-json' -Token 'other' -Stderr),
        (New-FingerprintCase -Status '200' -Ct 'application/json;charset=UTF-8' -Body '{"status":"UP","components":{"db":{"status":"UP","details":{"token":"SENTINEL-NESTED-3b2d"}}}}' -Label 'application/json' -Class 'actuator-json' -Token 'UP')
    ) },
    @{ Name = 'null, array, object and number statuses; a case-variant key'; Cases = @(
        (New-FingerprintCase -Ct 'application/xml' -Body '{"status":null}' -Label 'other' -Class 'unclassified'),
        (New-FingerprintCase -Ct 'application/problem+json' -Body '[{"status":"UP"}]' -Label 'application/*+json' -Class 'unclassified'),
        (New-FingerprintCase -Ct 'text/plain' -Body '{"status":{"code":"UP"}}' -Label 'text/plain' -Class 'unclassified'),
        (New-FingerprintCase -Ct 'application/json' -Body '{"status":42}' -Label 'application/json' -Class 'actuator-json' -Token 'other'),
        (New-FingerprintCase -Status '200' -Ct 'none' -Body '{"Status":"UP"}' -Label 'unknown' -Class 'unclassified')
    ) },
    @{ Name = 'trailing newline, oversize token, boolean, non-JSON, invalid UTF-8'; Cases = @(
        (New-FingerprintCase -Body '{"status":"DOWN\n"}' -Label 'application/json' -Class 'actuator-json' -Token 'other'),
        (New-FingerprintCase -Body '{"status":"ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456"}' -Label 'application/json' -Class 'actuator-json' -Token 'other'),
        (New-FingerprintCase -Body '{"status":true}' -Label 'application/json' -Class 'actuator-json' -Token 'other'),
        (New-FingerprintCase -Ct 'application/vnd.sentinel-ct-77d0+json' -Body 'not json SENTINEL-NOTJSON-c41e {' -Label 'application/*+json' -Class 'unclassified'),
        (New-FingerprintCase -Status '200' -Ct 'application/sentinel-ct-other-e19b' -Body ([byte[]]@(0xFF, 0xFE, 0x53, 0x45, 0x4E)) -Label 'other' -Class 'unclassified')
    ) },
    @{ Name = 'an empty body file and a 32-character token'; Cases = @(
        (New-FingerprintCase -Body ([byte[]]@()) -Label 'application/json' -Class 'unclassified'),
        (New-FingerprintCase -Status '200' -Body '{"status":"ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"}' -Label 'application/json' -Class 'actuator-json' -Token 'ABCDEFGHIJKLMNOPQRSTUVWXYZ012345')
    ) }
)
Write-Host 'Each probe prints an exact, allowlisted fingerprint'
foreach ($run in $fingerprintRuns) {
    $e = Probe-Env $good @($run.Cases | ForEach-Object { $_.Call })
    $e['TASK8_9_ACCESS_TOKEN'] = 'sentinel-token-value'
    $e['TASK8_9_DEMO_PASSWORD'] = 'sentinel-password-value'
    $r = Invoke-Wrapper -Env $e
    Check "fingerprints: $($run.Name)" {
        if ($r.Exit -ne 0) { throw "exit $($r.Exit); output: $($r.Output)" }
        Assert-ProbeCount $r $run.Cases.Count
        Assert-ProbesSound $r
        $fp = @(Get-Fingerprints $r)
        if ($fp.Count -ne $run.Cases.Count) { throw "expected $($run.Cases.Count) fingerprints, found $($fp.Count); output:`n$($r.Output)" }
        for ($i = 0; $i -lt $fp.Count; $i++) {
            if ($fp[$i].N -ne $i + 1) { throw "fingerprint $i is for probe $($fp[$i].N)" }
            if ($fp[$i].Rest -cne $run.Cases[$i].Rest) { throw "probe $($i + 1) fingerprint is:`n  $($fp[$i].Rest)`nexpected:`n  $($run.Cases[$i].Rest)" }
        }
    }
    Check "non-disclosure: $($run.Name)" {
        # Nothing from a response may reach the transcript except the allowlisted
        # labels: no body text, no raw content type, no temporary path (curl's
        # own stderr names it), no credential.
        if ($r.Probes.Count -ne $run.Cases.Count -or @(Get-Fingerprints $r).Count -ne $run.Cases.Count) { throw 'the run did not issue and fingerprint every probe, so non-disclosure is unproven' }
        foreach ($t in @($r.Output, $r.Capture)) {
            if ($t -match '(?i)sentinel-(body|lower|nested|notjson|ct|token-value|password-value)') { throw "a sentinel reached the output or capture: $($Matches[0])" }
        }
        if ($r.Output -match '(?i)charset|vnd\.|problem\+json|application/xml|stub-injected|t89-wake-') { throw "raw response metadata or a temp path reached the output: $($Matches[0])" }
        foreach ($b in $r.Bodies) { if ($r.Output.Contains($b)) { throw 'a body file path reached the output' } }
        if ($r.Output -match '\{"|"status"') { throw 'response JSON reached the output' }
    }
}

# --- Temporary body files ---------------------------------------------------------
# Removal on a first-200, on exhaustion and on an immediate stop is asserted by
# Assert-ProbesSound in the runs above. The two cases below need a directory the
# test controls, so each points the wrapper's TMP at one whose ACL injects the
# fault through DENY entries for the current user:
#   * no read-data: the body file exists but cannot be read, an unanticipated
#     error inside the probe. It must still be removed, and exit 3.
#   * no delete: the body file cannot be removed after a 200. That must stop
#     the run with exit 3 before the replica wait -- a cleanup failure is never
#     silently accepted -- and the path must not be printed.
# DENY entries, not a trimmed allow list. On the GitHub windows-latest runner
# the job runs as the built-in Administrator (elevated), and its Temp directory
# carries SYSTEM and BUILTIN\Administrators full-control entries that a new
# subdirectory receives as explicit, non-inherited ACEs; `icacls /inheritance:r`
# only strips inherited ones, so a trimmed allow for the user alone restricted
# nothing there and both cases exited 0. A DENY for the user's SID is evaluated
# before every allow, whatever else the ACL carries. The no-delete case denies
# the specific DE right (icacls's simple D is wider and also blocks the read,
# which would stop the probe for the wrong reason) and DC on the directory
# itself, since NTFS otherwise falls back to the parent's delete-child right.
# Each restriction is then proven on a probe file, read and removed the way the
# wrapper does it, so a host where it does not hold fails by name here rather
# than as a wrong exit code.
$me = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
function New-RestrictedTemp {
    param([ValidateSet('NoReadData', 'NoDelete')][string]$Kind)
    $d = Join-Path ([IO.Path]::GetTempPath()) "t89-acl-$([guid]::NewGuid().ToString('N'))"
    New-Item -ItemType Directory -Path $d | Out-Null
    $steps = @(, @('/inheritance:r', '/grant:r', "*${me}:(OI)(CI)F"))
    if ($Kind -eq 'NoReadData') { $steps += , @('/deny', "*${me}:(OI)(IO)(RD)") }
    else { $steps += , @('/deny', "*${me}:(OI)(IO)(DE)"); $steps += , @('/deny', "*${me}:(DC)") }
    foreach ($s in $steps) {
        & icacls $d @s | Out-Null
        if ($LASTEXITCODE -ne 0) { Remove-RestrictedTemp $d | Out-Null; throw "icacls could not restrict $d with '$($s -join ' ')' (exit $LASTEXITCODE)" }
    }
    $probeFile = Join-Path $d 'acl-selfcheck.tmp'
    [IO.File]::WriteAllBytes($probeFile, [byte[]]@(0x7B, 0x7D))
    $readable = $true
    $removable = $true
    try { [IO.File]::ReadAllBytes($probeFile) | Out-Null } catch { $readable = $false }
    try { Remove-Item -LiteralPath $probeFile -Force -ErrorAction Stop } catch { $removable = $false }
    if (Test-Path -LiteralPath $probeFile) { $removable = $false }
    $expectReadable = ($Kind -eq 'NoDelete')
    $expectRemovable = ($Kind -eq 'NoReadData')
    if ($readable -ne $expectReadable -or $removable -ne $expectRemovable) {
        Remove-RestrictedTemp $d | Out-Null
        throw "the $Kind fault injection does not hold on this host: probe file readable=$readable (expected $expectReadable), removable=$removable (expected $expectRemovable)"
    }
    return $d
}
function Remove-RestrictedTemp {
    # Drops the DENY entries and restores access, returns the names of anything
    # still inside, then deletes the directory.
    param([string]$Dir)
    & icacls $Dir /remove:d "*${me}" /T /C | Out-Null
    & icacls $Dir /grant "*${me}:(OI)(CI)F" /T /C | Out-Null
    $inside = @(Get-ChildItem -LiteralPath $Dir -Force -ErrorAction SilentlyContinue | ForEach-Object { $_.Name })
    Remove-Item -LiteralPath $Dir -Recurse -Force -ErrorAction SilentlyContinue
    , $inside
}

Write-Host 'An unanticipated error inside a probe still removes its body file'
$aclDir = $null
$r = $null
$setupError = $null
$leftInDir = @('not-checked')
try {
    $aclDir = New-RestrictedTemp -Kind NoReadData
    $r = Invoke-Wrapper -Env (Probe-Env $good @(@{ STATUS = '503' }, @{ STATUS = '200'; SENTINEL = '1' })) -TempDir $aclDir
} catch { $setupError = $_ } finally { if ($aclDir) { $leftInDir = Remove-RestrictedTemp $aclDir } }
Check 'an unreadable body file => exit 3 after one probe, file removed, details withheld' {
    if ($setupError) { throw "the fault could not be injected: $setupError" }
    if ($r.Exit -ne 3) { throw "exit $($r.Exit) (expected 3); output: $($r.Output)" }
    Assert-ProbeCount $r 1
    Assert-NoReplicaWait $r
    Assert-NoVerifier $r
    if ($r.Output -notmatch 'internal error') { throw "the error was not reported as an internal probe error:`n$($r.Output)" }
    if ($r.Output -match 'FAIL \(unhandled\)') { throw 'the error escaped the probe to the trap' }
    if ($r.Output -match '(?i)t89-acl-|t89-wake-|denied') { throw "the error details or path reached the output:`n$($r.Output)" }
    # Only body files count: Windows PowerShell itself may drop other files in
    # its TMP directory.
    $leftBodies = @($leftInDir | Where-Object { $_ -like 't89-wake-*' })
    if ($r.Leftovers.Count -or $leftBodies.Count) { throw "the body file was left behind ($($r.Leftovers.Count) by path; in the directory: $($leftInDir -join ', '))" }
    if ($r.Bodies.Count -ne 1) { throw "expected one body path, found $($r.Bodies.Count)" }
}

Write-Host 'A body file that cannot be removed stops the run, even after a 200'
$aclDir = $null
$r = $null
$setupError = $null
try {
    $aclDir = New-RestrictedTemp -Kind NoDelete
    $r = Invoke-Wrapper -Env (Probe-Env $good @(@{ STATUS = '200' }, @{ STATUS = '200'; SENTINEL = '1' })) -TempDir $aclDir
} catch { $setupError = $_ } finally { if ($aclDir) { Remove-RestrictedTemp $aclDir | Out-Null } }
Check 'an unremovable body file after a 200 => exit 3, no replica wait, no verifier, path withheld' {
    if ($setupError) { throw "the fault could not be injected: $setupError" }
    if ($r.Exit -ne 3) { throw "exit $($r.Exit) (expected 3); output: $($r.Output)" }
    Assert-ProbeCount $r 1
    Assert-NoReplicaWait $r
    Assert-NoVerifier $r
    if ($r.Output -notmatch 'could not be removed') { throw "the cleanup failure was not reported:`n$($r.Output)" }
    if ($r.Output -match '(?i)t89-acl-|t89-wake-') { throw "the path reached the output:`n$($r.Output)" }
    if ($r.Output -match 'activation confirmed') { throw 'announced activation despite the cleanup failure' }
}

# --- WakeProbeIntervalSeconds -------------------------------------------------------
# A [string] on purpose: an [int] parameter fails binding with exit 1 under
# -File for non-numeric input, before the script runs, and rounds 29.5. Every
# invalid value must exit 2 before ANY child process -- the capture stays empty.
$badIntervals = @(
    @{ Name = 'negative';          V = '-1' },
    @{ Name = 'above 30';          V = '31' },
    @{ Name = 'far above 30';      V = '100' },
    @{ Name = 'non-numeric';       V = 'abc' },
    @{ Name = 'empty';             V = '""' },
    @{ Name = 'fraction 5.0';      V = '5.0' },
    @{ Name = 'fraction 29.5';     V = '29.5' },
    @{ Name = 'leading space';     V = ' 5' },
    @{ Name = 'trailing tab';      V = "5`t" },
    @{ Name = 'plus sign';         V = '+5' },
    @{ Name = 'leading zero';      V = '05' },
    @{ Name = 'double zero';       V = '00' },
    @{ Name = 'exponent';          V = '1e1' },
    @{ Name = 'hex';               V = '0x1' }
)
Write-Host 'An invalid -WakeProbeIntervalSeconds is refused before any child process'
foreach ($b in $badIntervals) {
    $r = Invoke-Wrapper -Env $good.Clone() -Extra @('-WakeProbeIntervalSeconds', $b.V)
    Check "-WakeProbeIntervalSeconds $($b.Name) => exit 2, no child process" {
        if ($r.Exit -ne 2) { throw "exit $($r.Exit) (expected 2); output: $($r.Output)" }
        if ($r.Capture -match '(?m)^(az|docker|curl|python)') { throw "a child process ran:`n$($r.Capture)" }
        if ($r.Output -notmatch 'WakeProbeIntervalSeconds') { throw "did not name the parameter:`n$($r.Output)" }
    }
}
$r = Invoke-Wrapper -Env $good.Clone() -Extra @('-WakeProbeIntervalSeconds', '30')
Check '-WakeProbeIntervalSeconds 30 (the upper bound) is accepted' {
    if ($r.Exit -ne 0) { throw "exit $($r.Exit); output: $($r.Output)" }
    Assert-ProbeCount $r 1
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
# --- The activation sequence, pinned through the AST ------------------------------
# The invariant: the verifier may start only after a sequence of at most six
# probes -- each the one authorized request, with the fixed URL, argument vector
# and 90-second bound -- in which only an exact 503 or a curl timeout led to
# another probe, and a probe returned exactly the scalar status '200'. No
# parameter, environment value, configuration input, alternate function or
# surrounding control flow may alter that decision.
#
# Earlier versions of these pins checked the SHAPE of one condition and were
# defeated from just outside it: a variable pre-assigned before the check, the
# Fail inside the branch wrapped in another if, Fail itself redefined, an
# environment read through a provider path, curl's -w changed so every response
# read 200, --retry, -L, a different URL. Pinning the literal text of the block
# would not help either -- it fails on the same edits made one line outside the
# pinned extent, and turns comment and formatting changes into security events.
#
# So the sequence is built as one safety unit (see Invoke-AuthorizedWake in the
# wrapper), and the checks below are targeted regression guards over its
# structure, not its spelling: they catch accidental drift -- a seventh probe, a
# widened retry set, a loop that carries on past a 200, a stray variable read,
# a second curl call, a raw value printed -- that the behavioural tests above
# might not surface.
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
function Assignments-To {
    # Every assignment to $Name under $Root, in source order.
    param($Root, [string]$Name)
    @(Find-Ast $Root { param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] -and (Target-Var $n.Left) -eq $Name }.GetNewClosure())
}
function Is-Within {
    param($Node, $Container)
    if ($null -eq $Container) { return $false }
    ($Node.Extent.StartOffset -ge $Container.Extent.StartOffset) -and ($Node.Extent.EndOffset -le $Container.Extent.EndOffset)
}
function Enclosing-If {
    # The nearest if-statement around $Node.
    param($Node)
    $p = $Node.Parent
    while ($null -ne $p -and -not ($p -is [System.Management.Automation.Language.IfStatementAst])) { $p = $p.Parent }
    return $p
}

$unitDefs = @(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Invoke-AuthorizedWake' })
$script:unit = if ($unitDefs.Count -eq 1) { $unitDefs[0] } else { $null }
function Inside-Unit { param($n) Is-Within $n $script:unit }
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
# Every HTTP-client command, not just curl by name: PowerShell's own web
# cmdlets and their aliases are counted too, so any of them makes the "only
# curl call" count wrong. The forbidden-construct check below bans them outright.
$curlCalls = @(Find-Ast $wrapperAst {
    param($n)
    $n -is [System.Management.Automation.Language.CommandAst] -and (
        ($n.CommandElements[0] -is [System.Management.Automation.Language.VariableExpressionAst] -and
         @('Curl', 'CurlCommand') -contains $n.CommandElements[0].VariablePath.UserPath) -or
        ("$($n.GetCommandName())" -match '(?i)^((curl|wget)(\.exe)?|Invoke-WebRequest|iwr|Invoke-RestMethod|irm|Start-BitsTransfer)$'))
})
$unitLoops = @(Find-Ast $(if ($script:unit) { $script:unit.Body } else { $null }) { param($n) $n -is [System.Management.Automation.Language.LoopStatementAst] })
$probeLoop = if ($unitLoops.Count -eq 1 -and $unitLoops[0] -is [System.Management.Automation.Language.ForStatementAst]) { $unitLoops[0] } else { $null }
$probeTry = $null
if ($probeLoop -and $curlCalls.Count -eq 1) {
    $probeTry = @(@($probeLoop.Body.Statements) | Where-Object { $_ -is [System.Management.Automation.Language.TryStatementAst] -and (Is-Within $curlCalls[0] $_.Body) }) | Select-Object -First 1
}

Check 'the activation sequence lives in exactly one safety unit, called once and alone from the non-SkipWake branch' {
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
    if ((Norm $wakeCalls[0].Extent.Text) -cne 'Invoke-AuthorizedWake -Curl $CurlCommand -IntervalSeconds $probeIntervalSeconds') { throw "the wake call is '$($wakeCalls[0].Extent.Text)'" }
}

Check 'the shared $commandsAtDefault predicate is defined once, before the SkipWake guard, and the guard reuses it rather than recomputing' {
    if ($skipGuard.Count -ne 1) { throw 'no early SkipWake guard' }
    $g = $skipGuard[0]
    $asg = @(Assignments-To $wrapperAst 'commandsAtDefault')
    if ($asg.Count -ne 1) { throw "`$commandsAtDefault must be assigned exactly once, found $($asg.Count)" }
    if ($asg[0].Extent.StartOffset -gt $g.Extent.StartOffset) { throw '$commandsAtDefault must be assigned before the SkipWake guard' }
    if (@($childCalls | Where-Object { $_.Extent.StartOffset -lt $asg[0].Extent.StartOffset -and -not (Inside-Unit $_) }).Count) {
        throw 'a child process can run before $commandsAtDefault is computed'
    }
    if (@($childCalls | Where-Object { $_.Extent.StartOffset -lt $g.Extent.StartOffset -and -not (Inside-Unit $_) }).Count) {
        throw 'a child process can run before the SkipWake guard'
    }
    $cmps = @(Find-Ast $asg[0].Right { param($n) $n -is [System.Management.Automation.Language.BinaryExpressionAst] -and $n.Operator -eq 'Ieq' } | ForEach-Object { Norm $_.Extent.Text } | Sort-Object)
    $want = @("`$AzCommand -eq 'az'", "`$CurlCommand -eq 'curl.exe'", "`$DockerCommand -eq 'docker'", "`$PythonCommand -eq 'python'") | Sort-Object
    if (($cmps -join ' | ') -cne ($want -join ' | ')) { throw "`$commandsAtDefault compares:`n  $($cmps -join "`n  ")" }
    # The guard itself must not recompute its own comparisons -- there is
    # exactly one definition of "every command is at its default", reused here
    # and by $isLiveRun below.
    if (@(Find-Ast $g { param($n) $n -is [System.Management.Automation.Language.BinaryExpressionAst] -and $n.Operator -eq 'Ieq' }).Count) {
        throw 'the SkipWake guard recomputes its own default comparisons instead of reusing $commandsAtDefault'
    }
    $reads = @(Find-Ast $g { param($n) $n -is [System.Management.Automation.Language.VariableExpressionAst] -and $n.VariablePath.UserPath -eq 'commandsAtDefault' })
    if ($reads.Count -ne 1) { throw "the SkipWake guard must read `$commandsAtDefault exactly once, found $($reads.Count)" }
    $fails = @(Find-Ast $g { param($n) $n -is [System.Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'Fail' })
    if ($fails.Count -ne 1 -or (Norm @($fails[0].CommandElements)[-1].Extent.Text) -ne '2') { throw 'the SkipWake guard must Fail with exit 2' }
}

Check '$isLiveRun reuses the same $commandsAtDefault predicate -- not a second, divergent definition of "live"' {
    $ca = @(Assignments-To $wrapperAst 'commandsAtDefault')
    if ($ca.Count -ne 1) { throw "`$commandsAtDefault must be assigned exactly once, found $($ca.Count)" }
    $live = @(Assignments-To $wrapperAst 'isLiveRun')
    if ($live.Count -ne 1) { throw "`$isLiveRun must be assigned exactly once, found $($live.Count)" }
    if ((Norm $live[0].Right.Extent.Text) -cne '($commandsAtDefault -contains $true)') { throw "`$isLiveRun is assigned from '$((Norm $live[0].Right.Extent.Text))', not from the fail-closed shared `$commandsAtDefault" }
    if ($live[0].Extent.StartOffset -lt $ca[0].Extent.EndOffset) { throw '$isLiveRun must be assigned after $commandsAtDefault' }
    if (@($childCalls | Where-Object { $_.Extent.StartOffset -lt $live[0].Extent.StartOffset -and -not (Inside-Unit $_) }).Count) {
        throw 'a child process can run before $isLiveRun is computed'
    }
}

Check 'WakeProbeIntervalSeconds defaults to 5, is validated before any child process, and only the validated integer reaches the unit' {
    $param = @($wrapperAst.ParamBlock.Parameters | Where-Object { $_.Name.VariablePath.UserPath -eq 'WakeProbeIntervalSeconds' })
    if ($param.Count -ne 1) { throw 'no WakeProbeIntervalSeconds parameter' }
    if ($null -eq $param[0].DefaultValue -or $param[0].DefaultValue.Extent.Text -cne "'5'") { throw "the default is not the literal '5'" }
    $pattern = "-not [regex]::IsMatch(`$WakeProbeIntervalSeconds, '\A(?:[0-9]|[12][0-9]|30)\z')"
    $guards = @(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.IfStatementAst] -and (Norm $n.Clauses[0].Item1.Extent.Text) -ceq $pattern }.GetNewClosure())
    if ($guards.Count -ne 1) { throw "expected exactly one interval validation reading exactly '$pattern', found $($guards.Count)" }
    $g = $guards[0]
    if ($g.Clauses.Count -ne 1 -or $null -ne $g.ElseClause) { throw 'the interval validation has extra clauses' }
    $last = @($g.Clauses[0].Item2.Statements)[-1]
    $failCall = if ($last -is [System.Management.Automation.Language.PipelineAst]) { @($last.PipelineElements)[0] } else { $null }
    if (-not ($failCall -is [System.Management.Automation.Language.CommandAst]) -or $failCall.GetCommandName() -ne 'Fail' -or (Norm @($failCall.CommandElements)[-1].Extent.Text) -ne '2') {
        throw 'the interval validation must end in Fail with exit 2'
    }
    if (@($childCalls | Where-Object { $_.Extent.StartOffset -lt $g.Extent.StartOffset -and -not (Inside-Unit $_) }).Count) { throw 'a child process can run before the interval validation' }
    if ($wakeBranch.Count -ne 1 -or $g.Extent.EndOffset -gt $wakeBranch[0].Extent.StartOffset) { throw 'the interval validation must precede the wake branch' }
    $refs = @(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.VariableExpressionAst] -and $n.VariablePath.UserPath -eq 'WakeProbeIntervalSeconds' })
    if ($refs.Count -ne 3) { throw "`$WakeProbeIntervalSeconds must be read only by its declaration, the validation and the conversion; found $($refs.Count) references" }
    $asg = @(Assignments-To $wrapperAst 'probeIntervalSeconds')
    if ($asg.Count -ne 1 -or (Norm $asg[0].Right.Extent.Text) -cne '[int]$WakeProbeIntervalSeconds') { throw '$probeIntervalSeconds must be assigned exactly once, from [int]$WakeProbeIntervalSeconds' }
    if ($asg[0].Extent.StartOffset -lt $g.Extent.EndOffset -or -not [object]::ReferenceEquals($asg[0].Parent, $g.Parent)) { throw 'the conversion must follow the validation, unconditionally, in the same block' }
    $uses = @(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.VariableExpressionAst] -and $n.VariablePath.UserPath -eq 'probeIntervalSeconds' })
    if ($uses.Count -ne 3) { throw "`$probeIntervalSeconds must be assigned once and read only by the live-run interval check and the wake call; found $($uses.Count) references" }
    if (@(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.VariableExpressionAst] -and $n.VariablePath.UserPath -eq 'IntervalSeconds' } | Where-Object { -not (Inside-Unit $_) }).Count) {
        throw '$IntervalSeconds is referenced outside the safety unit'
    }
    $sleeps = @(Find-Ast $script:unit { param($n) $n -is [System.Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'Start-Sleep' })
    if ($sleeps.Count -ne 1 -or (Norm $sleeps[0].Extent.Text) -cne 'Start-Sleep -Seconds $IntervalSeconds') { throw 'the unit must contain exactly one Start-Sleep -Seconds $IntervalSeconds' }
    $sleepIf = Enclosing-If $sleeps[0]
    if ($null -eq $sleepIf -or (Norm $sleepIf.Clauses[0].Item1.Extent.Text) -cne '$probe -gt 1' -or -not (Is-Within $sleepIf $probeLoop)) { throw 'the interval must be slept only between probes, inside the probe loop' }
}

Check 'a live run must use exactly the production interval, checked before any child process, right after the format validation' {
    $pattern = '$isLiveRun -and $probeIntervalSeconds -ne 5'
    $guards = @(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.IfStatementAst] -and (Norm $n.Clauses[0].Item1.Extent.Text) -ceq $pattern }.GetNewClosure())
    if ($guards.Count -ne 1) { throw "expected exactly one live-interval check reading exactly '$pattern', found $($guards.Count)" }
    $g = $guards[0]
    if ($g.Clauses.Count -ne 1 -or $null -ne $g.ElseClause) { throw 'the live-interval check has extra clauses' }
    $last = @($g.Clauses[0].Item2.Statements)[-1]
    $failCall = if ($last -is [System.Management.Automation.Language.PipelineAst]) { @($last.PipelineElements)[0] } else { $null }
    if (-not ($failCall -is [System.Management.Automation.Language.CommandAst]) -or $failCall.GetCommandName() -ne 'Fail' -or (Norm @($failCall.CommandElements)[-1].Extent.Text) -ne '2') {
        throw 'the live-interval check must end in Fail with exit 2'
    }
    if (@($childCalls | Where-Object { $_.Extent.StartOffset -lt $g.Extent.StartOffset -and -not (Inside-Unit $_) }).Count) {
        throw 'a child process can run before the live-interval check'
    }
    # It must come after the format validation (so a malformed interval is
    # always reported as a format error, not a liveness error) and before the
    # wake branch.
    $formatPattern = "-not [regex]::IsMatch(`$WakeProbeIntervalSeconds, '\A(?:[0-9]|[12][0-9]|30)\z')"
    $formatGuards = @(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.IfStatementAst] -and (Norm $n.Clauses[0].Item1.Extent.Text) -ceq $formatPattern }.GetNewClosure())
    if ($formatGuards.Count -ne 1 -or $g.Extent.StartOffset -lt $formatGuards[0].Extent.EndOffset) { throw 'the live-interval check must follow the format validation' }
    if ($wakeBranch.Count -ne 1 -or $g.Extent.EndOffset -gt $wakeBranch[0].Extent.StartOffset) { throw 'the live-interval check must precede the wake branch' }
}

Check 'the safety unit makes the only curl call, with exactly the fixed arguments, URL, proxy bypass and 90-second bound' {
    if ($curlCalls.Count -ne 1) { throw "expected exactly one HTTP-client invocation (the curl call) in the script, found $($curlCalls.Count)" }
    $c = $curlCalls[0]
    if (-not (Inside-Unit $c)) { throw 'the curl invocation is outside the safety unit' }
    $actual = @($c.CommandElements | ForEach-Object { $_.Extent.Text })
    $expected = @('$Curl', '-q', '--noproxy', "'*'", '-sS', '-o', '$bodyPath', '-w', "'t89:%{http_code}:%{time_total}:%{content_type}'", '--max-time', '90', "'https://api.vibhanshu-ai-portfolio.dev/actuator/health'")
    if (($actual -join ' ') -cne ($expected -join ' ')) { throw "curl argument vector is: $($actual -join ' ')" }
    # -q first; --noproxy immediately after it; its value exactly one
    # single-quoted literal '*' (a constant PowerShell passes as the character
    # itself, not a variable, an expandable string or a host list).
    $els = @($c.CommandElements)
    if ($els.Count -lt 4) { throw 'the curl call has too few elements' }
    if (-not ($els[1] -is [System.Management.Automation.Language.CommandParameterAst]) -or $els[1].Extent.Text -cne '-q') { throw "curl's first argument is not the literal -q: $($els[1].Extent.Text)" }
    # PowerShell parses --noproxy as a bare-word string constant, not a parameter.
    if (-not ($els[2] -is [System.Management.Automation.Language.StringConstantExpressionAst]) -or $els[2].StringConstantType -ne [System.Management.Automation.Language.StringConstantType]::BareWord -or $els[2].Value -cne '--noproxy') { throw "the argument after -q is not the literal --noproxy: $($els[2].Extent.Text)" }
    $np = $els[3]
    if (-not ($np -is [System.Management.Automation.Language.StringConstantExpressionAst]) -or $np.StringConstantType -ne [System.Management.Automation.Language.StringConstantType]::SingleQuoted -or $np.Value -cne '*') {
        throw "the --noproxy value is not the single-quoted literal '*': $($np.Extent.Text)"
    }
    if (@($els | Where-Object { $_.Extent.Text -ceq '--noproxy' }).Count -ne 1) { throw '--noproxy appears more than once' }
    # curl's stderr is discarded: on a local write failure it names the body file.
    $redirs = @($c.Redirections | ForEach-Object { Norm $_.Extent.Text })
    if (($redirs -join ' | ') -cne '2>$null') { throw "curl redirections are: '$($redirs -join ' | ')'" }
    $ps = @($script:unit.Body.ParamBlock.Parameters | ForEach-Object { '{0}:{1}' -f $_.Name.VariablePath.UserPath, $_.StaticType.Name })
    if (($ps -join ',') -cne 'Curl:String,IntervalSeconds:Int32') { throw "the safety unit parameters are '$($ps -join ',')', expected exactly [string]`$Curl and [int]`$IntervalSeconds" }
    # The call's output is the metadata record, and its exit code is read on
    # the very next statement.
    $asg = $c.Parent.Parent
    if (-not ($asg -is [System.Management.Automation.Language.AssignmentStatementAst]) -or (Target-Var $asg.Left) -ne 'probeMeta') { throw 'the curl output is not assigned directly to $probeMeta' }
    $siblings = @($asg.Parent.Statements)
    $idx = [array]::IndexOf($siblings, $asg)
    if ($idx -lt 0 -or $idx + 1 -ge $siblings.Count -or (Norm $siblings[$idx + 1].Extent.Text) -cne '$probeExit = $LASTEXITCODE') { throw 'the curl exit code is not read on the statement after the call' }
    $bp = @(Assignments-To $wrapperAst 'bodyPath')
    $wantPath = "Join-Path ([IO.Path]::GetTempPath()) ('t89-wake-' + [guid]::NewGuid().ToString('N') + '.body')"
    if ($bp.Count -ne 1 -or (Norm $bp[0].Right.Extent.Text) -cne $wantPath) { throw "`$bodyPath must be assigned exactly once, to a fresh generated temp path" }
    if (-not (Is-Within $bp[0] $probeLoop) -or $bp[0].Extent.StartOffset -gt $c.Extent.StartOffset) { throw 'the body path must be generated inside the probe loop, before the call, so each probe gets a fresh file' }
}

Check 'the probe budget is $script:ProbeBudget, in one bounded loop holding the only curl call' {
    if ($null -eq $probeLoop) { throw "the unit must contain exactly one loop, a for loop; found $($unitLoops.Count) loop(s)" }
    $f = $probeLoop
    $header = "for ($(Norm $f.Initializer.Extent.Text); $(Norm $f.Condition.Extent.Text); $(Norm $f.Iterator.Extent.Text))"
    if ($header -cne 'for ($probe = 1; $probe -le $script:ProbeBudget; $probe++)') { throw "the loop header is '$header'" }
    if (-not ([object]::ReferenceEquals($f.Parent, $script:unit.Body.EndBlock))) { throw 'the probe loop is not a top-level statement of the unit' }
    if ($curlCalls.Count -ne 1 -or -not (Is-Within $curlCalls[0] $f.Body)) { throw 'the curl call is not inside the probe loop' }
    $writes = @(Find-Ast $wrapperAst {
        param($n)
        ($n -is [System.Management.Automation.Language.AssignmentStatementAst] -and (Target-Var $n.Left) -eq 'probe') -or
        ($n -is [System.Management.Automation.Language.UnaryExpressionAst] -and $n.Child -is [System.Management.Automation.Language.VariableExpressionAst] -and $n.Child.VariablePath.UserPath -eq 'probe')
    })
    if ($writes.Count -ne 2) { throw "`$probe must be written only by the loop header; found $($writes.Count) writes" }
    foreach ($kind in @('BreakStatementAst', 'ContinueStatementAst', 'TrapStatementAst', 'FunctionDefinitionAst', 'ScriptBlockExpressionAst', 'SwitchStatementAst')) {
        $t = [type]"System.Management.Automation.Language.$kind"
        if (@(Find-Ast $script:unit.Body { param($n) $n -is $t }.GetNewClosure()).Count) { throw "the safety unit contains a $kind" }
    }
    foreach ($name in @('Invoke-AuthorizedWake', 'Fail')) {
        if (@(Find-Ast $script:unit { param($n) $n -is [System.Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq $name }.GetNewClosure()).Count) { throw "the safety unit calls $name" }
    }
}

Check '$script:ProbeBudget is a single literal 6, not a parameter, assigned once outside the unit -- so the announced ceiling can never drift from the loop bound (same variable)' {
    if ($wrapperAst.ParamBlock.Parameters | Where-Object { $_.Name.VariablePath.UserPath -eq 'ProbeBudget' }) { throw 'ProbeBudget must not be a script parameter' }
    $asg = @(Assignments-To $wrapperAst 'script:ProbeBudget')
    if ($asg.Count -ne 1) { throw "`$script:ProbeBudget must be assigned exactly once, found $($asg.Count)" }
    if ((Norm $asg[0].Right.Extent.Text) -cne '6') { throw "`$script:ProbeBudget is assigned '$((Norm $asg[0].Right.Extent.Text))', not the literal 6" }
    if (Inside-Unit $asg[0]) { throw '$script:ProbeBudget must be assigned outside the safety unit, like $script:WakeIssued' }
    if ($asg[0].Extent.EndOffset -gt $script:unit.Extent.StartOffset) { throw '$script:ProbeBudget must be assigned before the unit that reads it' }
    # The loop bound, the initial announcement, every per-probe progress/failure
    # string, and the exhaustion message (which names the budget twice) all read
    # this one variable, so the announced ceiling and the actual loop bound
    # cannot drift apart -- there is no second number anywhere that could.
    $reads = @(Find-Ast $script:unit { param($n) $n -is [System.Management.Automation.Language.VariableExpressionAst] -and $n.VariablePath.UserPath -eq 'script:ProbeBudget' })
    if ($reads.Count -ne 14) { throw "`$script:ProbeBudget is read $($reads.Count) time(s) inside the unit; expected exactly 14 (the loop bound, every progress/failure string once, and the exhaustion message twice)" }
    $condRefs = @(Find-Ast $probeLoop.Condition { param($n) $n -is [System.Management.Automation.Language.VariableExpressionAst] -and $n.VariablePath.UserPath -eq 'script:ProbeBudget' })
    if ($condRefs.Count -ne 1) { throw 'the loop condition must read $script:ProbeBudget exactly once' }
    # No stray literal digit stands in for the budget anywhere in the unit's
    # progress, stop or exhaustion strings.
    if ($script:unit.Extent.Text -match '\$probe/[0-9]') { throw "a probe-progress string uses a literal digit instead of `$script:ProbeBudget: $($Matches[0])" }
    if ($script:unit.Extent.Text -match '\b[0-9]+ of [0-9]+ activation probes\b') { throw 'the exhaustion message uses a literal digit instead of $script:ProbeBudget' }
}

Check 'only an exact 200 activates, only an exact 503 or a curl timeout probes again, and every other outcome exits 3' {
    $decisions = @(Find-Ast $script:unit { param($n) $n -is [System.Management.Automation.Language.IfStatementAst] -and (Norm $n.Clauses[0].Item1.Extent.Text) -like '$probeExit -eq *' })
    if ($decisions.Count -ne 1) { throw "expected exactly one outcome decision in the unit, found $($decisions.Count)" }
    $d = $decisions[0]
    $conds = @($d.Clauses | ForEach-Object { Norm $_.Item1.Extent.Text })
    $expected = @(
        "`$probeExit -eq 0 -and `$metaOk -and `$httpStatus -ceq '200'",
        "`$probeExit -eq 0 -and `$metaOk -and `$httpStatus -ceq '503'",
        "`$probeExit -eq 28 -and `$metaOk -and `$httpStatus -ceq '000'"
    )
    if (($conds -join ' | ') -cne ($expected -join ' | ')) { throw "the outcome decision is, in order:`n  $($conds -join "`n  ")`nexpected exactly:`n  $($expected -join "`n  ")" }
    if ($null -eq $d.ElseClause) { throw 'the outcome decision has no else' }
    if (-not (Is-Within $d $probeTry.Body)) { throw 'the outcome decision is not inside the probe try block' }
    $c1 = @($d.Clauses[0].Item2.Statements)
    if ($c1.Count -ne 1 -or (Norm $c1[0].Extent.Text) -cne '$activated = $true') { throw 'the 200 branch must do exactly one thing: $activated = $true' }
    foreach ($i in 1, 2) {
        foreach ($s in @($d.Clauses[$i].Item2.Statements)) {
            $cmd = if ($s -is [System.Management.Automation.Language.PipelineAst] -and @($s.PipelineElements).Count -eq 1) { $s.PipelineElements[0] } else { $null }
            if (-not ($cmd -is [System.Management.Automation.Language.CommandAst]) -or $cmd.GetCommandName() -ne 'Write-Host') { throw "a retryable branch does more than report: $($s.Extent.Text)" }
        }
    }
    $elseLast = @($d.ElseClause.Statements)[-1]
    if (-not ($elseLast -is [System.Management.Automation.Language.ExitStatementAst]) -or (Norm $elseLast.Pipeline.Extent.Text) -ne '3') { throw 'the else branch does not end in a direct exit 3' }
    # The decision inputs are assigned only where the metadata record is parsed.
    $pinned = [ordered]@{
        'probeExit'  = @('$LASTEXITCODE')
        'metaOk'     = @('$false', '$true')
        'httpStatus' = @("'invalid'", '$record.Groups[1].Value')
        'activated'  = @('$false', '$true')
        'record'     = @("[regex]::Match(`$probeMeta, '\At89:(000|[1-5][0-9][0-9]):([0-9]{1,6}\.[0-9]{1,6}):([^\r\n]*)\z')")
    }
    foreach ($v in $pinned.Keys) {
        $rhs = @(Assignments-To $wrapperAst $v | ForEach-Object { Norm $_.Right.Extent.Text })
        if (($rhs -join ' | ') -cne ($pinned[$v] -join ' | ')) { throw "`$$v is assigned: $($rhs -join ' | ')" }
    }
    $recordAsg = @(Assignments-To $wrapperAst 'record')[0]
    $recordIf = Enclosing-If $recordAsg
    if ($null -eq $recordIf -or (Norm $recordIf.Clauses[0].Item1.Extent.Text) -cne '$probeMeta -is [string]') { throw 'the record is parsed only from a single [string] of metadata' }
    foreach ($v in 'metaOk', 'httpStatus') {
        $a = @(Assignments-To $wrapperAst $v)[1]
        $i1 = Enclosing-If $a
        if ($null -eq $i1 -or (Norm $i1.Clauses[0].Item1.Extent.Text) -cne '$record.Success' -or -not (Is-Within $i1 $recordIf)) { throw "`$$v takes its parsed value outside if (`$record.Success)" }
    }
}

Check 'the first 200 returns at once, exhaustion and every stop exit 3, so no verifier starts without an exact 200' {
    if ($null -eq $probeLoop -or $null -eq $probeTry) { throw 'no probe loop with a try around the curl call' }
    $end = @($script:unit.Body.EndBlock.Statements)
    $last = $end[-1]
    if (-not ($last -is [System.Management.Automation.Language.ExitStatementAst]) -or (Norm $last.Pipeline.Extent.Text) -ne '3') { throw 'the unit does not end in exit 3 after the probe loop' }
    $loopIdx = [array]::IndexOf($end, $probeLoop)
    for ($i = $loopIdx + 1; $i -lt $end.Count - 1; $i++) {
        $s = $end[$i]
        $cmd = if ($s -is [System.Management.Automation.Language.PipelineAst]) { @($s.PipelineElements)[0] } else { $null }
        if (-not ($cmd -is [System.Management.Automation.Language.CommandAst]) -or $cmd.GetCommandName() -ne 'Write-Host') { throw "something other than a report runs between exhaustion and exit 3: $($s.Extent.Text)" }
    }
    $returns = @(Find-Ast $script:unit { param($n) $n -is [System.Management.Automation.Language.ReturnStatementAst] })
    if ($returns.Count -ne 1 -or $null -ne $returns[0].Pipeline) { throw "expected exactly one bare return in the unit, found $($returns.Count)" }
    $ifAct = Enclosing-If $returns[0]
    if ($null -eq $ifAct -or (Norm $ifAct.Clauses[0].Item1.Extent.Text) -cne '$activated' -or $ifAct.Clauses.Count -ne 1 -or $null -ne $ifAct.ElseClause) { throw 'the return is not inside a plain if ($activated)' }
    if (-not [object]::ReferenceEquals(@($ifAct.Clauses[0].Item2.Statements)[-1], $returns[0])) { throw 'the return is not the last statement of if ($activated)' }
    $body = @($probeLoop.Body.Statements)
    if (-not [object]::ReferenceEquals($body[-1], $ifAct)) { throw 'if ($activated) must be the last statement of the probe loop' }
    $stop = $body[-2]
    if (-not ($stop -is [System.Management.Automation.Language.IfStatementAst]) -or (Norm $stop.Clauses[0].Item1.Extent.Text) -cne '$cleanupFailed' -or (Norm $stop.Clauses[0].Item2.Extent.Text) -cne '{ exit 3 }') { throw 'a cleanup failure must exit 3 immediately before the activation return' }
    if (-not [object]::ReferenceEquals($body[-3], $probeTry)) { throw 'the probe try must immediately precede the cleanup stop' }
    if (@($probeTry.CatchClauses).Count -ne 1 -or @($probeTry.CatchClauses[0].CatchTypes).Count -ne 0) { throw 'the probe try must have exactly one catch-all' }
    $catchLast = @($probeTry.CatchClauses[0].Body.Statements)[-1]
    if (-not ($catchLast -is [System.Management.Automation.Language.ExitStatementAst]) -or (Norm $catchLast.Pipeline.Extent.Text) -ne '3') { throw 'the catch-all does not end in exit 3' }
    if ($null -eq $probeTry.Finally) { throw 'the probe try has no finally to remove the body file' }
    $exits = @(Find-Ast $script:unit { param($n) $n -is [System.Management.Automation.Language.ExitStatementAst] })
    if ($exits.Count -ne 4) { throw "expected exactly 4 exit statements in the unit (stop, internal error, cleanup failure, exhaustion), found $($exits.Count)" }
    foreach ($x in $exits) { if ((Norm $x.Pipeline.Extent.Text) -ne '3') { throw "an exit in the unit is not exit 3: $($x.Extent.Text)" } }
    foreach ($v in 'activated', 'cleanupFailed') {
        $reset = @(Assignments-To $wrapperAst $v | Where-Object { (Norm $_.Right.Extent.Text) -eq '$false' })
        if ($reset.Count -ne 1 -or -not [object]::ReferenceEquals($reset[0].Parent, $probeLoop.Body) -or $reset[0].Extent.StartOffset -gt $probeTry.Extent.StartOffset) { throw "`$$v must be reset once per probe, before the try" }
    }
    foreach ($a in @(Assignments-To $wrapperAst 'cleanupFailed' | Where-Object { (Norm $_.Right.Extent.Text) -eq '$true' })) {
        if (-not (Is-Within $a $probeTry.Finally)) { throw '$cleanupFailed is set outside the finally' }
    }
    $rm = @(Find-Ast $probeTry.Finally { param($n) $n -is [System.Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'Remove-Item' })
    if ($rm.Count -ne 1 -or (Norm $rm[0].Extent.Text) -cne 'Remove-Item -LiteralPath $bodyPath -Force -ErrorAction Stop') { throw 'the finally must remove the body file with Remove-Item -LiteralPath $bodyPath -Force -ErrorAction Stop' }
    if (@(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'Remove-Item' }).Count -ne 1) { throw 'Remove-Item appears outside the probe cleanup' }
}

Check 'the safety unit prints only allowlisted values' {
    # The fingerprint is diagnostic, not a transcript of the response. Strings the
    # unit prints may interpolate only validated or allowlisted values; the raw
    # metadata, body text, JSON, content type and temp path never appear in one.
    $printable = @('probe', 'script:ProbeBudget', 'startedUtc', 'probeExit', 'httpStatus', 'duration', 'typeLabel', 'bodyBytes', 'bodySha', 'bodyClass', 'statusToken', 'earlier', '_')
    foreach ($s in (Find-Ast $script:unit { param($n) $n -is [System.Management.Automation.Language.ExpandableStringExpressionAst] })) {
        foreach ($v in (Find-Ast $s { param($n) $n -is [System.Management.Automation.Language.VariableExpressionAst] })) {
            $name = $v.VariablePath.UserPath
            if ($printable -notcontains $name) { throw "an output string interpolates `$${name}: $($s.Extent.Text)" }
            $top = $v
            while ($top.Parent -is [System.Management.Automation.Language.MemberExpressionAst]) { $top = $top.Parent }
            if ($name -eq 'bodyBytes' -and (Norm $top.Extent.Text) -cne '$bodyBytes.Length') { throw "`$bodyBytes is interpolated as more than its length: $($top.Extent.Text)" }
            if ($name -eq '_' -and (Norm $top.Extent.Text) -cne '$_.Exception.GetType().Name') { throw "the caught error is interpolated as more than its type name: $($top.Extent.Text)" }
        }
    }
    foreach ($c in (Find-Ast $script:unit { param($n) $n -is [System.Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'Write-Host' })) {
        $arg = @($c.CommandElements)[1]
        $okArg = ($arg -is [System.Management.Automation.Language.StringConstantExpressionAst]) -or
                 ($arg -is [System.Management.Automation.Language.ExpandableStringExpressionAst]) -or
                 ($arg -is [System.Management.Automation.Language.VariableExpressionAst] -and $arg.VariablePath.UserPath -eq 'fingerprint')
        if (-not $okArg) { throw "Write-Host prints something other than a string or the fingerprint: $($c.Extent.Text)" }
    }
    foreach ($a in (Assignments-To $script:unit 'fingerprint')) {
        $expr = if ($a.Right -is [System.Management.Automation.Language.PipelineAst]) { @($a.Right.PipelineElements)[0] } else { $a.Right }
        if (-not ($expr -is [System.Management.Automation.Language.CommandExpressionAst]) -or -not ($expr.Expression -is [System.Management.Automation.Language.ExpandableStringExpressionAst])) { throw "the fingerprint is built from something other than an interpolated string: $($a.Extent.Text)" }
    }
    $outputs = @('Write-Output', 'echo', 'write', 'Write-Error', 'Write-Warning', 'Write-Verbose', 'Write-Debug', 'Write-Information', 'Out-Host', 'Out-File', 'Out-Default', 'Out-String', 'Set-Content', 'Add-Content', 'Tee-Object')
    foreach ($c in (Find-Ast $script:unit { param($n) $n -is [System.Management.Automation.Language.CommandAst] })) {
        if ($outputs -contains "$($c.GetCommandName())") { throw "the unit writes through $($c.GetCommandName())" }
    }
    # A bare expression statement would reach the success stream and the
    # transcript. Every statement-level pipeline must be a command from this list.
    # (The body of a $(...) or @(...) is not statement-level: its value is an
    # operand of the expression around it, and that expression's own statement
    # is what this check sees.)
    $statementCommands = @('Write-Host', 'Start-Sleep', 'Remove-Item')
    foreach ($p in (Find-Ast $script:unit {
        param($n)
        $n -is [System.Management.Automation.Language.PipelineAst] -and
        ($n.Parent -is [System.Management.Automation.Language.StatementBlockAst] -or $n.Parent -is [System.Management.Automation.Language.NamedBlockAst]) -and
        -not ($n.Parent.Parent -is [System.Management.Automation.Language.SubExpressionAst] -or $n.Parent.Parent -is [System.Management.Automation.Language.ArrayExpressionAst])
    })) {
        $head = @($p.PipelineElements)[0]
        if (-not ($head -is [System.Management.Automation.Language.CommandAst]) -or $statementCommands -notcontains "$($head.GetCommandName())") { throw "a statement in the unit could write to the success stream: $($p.Extent.Text)" }
    }
    if (@(Find-Ast $script:unit { param($n) $n -is [System.Management.Automation.Language.TypeExpressionAst] -and "$($n.TypeName.FullName)" -match '(?i)console' }).Count) { throw 'the unit uses [Console]' }
}

Check 'the safety unit reads only its own variables, and its decision variables are not used outside it' {
    $allowed = @('Curl', 'IntervalSeconds', 'probe', 'script:ProbeBudget', 'bodyPath', 'activated', 'cleanupFailed', 'startedUtc', 'script:WakeIssued', 'probeMeta', 'probeExit', 'LASTEXITCODE',
                 'metaOk', 'httpStatus', 'duration', 'typeLabel', 'record', 'media', 'bodyBytes', 'bodySha', 'bodyClass', 'statusToken', 'bodyText', 'bodyDoc',
                 'statusProp', 'statusValue', 'fingerprint', 'earlier', 'true', 'false', 'null', '_')
    $vars = @(Find-Ast $script:unit { param($n) $n -is [System.Management.Automation.Language.VariableExpressionAst] } | ForEach-Object { $_.VariablePath.UserPath } | Sort-Object -Unique)
    $stray = @($vars | Where-Object { $allowed -notcontains $_ })
    if ($stray.Count) { throw "the safety unit reads variables outside its own sequence: $($stray -join ', ')" }
    foreach ($v in @('probeMeta', 'probeExit', 'httpStatus', 'metaOk', 'activated', 'record', 'bodyPath', 'cleanupFailed', 'probe', 'statusToken', 'bodyClass')) {
        $out = @(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.VariableExpressionAst] -and $n.VariablePath.UserPath -eq $v }.GetNewClosure() | Where-Object { -not (Inside-Unit $_) })
        if ($out.Count) { throw "`$$v is referenced outside the safety unit" }
    }
    if (@(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] -and (Target-Var $n.Left) -eq 'CurlCommand' }).Count) {
        throw '$CurlCommand is reassigned after binding, which could redirect the probes'
    }
    $wi = @(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] -and (Target-Var $n.Left) -eq 'script:WakeIssued' })
    $inUnit = @($wi | Where-Object { Inside-Unit $_ })
    $outUnit = @($wi | Where-Object { -not (Inside-Unit $_) })
    if ($inUnit.Count -ne 1 -or (Norm $inUnit[0].Right.Extent.Text) -ne '$true') { throw 'the safety unit must set $script:WakeIssued = $true exactly once' }
    if ($null -eq $probeTry -or -not (Is-Within $inUnit[0] $probeTry.Body) -or $inUnit[0].Extent.StartOffset -gt $curlCalls[0].Extent.StartOffset) { throw '$script:WakeIssued must be set inside the probe try, before the curl call' }
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

function Find-ForbiddenConstruct {
    # The first forbidden construct under $Ast, as a message, or $null. A
    # function rather than inline so the fidelity check below can prove it
    # fires on each banned form, not merely that the wrapper has none.
    #
    # Network clients are banned alongside dynamic execution: the offline
    # suite has no network sandbox, so a web cmdlet or .NET client added to the
    # wrapper would reach the network unobserved. They are caught by command
    # name, by type expression, and by type NAME in a string -- New-Object
    # System.Net.WebClient, [type]'System.Net.WebClient', or a COM ProgID --
    # plus the string-to-type routes ([type], [Activator], reflection). None of
    # these is used by the wrapper.
    param($Ast)
    $bannedCmds = @('Invoke-Expression', 'iex', 'Set-Alias', 'New-Alias', 'sal', 'nal', 'Import-Alias', 'Set-Item', 'si', 'New-Item', 'ni',
                    'Set-Variable', 'sv', 'New-Variable', 'nv', 'Clear-Variable', 'clv', 'Remove-Variable', 'rv', 'Start-Process', 'saps', 'start',
                    'Invoke-Command', 'icm', 'Add-Type', 'Import-Module', 'ipmo', 'Invoke-Item', 'ii', 'Get-Item', 'gi', 'Get-ChildItem', 'gci', 'ls', 'dir',
                    'Invoke-WebRequest', 'iwr', 'Invoke-RestMethod', 'irm', 'wget', 'curl', 'Start-BitsTransfer', 'Test-NetConnection', 'tnc',
                    'Test-Connection', 'Resolve-DnsName', 'New-WebServiceProxy', 'Send-MailMessage', 'New-Object')
    $allowedHeads = @('AzCommand', 'DockerCommand', 'PythonCommand', 'Curl', 'expr')
    foreach ($c in (Find-Ast $Ast { param($n) $n -is [System.Management.Automation.Language.CommandAst] })) {
        $head = $c.CommandElements[0]
        if ($head -is [System.Management.Automation.Language.VariableExpressionAst]) {
            if ($allowedHeads -notcontains $head.VariablePath.UserPath) { return "forbidden dynamic invocation: $($c.Extent.Text)" }
        } elseif (-not ($head -is [System.Management.Automation.Language.StringConstantExpressionAst])) {
            return "forbidden computed command: $($c.Extent.Text)"
        } elseif ($bannedCmds -contains "$($c.GetCommandName())") {
            return "forbidden command: $($c.GetCommandName())"
        }
    }
    foreach ($s in (Find-Ast $Ast { param($n) $n -is [System.Management.Automation.Language.StringConstantExpressionAst] -or $n -is [System.Management.Automation.Language.ExpandableStringExpressionAst] })) {
        if ("$($s.Value)" -match '(?i)\b(env|alias|function|variable):') { return "forbidden provider path in a string: $($s.Extent.Text)" }
        if ("$($s.Value)" -match '(?i)((^|[^\w.])(System\.)?Net\.[a-z]|\b(WebClient|HttpClient|WebRequest|HttpWebRequest|TcpClient|UdpClient|XMLHTTP|ServerXMLHTTP|WinHttp\w*)\b)') { return "forbidden network type name in a string: $($s.Extent.Text)" }
    }
    foreach ($t in (Find-Ast $Ast { param($n) $n -is [System.Management.Automation.Language.TypeExpressionAst] -or $n -is [System.Management.Automation.Language.TypeConstraintAst] })) {
        if ("$($t.TypeName.FullName)" -match '(?i)(^|\.)(Environment|ScriptBlock|PowerShell|Runspace\w*|SessionState\w*|Process)$') { return "forbidden type: [$($t.TypeName.FullName)]" }
        if ("$($t.TypeName.FullName)" -match '(?i)(^|\.)(Net(\.\w+)*|WebClient|HttpClient|Sockets(\.\w+)*)$') { return "forbidden network type: [$($t.TypeName.FullName)]" }
        if ("$($t.TypeName.FullName)" -match '(?i)(^|\.)(Type|Activator|Reflection(\.\w+)*)$') { return "forbidden string-to-type route: [$($t.TypeName.FullName)]" }
    }
    foreach ($m in (Find-Ast $Ast { param($n) $n -is [System.Management.Automation.Language.MemberExpressionAst] })) {
        if ("$($m.Member.Extent.Text)" -match '(?i)^(InvokeScript|NewScriptBlock|Create|GetEnvironmentVariables?|ExpandEnvironmentVariables|SetEnvironmentVariable|Invoke|InvokeReturnAsIs|Start)$') {
            return "forbidden member: $($m.Extent.Text)"
        }
    }
    $bannedVars = @('args', 'PSBoundParameters', 'ExecutionContext', 'input', 'MyInvocation', 'PSCmdlet')
    foreach ($v in (Find-Ast $Ast { param($n) $n -is [System.Management.Automation.Language.VariableExpressionAst] })) {
        if ($bannedVars -contains $v.VariablePath.UserPath) { return "forbidden variable: `$$($v.VariablePath.UserPath)" }
        if ($v.VariablePath.UserPath -match '(?i)^env:' -and @('env:TASK8_9_ACCESS_TOKEN', 'env:TASK8_9_DEMO_PASSWORD') -notcontains $v.VariablePath.UserPath) {
            return "forbidden environment read: `$$($v.VariablePath.UserPath)"
        }
    }
    return $null
}

Check 'the script forbids dynamic execution, alias or function replacement, network clients, and ambient input channels' {
    if (@($wrapperAst.ParamBlock.Attributes | ForEach-Object { $_.TypeName.Name }) -notcontains 'CmdletBinding') {
        throw '[CmdletBinding()] is absent, so unknown parameters would land in $args instead of being rejected'
    }
    if ($wrapperAst.Extent.Text -match '(?im)^\s*dynamicparam\b') { throw 'dynamicparam block present' }
    $found = Find-ForbiddenConstruct $wrapperAst
    if ($found) { throw $found }
}

Check 'the forbidden-construct guard fires on every banned network form, and not on what the wrapper uses' {
    # Fidelity for the guard above: a guard that never fires passes the wrapper
    # just as well as one that works. Each snippet is only PARSED, never run.
    # The fixtures use inert arguments -- no URL, nothing fetched or executed --
    # because Windows Defender's AMSI scan blocks a script block that resembles
    # a download cradle (an earlier draft of this list, with a download method
    # call and an expression invoker, was blocked).
    $banned = @(
        "Invoke-WebRequest -Uri 'x'",
        "iwr 'x'",
        "Invoke-RestMethod 'x'",
        "irm 'x'",
        "wget 'x'",
        "curl 'x'",
        "Start-BitsTransfer -Source 'x' -Destination 'y'",
        "Test-NetConnection 'example.invalid' -Port 443",
        "Test-Connection 'example.invalid'",
        "Resolve-DnsName 'example.invalid'",
        "[System.Net.WebClient]::new()",
        "[Net.WebClient]::new()",
        "[System.Net.Http.HttpClient]::new()",
        "[System.Net.Sockets.TcpClient]::new()",
        "[Net.Dns]::GetHostName()",
        "`$c = [System.Net.WebClient]`$null",
        "New-Object System.Net.WebClient",
        "New-Object -TypeName 'System.Net.WebClient'",
        "([type]'System.Net.WebClient')::new()",
        "'System.Net.WebClient' -as [type]",
        "[Activator]::CreateInstance(`$t)",
        "[System.Reflection.Assembly]::GetExecutingAssembly()",
        "`$x = 'Net.WebClient'",
        "New-Object -ComObject 'MSXML2.XMLHTTP'",
        # Two of the pre-existing bans, so the refactor into a function is
        # shown to have kept them.
        "Set-Alias -Name x -Value y",
        "[Environment]::GetEnvironmentVariable('X')"
    )
    foreach ($snippet in $banned) {
        $e = $null
        $snipAst = [System.Management.Automation.Language.Parser]::ParseInput($snippet, [ref]$null, [ref]$e)
        if ($e) { throw "fixture does not parse: $snippet" }
        if (-not (Find-ForbiddenConstruct $snipAst)) { throw "the guard did not fire on: $snippet" }
    }
    $allowed = "`$b = [IO.File]::ReadAllBytes('x'); `$h = (Get-FileHash -InputStream ([IO.MemoryStream]::new(`$b)) -Algorithm SHA256).Hash; `$t = [Text.UTF8Encoding]::new(`$false, `$true).GetString(`$b); `$d = ConvertFrom-Json -InputObject `$t -ErrorAction Stop; `$p = Join-Path ([IO.Path]::GetTempPath()) ('t89-wake-' + [guid]::NewGuid().ToString('N') + '.body'); Write-Host 'https://api.vibhanshu-ai-portfolio.dev/actuator/health'"
    $e = $null
    $okAst = [System.Management.Automation.Language.Parser]::ParseInput($allowed, [ref]$null, [ref]$e)
    if ($e) { throw 'the allowed fixture does not parse' }
    $found = Find-ForbiddenConstruct $okAst
    if ($found) { throw "the guard fires on constructs the wrapper legitimately uses: $found" }
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
        'WakeProbeIntervalSeconds:String'
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

# --- Windows PowerShell 5.1 gate ---------------------------------------------
Check 'the PowerShell 5.1 gate runs before any child process, checks both PSEdition and major version, and Fails with exit 2' {
    $pattern = "`$PSVersionTable.PSEdition -ne 'Desktop' -or `$PSVersionTable.PSVersion.Major -ne 5"
    $guards = @(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.IfStatementAst] -and (Norm $n.Clauses[0].Item1.Extent.Text) -ceq $pattern }.GetNewClosure())
    if ($guards.Count -ne 1) { throw "expected exactly one PowerShell-edition gate reading exactly '$pattern', found $($guards.Count)" }
    $g = $guards[0]
    if ($g.Clauses.Count -ne 1 -or $null -ne $g.ElseClause) { throw 'the PowerShell-edition gate has extra clauses' }
    $last = @($g.Clauses[0].Item2.Statements)[-1]
    $failCall = if ($last -is [System.Management.Automation.Language.PipelineAst]) { @($last.PipelineElements)[0] } else { $null }
    if (-not ($failCall -is [System.Management.Automation.Language.CommandAst]) -or $failCall.GetCommandName() -ne 'Fail' -or (Norm @($failCall.CommandElements)[-1].Extent.Text) -ne '2') {
        throw 'the PowerShell-edition gate must Fail with exit 2'
    }
    if (@($childCalls | Where-Object { $_.Extent.StartOffset -lt $g.Extent.StartOffset -and -not (Inside-Unit $_) }).Count) {
        throw 'a child process can run before the PowerShell-edition gate'
    }
    if ($g.Extent.StartOffset -gt $script:unit.Extent.StartOffset) { throw 'the PowerShell-edition gate must precede the safety unit' }
    if ($wrapperAst.Extent.Text -match '(?im)^\s*#Requires\s+-Version') {
        throw '#Requires -Version must not be used: PowerShell 7 satisfies a 5.1 minimum and would pass the very gate it exists to block'
    }
}

Write-Host 'PowerShell-edition gate (behavioral; only runs if a Core-edition pwsh is installed on this machine)'
$pwshCmd = Get-Command pwsh -ErrorAction SilentlyContinue
if ($pwshCmd) {
    $peCapture = Join-Path ([IO.Path]::GetTempPath()) "t89-pwsh-capture-$([guid]::NewGuid()).txt"
    $peEvidence = Join-Path ([IO.Path]::GetTempPath()) "t89-pwsh-evidence-$([guid]::NewGuid()).json"
    [Environment]::SetEnvironmentVariable('STUB_CAPTURE', $peCapture)
    Push-Location -LiteralPath $repo
    try {
        $peOut = & $pwshCmd.Source -NoProfile -File $script -EvidenceOutput $peEvidence `
            -AzCommand (Join-Path $stubs 'stub_az.cmd') -DockerCommand (Join-Path $stubs 'stub_docker.cmd') `
            -CurlCommand (Join-Path $stubs 'stub_curl.cmd') -PythonCommand (Join-Path $stubs 'stub_python.cmd') `
            -ReplicaWaitSeconds 2 -ReplicaPollSeconds 1 -WakeProbeIntervalSeconds 0 2>&1 | Out-String
        $peCode = $LASTEXITCODE
    } finally { Pop-Location }
    $peCap = if (Test-Path $peCapture) { Get-Content $peCapture -Raw } else { '' }
    Remove-Item $peCapture, $peEvidence -ErrorAction SilentlyContinue
    [Environment]::SetEnvironmentVariable('STUB_CAPTURE', $null)
    Check 'running under PowerShell 7 (Core edition) is refused with exit 2 before any child process' {
        if ($peCode -ne 2) { throw "exit $peCode (expected 2); output: $peOut" }
        if ($peOut -notmatch 'requires Windows PowerShell 5\.1') { throw "did not name the requirement:`n$peOut" }
        if ($peCap -match '(?m)^(az|docker|python|curl) ') { throw "a child process ran under PowerShell 7:`n$peCap" }
    }
} else {
    Write-Host "  (skipped: no pwsh found on PATH in this environment; the AST test above still pins the gate's structure)"
}

# --- Live-run interval enforcement -------------------------------------------
# The AST pin for the guard's exact shape lives with the other
# WakeProbeIntervalSeconds AST checks above; these are its behavioral proof.
Write-Host 'Live-run interval enforcement'
$liveRig = New-ShadowPathRig 'live'
try {
    # Every command left at its literal default (so $isLiveRun is true), but
    # shadowed on PATH with stubs first: a bug in the guard below must not be
    # able to reach a real az/docker/python. Two independent nets on top of
    # that, in case the interval guard itself regresses and lets execution
    # fall through to the pre-wake checks:
    #   1. STUB_AZ_FAIL_MATCH makes the very FIRST az call in the wrapper
    #      ("Checking Azure session", --query name) fail in the same
    #      controlled, already-proven way Invoke-LiveEvidenceCase below relies
    #      on -- so a regressed guard still stops at "no authenticated az
    #      session" long before Invoke-AuthorizedWake, and this Check's own
    #      assertions (which require exit 2 with the INTERVAL message, and no
    #      child process at all) then fail loudly instead of silently letting
    #      a real wake through.
    #   2. curl.exe is ALSO shadowed, with a placeholder that is not a valid
    #      executable: if it is ever invoked, Windows refuses to launch it and
    #      no network I/O occurs. This is belt-and-braces underneath net 1 --
    #      it does not depend on az failing first -- not a substitute for it.
    $r = Invoke-ShadowedPotentiallyLiveCase $liveRig @('-WakeProbeIntervalSeconds', '10')
    Check 'a live configuration (every command at its default) with a non-5 interval fails closed before any child process' {
        if ($r.Exit -ne 2) { throw "exit $($r.Exit) (expected 2); output: $($r.Output)" }
        if ($r.Output -notmatch 'must be omitted or set to exactly 5 for a live run') { throw "did not name the live-interval rule:`n$($r.Output)" }
        if ($r.Capture -match '(?m)^(az|docker|python|curl) ') { throw "a child process ran before the live-interval guard:`n$($r.Capture)" }
    }

    $r = Invoke-ShadowedPotentiallyLiveCase $liveRig @('-AzCommand', (Join-Path $stubs 'stub_az.cmd'), '-DockerCommand', (Join-Path $stubs 'stub_docker.cmd'), '-PythonCommand', (Join-Path $stubs 'stub_python.cmd'), '-WakeProbeIntervalSeconds', '10')
    Check 'a partially overridden configuration with real curl.exe remains live and refuses a non-5 interval before any child process' {
        if ($r.Exit -ne 2) { throw "exit $($r.Exit) (expected 2); output: $($r.Output)" }
        if ($r.Output -notmatch 'must be omitted or set to exactly 5 for a live run') { throw "did not name the live-interval rule:`n$($r.Output)" }
        if ($r.Capture -match '(?m)^(az|docker|python|curl) ') { throw "a child process ran before the partial-override live guard:`n$($r.Capture)" }
    }
} finally {
    Remove-Item $liveRig.Root -Recurse -Force -ErrorAction SilentlyContinue
}
$r = Invoke-Wrapper -Env $good.Clone()
Check 'a non-live (stub) configuration with interval 0 is unaffected by the live-interval guard (as every other test in this suite already relies on)' {
    if ($r.Exit -ne 0) { throw "exit $($r.Exit); output: $($r.Output)" }
}

# --- Evidence-path externality (live runs only) ------------------------------
Check 'the overwrite refusal is preserved unchanged, and a live run enforces evidence-path externality after it, before any child process' {
    $overwrite = @(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'Fail' -and (Norm $n.Extent.Text) -cmatch 'already exists, refusing to overwrite' })
    if ($overwrite.Count -ne 1) { throw "expected exactly one overwrite-refusal Fail call, found $($overwrite.Count)" }
    if ((Norm $overwrite[0].Extent.Text) -cne 'Fail "evidence output already exists, refusing to overwrite: $EvidenceOutput" 2') {
        throw "the overwrite refusal changed: $((Norm $overwrite[0].Extent.Text))"
    }
    $liveIfs = @(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.IfStatementAst] -and (Norm $n.Clauses[0].Item1.Extent.Text) -ceq '$isLiveRun' })
    if ($liveIfs.Count -ne 1) { throw "expected exactly one 'if (`$isLiveRun)' block (the evidence-path check), found $($liveIfs.Count)" }
    $b = $liveIfs[0]
    if ($b.Extent.StartOffset -lt $overwrite[0].Extent.EndOffset) { throw 'the evidence-path check must come after the overwrite refusal' }
    if (@($childCalls | Where-Object { $_.Extent.StartOffset -lt $b.Extent.StartOffset -and -not (Inside-Unit $_) }).Count) {
        throw 'a child process can run before the evidence-path check'
    }
    $bodyText = $b.Extent.Text
    if ($bodyText -notmatch '\[IO\.Path\]::GetFullPath') { throw 'the evidence-path check does not canonicalize absolute paths with [IO.Path]::GetFullPath' }
    if ($bodyText -notmatch '\$PWD\.ProviderPath') { throw 'the evidence-path check does not resolve relative paths against $PWD.ProviderPath before canonicalizing them' }
    if ($bodyText -match '\$(?:ExecutionContext|PSCmdlet)\.SessionState\.Path') { throw 'the evidence-path check must not depend on a forbidden session object' }
    if ($bodyText -notmatch '\$PSScriptRoot') { throw 'the evidence-path check does not derive the repo root from $PSScriptRoot' }
    if ($bodyText -notmatch 'OrdinalIgnoreCase') { throw 'the evidence-path check is not case-insensitive' }
    if ($bodyText -notmatch 'DirectorySeparatorChar') { throw 'the evidence-path check does not append a trailing separator before comparing (the prefix-sibling guard)' }
    $liveFails = @(Find-Ast $b { param($n) $n -is [System.Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'Fail' })
    if ($liveFails.Count -ne 1) { throw "the evidence-path check must have exactly one refusal path, found $($liveFails.Count)" }
    if ((Norm @($liveFails[0].CommandElements)[-1].Extent.Text) -ne '2') { throw 'the evidence-path refusal must Fail with exit 2' }
    # The ambiguous/prefixed-spelling refusal is a separate, live-only guard
    # that must run BEFORE the overwrite refusal (so 'already exists' cannot
    # mask it) and must cover drive-relative, root-relative and any
    # two-separator (UNC / \\?\ / \\.\) spelling.
    $spellingPattern = '$isLiveRun -and ($isDriveRelative -or $isRootRelative -or $isDoubleSeparator)'
    $spelling = @(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.IfStatementAst] -and (Norm $n.Clauses[0].Item1.Extent.Text) -ceq $spellingPattern }.GetNewClosure())
    if ($spelling.Count -ne 1) { throw "expected exactly one ambiguous-spelling guard reading exactly '$spellingPattern', found $($spelling.Count)" }
    $s = $spelling[0]
    if ($s.Extent.EndOffset -gt $overwrite[0].Extent.StartOffset) { throw 'the ambiguous-spelling guard must precede the overwrite refusal' }
    if (@($childCalls | Where-Object { $_.Extent.StartOffset -lt $s.Extent.StartOffset -and -not (Inside-Unit $_) }).Count) {
        throw 'a child process can run before the ambiguous-spelling guard'
    }
    $spellingFails = @(Find-Ast $s { param($n) $n -is [System.Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'Fail' })
    if ($spellingFails.Count -ne 1 -or (Norm @($spellingFails[0].CommandElements)[-1].Extent.Text) -ne '2') { throw 'the ambiguous-spelling guard must Fail once with exit 2' }
    if ($s.Extent.Text -notmatch 'drive-relative and root-relative Windows spellings are refused') { throw 'the ambiguous-spelling guard does not explicitly refuse ambiguous Windows path forms' }
    $dblAsg = @(Assignments-To $wrapperAst 'isDoubleSeparator')
    if ($dblAsg.Count -ne 1 -or (Norm $dblAsg[0].Right.Extent.Text) -cne "[regex]::IsMatch(`$EvidenceOutput, '\A[\\/]{2}')") { throw "`$isDoubleSeparator must be assigned exactly once from the two-separator regex; found $($dblAsg.Count)" }
    if (@(Find-Ast $wrapperAst { param($n) $n -is [System.Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'git' }).Count) { throw 'the wrapper must not shell out to git' }
}

Write-Host 'Evidence-path externality (behavioral): in-repo rejected, prefix-sibling accepted, traversal both ways'
$evidenceRig = New-ShadowPathRig 'evpath'
function Invoke-LiveEvidenceCase {
    # A potentially "live" invocation (all command parameters at their literal
    # defaults, shadowed by the shared PATH rig) whose FIRST az call is made to
    # fail in a controlled, already-proven way (see the 'Each pre-wake child
    # failure' phases above). A path that passes the boundary check below
    # therefore proceeds to that known, safe stopping point -- never reaching
    # curl -- rather than this test needing a live wake to succeed.
    param([string]$EvidenceOutput, [string[]]$Extra = @())
    Invoke-ShadowedPotentiallyLiveCase $evidenceRig (@('-WakeProbeIntervalSeconds', '5', '-EvidenceOutput', $EvidenceOutput) + $Extra)
}
$repoLeaf = Split-Path $repo -Leaf
$siblingDir = "$repo-backup-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $siblingDir -Force | Out-Null
try {
    $insideAbs = Join-Path $repo "docs/evidence/b2-task-8-9/t89-boundary-inside-$([guid]::NewGuid().ToString('N')).json"
    $r = Invoke-LiveEvidenceCase -EvidenceOutput $insideAbs
    Check 'a live run with -EvidenceOutput inside the repository is rejected before any child process' {
        if ($r.Exit -ne 2) { throw "exit $($r.Exit) (expected 2); output: $($r.Output)" }
        if ($r.Output -notmatch 'must resolve outside the repository') { throw "did not name the boundary rule:`n$($r.Output)" }
        if ($r.Capture -match '(?m)^(az|docker|python|curl) ') { throw "a child process ran before the evidence-path guard:`n$($r.Capture)" }
    }

    # An ordinary relative path resolves against the wrapper's current
    # filesystem location and must be rejected when that resolution is inside
    # the repository. The traversal case below exercises the same branch with
    # dot segments; the ambiguous drive/root-relative spellings have their own
    # explicit refusal checks because they do not have ordinary relative-path
    # semantics on Windows PowerShell 5.1.
    $insideRelative = "docs/evidence/b2-task-8-9/t89-boundary-relative-$([guid]::NewGuid().ToString('N')).json"
    $r = Invoke-LiveEvidenceCase -EvidenceOutput $insideRelative
    Check 'a live run with a RELATIVE -EvidenceOutput that resolves inside the repository is rejected before any child process' {
        if ($r.Exit -ne 2) { throw "exit $($r.Exit) (expected 2); output: $($r.Output)" }
        if ($r.Output -notmatch 'must resolve outside the repository') { throw "did not name the boundary rule:`n$($r.Output)" }
        if ($r.Capture -match '(?m)^(az|docker|python|curl) ') { throw "a child process ran before the evidence-path guard:`n$($r.Capture)" }
    }

    # The two-separator forms are the ones [IO.Path]::GetFullPath keeps intact
    # on .NET Framework: an extended-length (\\?\) or device (\\.\) prefix in
    # front of an IN-REPO path would otherwise pass the StartsWith comparison,
    # and a plain UNC path is refused with them rather than special-cased.
    foreach ($ambiguous in @(
        "C:t89-drive-relative-$([guid]::NewGuid().ToString('N')).json",
        "\t89-root-relative-$([guid]::NewGuid().ToString('N')).json",
        ('\\?\' + (Join-Path $repo "docs\evidence\b2-task-8-9\t89-boundary-extended-$([guid]::NewGuid().ToString('N')).json")),
        ('\\.\' + (Join-Path $repo "docs\evidence\b2-task-8-9\t89-boundary-device-$([guid]::NewGuid().ToString('N')).json")),
        "\\\docs\evidence\b2-task-8-9\t89-boundary-triple-$([guid]::NewGuid().ToString('N')).json",
        "\\srv\share\t89-boundary-unc-$([guid]::NewGuid().ToString('N')).json"
    )) {
        $r = Invoke-LiveEvidenceCase -EvidenceOutput $ambiguous
        Check "a live run rejects ambiguous or prefixed Windows evidence path spelling '$ambiguous' before any child process" {
            if ($r.Exit -ne 2) { throw "exit $($r.Exit) (expected 2); output: $($r.Output)" }
            if ($r.Output -notmatch 'drive-relative and root-relative Windows spellings are refused') { throw "did not name the ambiguous-path rule:`n$($r.Output)" }
            if ($r.Output -match 'already exists, refusing to overwrite') { throw "the overwrite refusal masked the ambiguous-path rule:`n$($r.Output)" }
            if ($r.Capture -match '(?m)^(az|docker|python|curl) ') { throw "a child process ran before the ambiguous-path guard:`n$($r.Capture)" }
        }
    }

    $siblingPath = Join-Path $siblingDir 't89-sibling-evidence.json'
    $r = Invoke-LiveEvidenceCase -EvidenceOutput $siblingPath
    Check 'a prefix-sibling directory (<repo>-backup-..., next to <repo>) is NOT falsely rejected as inside the repository' {
        if ($r.Output -match 'must resolve outside the repository') { throw "a prefix-sibling path was wrongly rejected as in-repo:`n$($r.Output)" }
        # It must instead proceed past the boundary check to the (controlled,
        # deliberately failing) Azure session check -- proving it passed the
        # gate rather than merely happening to fail for an unrelated reason.
        if ($r.Output -notmatch 'no authenticated az session') { throw "did not proceed to the az session check:`n$($r.Output)" }
        if ($r.Exit -ne 2) { throw "exit $($r.Exit) (expected 2, from the controlled az failure); output: $($r.Output)" }
    }

    $traversalInside = "..\$repoLeaf\docs\evidence\b2-task-8-9\t89-boundary-traversal-$([guid]::NewGuid().ToString('N')).json"
    $r = Invoke-LiveEvidenceCase -EvidenceOutput $traversalInside
    Check 'relative traversal that resolves back inside the repository is rejected, not bypassed' {
        if ($r.Exit -ne 2) { throw "exit $($r.Exit) (expected 2); output: $($r.Output)" }
        if ($r.Output -notmatch 'must resolve outside the repository') { throw "traversal into the repository was not rejected:`n$($r.Output)" }
        if ($r.Capture -match '(?m)^(az|docker|python|curl) ') { throw "a child process ran before the evidence-path guard:`n$($r.Capture)" }
    }

    $traversalOutside = "..\..\t89-outside-$([guid]::NewGuid().ToString('N')).json"
    $r = Invoke-LiveEvidenceCase -EvidenceOutput $traversalOutside
    Check 'relative traversal that resolves outside the repository is accepted' {
        if ($r.Output -match 'must resolve outside the repository') { throw "an outward traversal was wrongly rejected as in-repo:`n$($r.Output)" }
        if ($r.Output -notmatch 'no authenticated az session') { throw "did not proceed to the az session check:`n$($r.Output)" }
    }
} finally {
    Remove-Item $evidenceRig.Root, $siblingDir -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host 'Evidence-output overwrite refusal is preserved'
$existingEvidence = Join-Path ([IO.Path]::GetTempPath()) "t89-existing-evidence-$([guid]::NewGuid()).json"
'{}' | Set-Content -LiteralPath $existingEvidence
try {
    $r = Invoke-Wrapper -Env $good.Clone() -Extra @('-EvidenceOutput', $existingEvidence)
    Check 'an existing -EvidenceOutput file is never overwritten' {
        if ($r.Exit -ne 2) { throw "exit $($r.Exit) (expected 2); output: $($r.Output)" }
        if ($r.Output -notmatch 'already exists, refusing to overwrite') { throw "overwrite refusal not reported:`n$($r.Output)" }
        if ($r.Capture -match '(?m)^curl ') { throw 'wake issued despite an existing evidence file' }
    }
} finally {
    Remove-Item $existingEvidence -ErrorAction SilentlyContinue
}

Write-Host 'Probe transport failure after delivery'
$r = Invoke-Wrapper -Env (Probe-Env $good @(@{ EXIT = '52'; STATUS = '000'; BODY = 'none' }, @{ STATUS = '200'; SENTINEL = '1' }))
Check 'does not claim the probe was unconsumed, exits 3, runs nothing further' {
    if ($r.Output -match 'probably NOT consumed') { throw 'claimed unconsumed for a post-delivery curl exit' }
    if ($r.Output -notmatch 'may be consumed') { throw "did not flag possible consumption:`n$($r.Output)" }
    if ($r.Output -notmatch 'probe 1/6 failed in transport \(curl exit 52\)') { throw "did not name the probe:`n$($r.Output)" }
    if ($r.Output -match 'earlier probe') { throw 'claimed earlier probes on the first probe' }
    if ($r.Exit -ne 3) { throw "exit $($r.Exit)" }
    Assert-ProbeCount $r 1
    Assert-NoVerifier $r
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
Check 'does not re-issue the wake after a 200' {
    $n = ([regex]::Matches($r.Capture, '(?m)^curl ')).Count
    if ($n -ne 1) { throw "probes issued $n times" }
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

Write-Host 'Probe transport failure before delivery (DNS)'
$r = Invoke-Wrapper -Env (Probe-Env $good @(@{ EXIT = '6'; STATUS = '000'; BODY = 'none' }, @{ STATUS = '200'; SENTINEL = '1' }))
Check 'reports the probe as probably NOT consumed, exits 3, runs nothing further' {
    if ($r.Output -notmatch 'probably NOT consumed') { throw "output did not flag an unconsumed probe: $($r.Output)" }
    if ($r.Output -match 'earlier probe') { throw 'claimed earlier probes on the first probe' }
    if ($r.Exit -ne 3) { throw "exit $($r.Exit)" }
    Assert-ProbeCount $r 1
    Assert-NoVerifier $r
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

# --- Serving Azure demo-reset timeout precondition (boundary M2) -----------
# scripts/run_task_8_9_preflight.ps1 ~L671-941: before any probe, the wrapper
# reads the serving revision's container env (a control-plane read, zero
# replicas needed) and compares APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT /
# RESET_TIMEOUT / OVERALL_TIMEOUT, plus the gateway response ceiling
# SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT, against its
# own 120s/30s/165s/150s literals -- all four with the SAME absent-is-a-
# mismatch rule. It then separately checks APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD
# (must be 30m if present; absent is a PASS) and CLOUD_PROVIDER (must be
# 'azure' if present; absent is a PASS). The stub's "containers[0].env"
# branch defaults to that same ratified payload (STUB_SERVING_ENV_JSON
# overrides it), so $good alone already exercises the match; every case below
# drives a specific mismatch shape.
Write-Host 'Serving Azure demo-reset timeout precondition (M2): match proceeds'
$m2RatifiedJson = '[{"name":"APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD","value":"30m"},{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"},{"name":"SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT","value":"150s"}]'
$m2E = $good.Clone(); $m2E['STUB_SERVING_ENV_JSON'] = $m2RatifiedJson
$r = Invoke-Wrapper -Env $m2E
Check 'a serving env matching the ratified attestation proceeds past the check and reaches the wake' {
    if ($r.Exit -ne 0) { throw "exit $($r.Exit) (expected 0); output: $($r.Output)" }
    Assert-ProbeCount $r 1
}
Check 'the match emits exactly one canonical, allowlisted (names-only, no values) observation line, now including the response ceiling' {
    $lines = @($r.Output -split "`r?`n" | Where-Object { $_ -match 'serving Azure demo-reset timeouts match the ratified attestation' })
    if ($lines.Count -ne 1) { throw "expected exactly one canonical observation line, found $($lines.Count); output:`n$($r.Output)" }
    $want = 'serving Azure demo-reset timeouts match the ratified attestation (APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT, APP_DEMO_LOGIN_RESET_RESET_TIMEOUT, APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT, SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT)'
    if (-not $lines[0].Contains($want)) { throw "canonical line reads:`n  $($lines[0])`nexpected to contain:`n  $want" }
    # Allowlisted means NAMES only -- the line must not also carry the values
    # it just approved, which would make it a second, uncontrolled place a
    # future edit could leak an observed value from.
    if ($lines[0] -match '\d') { throw "the canonical observation line carries a digit -- it must name checks, not values:`n  $($lines[0])" }
}
Check 'idle threshold present-and-30m and CLOUD_PROVIDER absent both proceed, and the second canonical line confirms both were checked' {
    if ($r.Exit -ne 0) { throw "exit $($r.Exit) (expected 0); output: $($r.Output)" }
    $lines = @($r.Output -split "`r?`n" | Where-Object { $_ -match 'APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD and CLOUD_PROVIDER are absent-safe or match their approved values' })
    if ($lines.Count -ne 1) { throw "expected exactly one idle-threshold/cloud-provider observation line, found $($lines.Count); output:`n$($r.Output)" }
}
Check 'each env row is matched by its own name, not the whole decoded list as one row' {
    # THE discriminating test for the ConvertFrom-Json double-wrap on THIS
    # path, mirroring the replica-list discriminator further down ('selects
    # exactly one replica name, not the whole decoded list'). Exit code
    # alone is not quite enough to design this fixture around by accident,
    # so it is pinned directly: $m2RatifiedJson above has five distinct
    # rows. A double-wrapped decode makes $envRows a one-element array whose
    # single element is the whole five-row array, so the loop in the wrapper
    # runs once with that whole array bound to $row; $row.name and
    # $row.value then member-enumerate across all five rows instead of
    # reading one row at a time, producing an array of five names (never
    # equal to any single expected name string) as the only hashtable key.
    # Every ContainsKey($name) lookup for the four expected timeout names
    # then misses, so a double-wrapped decode reports ALL FOUR as absent --
    # on this exact fixture, which is a byte-for-byte match. A correct
    # decode-first-then-wrap stores one string-keyed entry per row and finds
    # all four, so this fixture's correct-parse output (exit 0, no "absent"
    # text) and its double-wrapped output (exit 2, four "absent" lines)
    # cannot be confused for one another.
    if ($r.Exit -ne 0) { throw "exit $($r.Exit) (expected 0 -- a double-wrapped decode reports every timeout absent even on a match); output: $($r.Output)" }
    if ($r.Output -match 'is absent from the serving revision') { throw "a matching multi-row env was reported as having absent timeouts (the decode is double-wrapped):`n$($r.Output)" }
}

Write-Host 'Serving Azure demo-reset timeout precondition (M2): every failure mode exits 2, before curl, before evidence'
# Every case below asserts the same three things the rest of this suite's
# phase tables assert (exit 2, no curl, no verifier) plus a fourth this
# boundary specifically exists to guarantee: no verifier evidence file is
# ever created on any of these paths (Invoke-Wrapper's own auto-generated
# path is overridden per-case below so its absence can be asserted directly,
# rather than relying on the shared table helpers, which do not expose it).
$m2MismatchEligibility = '[{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"999s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"}]'
$m2MismatchReset = '[{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"999s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"}]'
$m2MismatchOverall = '[{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"999s"}]'
$m2MissingEligibility = '[{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"}]'
$m2MissingReset = '[{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"}]'
$m2MissingOverall = '[{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"}]'
# Gap 1 (response ceiling): folded into the SAME expected-name loop as the
# three timeouts above, so it gets the SAME absent-is-a-mismatch rule.
$m2CeilingWrong = '[{"name":"APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD","value":"30m"},{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"},{"name":"SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT","value":"999s"}]'
# M-1: PowerShell's -ne is case-INsensitive by default -- '150S' -ne '150s'
# is FALSE, i.e. a case-differing value reads as a match. The verifier
# compares with Python's != (case-sensitive) and `_duration_seconds` only
# matches a lowercase unit suffix, so '150S' fails post-wake. Against -ne
# this fixture would proceed past the gate (exit 0); with -cne it must
# reject here, before the wake is spent. Same-length, different case only --
# not a value this loop's -notlike/length-based sanitizer would touch.
$m2CeilingMixedCase = '[{"name":"APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD","value":"30m"},{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"},{"name":"SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT","value":"150S"}]'
# Ceiling entirely absent, everything else ratified. This is deliberately the
# EXACT shape that passed this boundary before Gap 1 closed (three timeouts +
# idle threshold, no ceiling row) -- the case the background note calls out as
# most likely to be implemented as a pass by mistake, so it is pinned as its
# own fixture rather than only appearing incidentally inside another case.
$m2CeilingAbsent = '[{"name":"APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD","value":"30m"},{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"}]'
# Gap 2a (idle threshold): NOT the same rule -- present-and-wrong is a reject,
# but (see the proceeds cases further below) absent is a pass.
$m2IdleWrong = '[{"name":"APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD","value":"5m"},{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"},{"name":"SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT","value":"150s"}]'
# M-1: same case-sensitivity gap as the ceiling above, for the idle
# threshold's own -ne comparison. '30M' -ne '30m' is FALSE under -ne (reads
# as a match) but the verifier's _duration_seconds only accepts a lowercase
# 'm' suffix, so '30M' fails post-wake. Must reject here under -cne.
$m2IdleMixedCase = '[{"name":"APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD","value":"30M"},{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"},{"name":"SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT","value":"150s"}]'
$m2IdleAbsent = '[{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"},{"name":"SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT","value":"150s"}]'
# Gap 2b (CLOUD_PROVIDER): also NOT the same rule as the four-name loop --
# present-and-wrong is a reject, absent is a pass (proceeds cases below).
$m2ProviderWrong = '[{"name":"APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD","value":"30m"},{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"},{"name":"SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT","value":"150s"},{"name":"CLOUD_PROVIDER","value":"aws"}]'
# M-1: same case-sensitivity gap, for CLOUD_PROVIDER's own -ne comparison.
# 'Azure' -ne 'azure' is FALSE under -ne (reads as a match) but the
# verifier's names.get("CLOUD_PROVIDER", "azure") == "azure" check in
# scripts/verify_demo_reset_azure.py is a case-sensitive Python ==, so
# 'Azure' fails post-wake. Must reject here under -cne.
$m2ProviderMixedCase = '[{"name":"APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD","value":"30m"},{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"},{"name":"SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT","value":"150s"},{"name":"CLOUD_PROVIDER","value":"Azure"}]'
$m2ProviderAzure = '[{"name":"APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD","value":"30m"},{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"},{"name":"SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT","value":"150s"},{"name":"CLOUD_PROVIDER","value":"azure"}]'
# Truncated mid-object: unbalanced brackets are an unambiguous parse failure
# (unexpected end of input) regardless of JSON parser leniency, unlike a
# missing colon which some lenient parsers might still tolerate.
$m2MalformedJson = '[{"name":"MALFORMED-MARKER-DO-NOT-LEAK","value":"120s"'
$m2SecretRefJson = '[{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","secretRef":"kv-prod-root-password"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"}]'
# The verifier (scripts/verify_demo_reset_azure.py) raises on a duplicated
# serving environment name rather than keeping the first copy. Here the
# FIRST copy of the eligibility timeout is correct (120s); a wrapper that
# silently kept it would pass this boundary and only fail the verifier
# post-wake -- the exact wake-costing class this boundary exists to remove.
$m2DuplicateNameJson = '[{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"},{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"999s"}]'
# Hostile fixtures for the console-injection property below: each is a
# genuine mismatch (so it exercises the one branch that interpolates the
# observed value at all -- see run_task_8_9_preflight.ps1 ~L827-876) carrying
# a payload an attacker who controls the Container App's env could plant.
# Both fixtures build their JSON-string escape sequences at runtime from
# [char]92 (a backslash) concatenated with plain letters/digits, rather
# than typing a backslash-escape token directly in this source: a
# single-quoted PowerShell string would not interpret such a token here
# either way, but building it from parts sidesteps any authoring tool
# along the way that might decode it before it reaches the wrapper under
# test. ConvertFrom-Json is the thing that is SUPPOSED to decode each
# escape -- into a real line feed, a real ESC byte, and a real bidi
# override character, respectively -- once the wrapper reads this payload
# back from the (stubbed) az call.
$m2JsonEscN = [string][char]92 + 'n'
$m2HostileNewlineJson = '[{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s' + $m2JsonEscN + 'INJECTED-VIA-NEWLINE-MARKER"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"}]'
$m2JsonEscEsc = [string][char]92 + 'u001b'
# U+202E (RIGHT-TO-LEFT OVERRIDE) is a Unicode format character (category
# Cf), not a C0/C1 control byte, so it falls outside the old
# [\x00-\x1F\x7F-\x9F] sanitizer range and inside the current printable-ASCII
# allowlist ([^\x20-\x7E], run_task_8_9_preflight.ps1 ~L779/866/900/925). It
# could visually reorder the marker that follows it in a rendered terminal.
$m2JsonEscBidi = [string][char]92 + 'u202e'
$m2HostileBidiJson = '[{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s' + $m2JsonEscBidi + 'INJECTED-VIA-BIDI-MARKER"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"}]'
$m2HostileAnsiJson = '[{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"45s' + $m2JsonEscEsc + '[31mFAKE-ALERT' + $m2JsonEscEsc + '[0m"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"}]'
# Same hostile-value property, exercised under each of the three NEW names
# (Gap 1's ceiling, and Gap 2's idle threshold / CLOUD_PROVIDER), each built
# from the same runtime bidi escape as $m2HostileBidiJson above. Every other
# field in each fixture matches so only the field under test takes the
# mismatch branch that interpolates -- and therefore must sanitize -- an
# observed value.
$m2HostileCeilingMarker = 'INJECTED-VIA-CEILING-BIDI-MARKER'
$m2HostileCeilingJson = '[{"name":"APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD","value":"30m"},{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"},{"name":"SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT","value":"150s' + $m2JsonEscBidi + $m2HostileCeilingMarker + '"}]'
$m2HostileIdleMarker = 'INJECTED-VIA-IDLE-BIDI-MARKER'
$m2HostileIdleJson = '[{"name":"APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD","value":"30m' + $m2JsonEscBidi + $m2HostileIdleMarker + '"},{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"},{"name":"SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT","value":"150s"}]'
$m2HostileProviderMarker = 'INJECTED-VIA-PROVIDER-BIDI-MARKER'
$m2HostileProviderJson = '[{"name":"APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD","value":"30m"},{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"},{"name":"SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT","value":"150s"},{"name":"CLOUD_PROVIDER","value":"azure' + $m2JsonEscBidi + $m2HostileProviderMarker + '"}]'

$m2LongMarker = 'PWNED-' + ('Q' * 3000) + '-MARKEREND'
$m2HostileLongJson = '[{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"' + $m2LongMarker + '"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"}]'

# Hostile-NAME fixture for the duplicate-name Fail path itself (distinct from
# $m2HostileBidiJson above, which puts the bidi override in a VALUE that
# mismatches -- this one puts it in the NAME that is duplicated). Both rows
# decode to the byte-identical hostile name, built from the same runtime
# $m2JsonEscBidi escape sequence, so the second occurrence still hits
# ContainsKey and reaches Fail; that message must sanitize the name with the
# exact rule $observedValue gets above, never echo it raw.
$m2DuplicateNameHostileMarker = 'INJECTED-VIA-DUPNAME-BIDI-MARKER'
$m2HostileDuplicateNameJson = '[{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT' + $m2JsonEscBidi + $m2DuplicateNameHostileMarker + '","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"},{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT' + $m2JsonEscBidi + $m2DuplicateNameHostileMarker + '","value":"999s"}]'

# Key-side M-1 (2026-09-14 review round, gap the value-side -cne fix missed):
# PowerShell's @{} hashtable literal is case-INsensitive on KEYS, independent
# of the -cne fix on the VALUE side above. Both fixtures below carry the
# CORRECT value under a WRONGLY-CASED name, with no correctly-cased row
# alongside it, so the only thing that can make the wrapper reject is the key
# comparer, not the value comparison. Neither is vacuous: replayed against
# 11e64ad (the case-insensitive @{} literal) both fixtures reach exit 0 --
# ContainsKey matches the wrongly-cased name case-insensitively, and the
# (identical) value then compares equal under -cne too -- and only reject
# (exit 2) once the wrapper's hashtable is constructed with
# [System.StringComparer]::Ordinal.
$m2NameLowercaseEligibility = '[{"name":"APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD","value":"30m"},{"name":"app_demo_login_reset_eligibility_timeout","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"},{"name":"SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT","value":"150s"}]'
$m2NameMixedCaseCeiling = '[{"name":"APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD","value":"30m"},{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"},{"name":"Spring_Cloud_Gateway_Server_Webflux_Httpclient_Responsetimeout","value":"150s"}]'

# Ordinal-vs-linguistic M-1 (2026-09-14 review round, the gap the -cne fix
# above did not close): -cne is case-sensitive, but PowerShell's -cne is
# still a LINGUISTIC (InvariantCulture) comparison, not an ordinal one, so a
# ratified value with an otherwise-invisible IGNORABLE character appended
# compares EQUAL under -cne to the bare ratified value -- a false pass here
# that the verifier's ordinal Python != then rejects post-wake. The owner
# reproduced this on real Windows PowerShell 5.1 Desktop: U+00AD (SOFT
# HYPHEN) and U+FEFF (BOM / ZERO WIDTH NO-BREAK SPACE) both compare equal to
# the bare string under -cne; only after run_task_8_9_preflight.ps1's fix
# (comparing via [string]::Equals(..., [System.StringComparison]::Ordinal)
# instead of -cne) do these three fixtures exit 2. U+200B (ZERO WIDTH
# SPACE), floated by an earlier review as an example of the same defect, is
# deliberately NOT used here: the owner's reproduction found -cne already
# rejects it, so a fixture built on U+200B would be vacuous for THIS
# property -- it already exits 2 at e57c71e and proves nothing about the
# ordinal-vs-linguistic comparison. (A separate U+200B fixture appears
# further below, as a coverage pin for the sanitizer -- a different property
# -- see that comment.)
#
# These three fixtures are ALSO the regression tests for a second, distinct
# property this round adds: whether the Fail message SANITIZES the
# offending value rather than rendering it raw. That property is what CI
# reported red at f548a80 for the eligibility and CLOUD_PROVIDER fixtures
# below (both U+00AD) even though the wrapper still exited 2 correctly (no
# wake consumed): U+00AD and U+FEFF are both Unicode category Cf per .NET's
# own Unicode data (CharUnicodeInfo reports U+00AD as
# UnicodeCategory.Format), but the .NET Framework REGEX ENGINE's own legacy
# Unicode category table disagrees -- it matches U+00AD as category Pd
# (dash punctuation), not Cf. The four-category sanitizer class in place at
# f548a80 ([\p{Cc}\p{Cf}\p{Zl}\p{Zp}]) therefore did not fire on U+00AD and
# let the raw ratified value (with the trailing soft hyphen) reach the
# transcript; the same run's U+FEFF fixture passed, because that engine's
# table does match U+FEFF as Cf. run_task_8_9_preflight.ps1 now sanitizes
# against a printable-ASCII allowlist ([^\x20-\x7E],
# ~L866/900/925) instead of a Unicode-category list, so all three fixtures
# below must show the fixed-shape "sanitized rendering, not the literal
# value" placeholder, never the raw ratified value with the character
# appended -- the raw character itself belongs in ForbiddenSubstrings, not
# ExpectSubstrings. Only the two U+00AD fixtures are non-vacuous regression
# evidence for the allowlist (this property failed at f548a80 and passes
# now); the U+FEFF fixture is a coverage pin that already held under the
# old four-category class and continues to hold under the allowlist.
$m2JsonEscSoftHyphen = [string][char]92 + 'u00ad'
$m2JsonEscBom = [string][char]92 + 'ufeff'
# Site 1 (the four-name loop, ~L841): APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT
# carries the ratified '120s' with a trailing U+00AD. Every other name in
# the loop (reset, overall, ceiling) plus idle threshold is correct, so only
# the eligibility comparison should ever reach the mismatch list.
$m2IgnorableEligibilityJson = '[{"name":"APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD","value":"30m"},{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s' + $m2JsonEscSoftHyphen + '"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"},{"name":"SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT","value":"150s"}]'
# Site 1 again, same loop, different member: the gateway response ceiling
# carries the ratified '150s' with a trailing U+FEFF. Everything else in
# this fixture is correct.
$m2IgnorableCeilingJson = '[{"name":"APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD","value":"30m"},{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"},{"name":"SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT","value":"150s' + $m2JsonEscBom + '"}]'
# Site 3 (CLOUD_PROVIDER's own comparison, ~L921) -- a site OTHER than the
# four-name loop, per the brief: CLOUD_PROVIDER carries the ratified 'azure'
# with a trailing U+00AD. Everything else in this fixture is correct.
$m2IgnorableProviderJson = '[{"name":"APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD","value":"30m"},{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"},{"name":"SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT","value":"150s"},{"name":"CLOUD_PROVIDER","value":"azure' + $m2JsonEscSoftHyphen + '"}]'
# Sanitizer coverage (this round): these two fixtures are about the
# SANITIZER itself, not the ordinal-vs-linguistic comparison above, and use
# the same runtime JSON-escape-sequence idiom as $m2JsonEscSoftHyphen/
# $m2JsonEscBom.
#   * U+034F (COMBINING GRAPHEME JOINER, category Mn) was never a member of
#     the OLD four-category class ([\p{Cc}\p{Cf}\p{Zl}\p{Zp}]) at all --
#     unlike U+00AD/U+FEFF above, there is no regex-engine-table subtlety
#     here, it is simply outside that class by category. This is therefore
#     also non-vacuous failing-before/passing-after evidence for the
#     allowlist, exercised on the RESET_TIMEOUT member of the four-name loop
#     (~L866) -- a site distinct from the eligibility/ceiling members
#     already covered above.
#   * U+200B (ZERO WIDTH SPACE) is category Cf, so the OLD class already
#     sanitized it: this fixture passes both before and after the allowlist
#     change. It is a coverage pin over the idle threshold's own sanitizer
#     block (~L900) -- a site distinct from the four-name loop -- not
#     evidence that the allowlist changed anything there. As noted above,
#     -cne and ordinal equality both already reject U+200B in the
#     comparison, so this fixture is solely about the sanitizer.
$m2JsonEscCombiningGraphemeJoiner = [string][char]92 + 'u034f'
$m2JsonEscZeroWidthSpace = [string][char]92 + 'u200b'
$m2SanitizerOnlyResetJson = '[{"name":"APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD","value":"30m"},{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s' + $m2JsonEscCombiningGraphemeJoiner + '"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"},{"name":"SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT","value":"150s"}]'
$m2SanitizerOnlyIdleJson = '[{"name":"APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD","value":"30m' + $m2JsonEscZeroWidthSpace + '"},{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"},{"name":"SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT","value":"150s"}]'

$m2Cases = @(
    @{ Name = 'value mismatch (eligibility)'; Env = @{ STUB_SERVING_ENV_JSON = $m2MismatchEligibility }
       # ForbiddenSubstrings here double as a double-wrap discriminator: a
       # double-wrapped decode (see the dedicated Check above) reports EVERY
       # expected name absent, so the two untouched, genuinely-matching
       # names in this fixture must never show up as absent alongside the
       # one that is really mismatched.
       ExpectSubstrings = @('do not match the ratified attestation', 'no wake was consumed', 'APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT', "observed='999s'", "expected='120s'")
       ForbiddenSubstrings = @('APP_DEMO_LOGIN_RESET_RESET_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT is absent') },
    @{ Name = 'value mismatch (reset)'; Env = @{ STUB_SERVING_ENV_JSON = $m2MismatchReset }
       ExpectSubstrings = @('do not match the ratified attestation', 'APP_DEMO_LOGIN_RESET_RESET_TIMEOUT', "observed='999s'", "expected='30s'")
       ForbiddenSubstrings = @('APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT is absent') },
    @{ Name = 'value mismatch (overall)'; Env = @{ STUB_SERVING_ENV_JSON = $m2MismatchOverall }
       ExpectSubstrings = @('do not match the ratified attestation', 'APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT', "observed='999s'", "expected='165s'")
       ForbiddenSubstrings = @('APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_RESET_TIMEOUT is absent') },
    @{ Name = 'name missing entirely (eligibility)'; Env = @{ STUB_SERVING_ENV_JSON = $m2MissingEligibility }
       ExpectSubstrings = @('do not match the ratified attestation', 'is absent from the serving revision', 'APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT', "expected='120s'"); ForbiddenSubstrings = @() },
    @{ Name = 'name missing entirely (reset)'; Env = @{ STUB_SERVING_ENV_JSON = $m2MissingReset }
       ExpectSubstrings = @('do not match the ratified attestation', 'is absent from the serving revision', 'APP_DEMO_LOGIN_RESET_RESET_TIMEOUT', "expected='30s'"); ForbiddenSubstrings = @() },
    @{ Name = 'name missing entirely (overall)'; Env = @{ STUB_SERVING_ENV_JSON = $m2MissingOverall }
       ExpectSubstrings = @('do not match the ratified attestation', 'is absent from the serving revision', 'APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT', "expected='165s'"); ForbiddenSubstrings = @() },
    # --- Gap 1: response ceiling (folded into the loop above; same rule) ----
    @{ Name = 'gap 1: response ceiling wrong value'; Env = @{ STUB_SERVING_ENV_JSON = $m2CeilingWrong }
       ExpectSubstrings = @('do not match the ratified attestation', 'SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT', "observed='999s'", "expected='150s'")
       ForbiddenSubstrings = @('APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_RESET_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT is absent') },
    @{ Name = 'gap 1: response ceiling absent -- the shape that passed before this gap closed, must now reject'; Env = @{ STUB_SERVING_ENV_JSON = $m2CeilingAbsent }
       ExpectSubstrings = @('do not match the ratified attestation', 'is absent from the serving revision', 'SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT', "expected='150s'")
       ForbiddenSubstrings = @('APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_RESET_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT is absent') },
    # M-1: mixed-case ceiling value. Against a case-insensitive -ne this
    # would proceed (a false pass here that fails the verifier post-wake);
    # -cne must reject it.
    @{ Name = 'm-1: response ceiling mixed case (150S) must reject, not pass case-insensitively'; Env = @{ STUB_SERVING_ENV_JSON = $m2CeilingMixedCase }
       ExpectSubstrings = @('do not match the ratified attestation', 'SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT', "observed='150S'", "expected='150s'")
       ForbiddenSubstrings = @('APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_RESET_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT is absent') },
    # --- Gap 2a: idle threshold (absent is a PASS -- see the proceeds checks
    #     further below; only present-and-wrong belongs in this reject table) -
    @{ Name = 'gap 2a: idle threshold present and wrong'; Env = @{ STUB_SERVING_ENV_JSON = $m2IdleWrong }
       ExpectSubstrings = @('do not match the ratified attestation', 'APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD', "observed='5m'", "expected='30m'")
       ForbiddenSubstrings = @('APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_RESET_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT is absent', 'SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT is absent') },
    # M-1: mixed-case idle threshold value -- same false-pass-under-ne shape
    # as the ceiling case above, for the idle threshold's own comparison.
    @{ Name = 'm-1: idle threshold mixed case (30M) must reject, not pass case-insensitively'; Env = @{ STUB_SERVING_ENV_JSON = $m2IdleMixedCase }
       ExpectSubstrings = @('do not match the ratified attestation', 'APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD', "observed='30M'", "expected='30m'")
       ForbiddenSubstrings = @('APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_RESET_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT is absent', 'SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT is absent') },
    # --- Gap 2b: CLOUD_PROVIDER (absent is a PASS -- see the proceeds checks
    #     further below; only present-and-wrong belongs in this reject table) -
    @{ Name = 'gap 2b: CLOUD_PROVIDER present and not azure'; Env = @{ STUB_SERVING_ENV_JSON = $m2ProviderWrong }
       ExpectSubstrings = @('do not match the ratified attestation', 'CLOUD_PROVIDER', "observed='aws'", "expected='azure'")
       ForbiddenSubstrings = @('APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_RESET_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT is absent', 'SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT is absent') },
    # M-1: mixed-case CLOUD_PROVIDER value -- same false-pass-under-ne shape,
    # for CLOUD_PROVIDER's own comparison.
    @{ Name = 'm-1: CLOUD_PROVIDER mixed case (Azure) must reject, not pass case-insensitively'; Env = @{ STUB_SERVING_ENV_JSON = $m2ProviderMixedCase }
       ExpectSubstrings = @('do not match the ratified attestation', 'CLOUD_PROVIDER', "observed='Azure'", "expected='azure'")
       ForbiddenSubstrings = @('APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_RESET_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT is absent', 'SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT is absent') },
    # --- Key side (2026-09-14 review round): a wrongly-cased NAME, not a
    #     wrongly-cased VALUE. The verifier's `names` dict (a Python dict) is
    #     exact-key case-sensitive, so a name spelled in any other case than
    #     the ratified one must read as ABSENT here, exactly as it does post-
    #     wake -- never as a case-insensitive match on an otherwise-correct
    #     value.
    @{ Name = 'm-1 (key side): a lowercase env-var NAME must read as absent, not match case-insensitively'; Env = @{ STUB_SERVING_ENV_JSON = $m2NameLowercaseEligibility }
       ExpectSubstrings = @('do not match the ratified attestation', 'is absent from the serving revision', 'APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT', "expected='120s'")
       ForbiddenSubstrings = @('APP_DEMO_LOGIN_RESET_RESET_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT is absent', 'SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT is absent') },
    @{ Name = 'm-1 (key side): a mixed-case env-var NAME on the response ceiling must read as absent, not match case-insensitively'; Env = @{ STUB_SERVING_ENV_JSON = $m2NameMixedCaseCeiling }
       ExpectSubstrings = @('do not match the ratified attestation', 'is absent from the serving revision', 'SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT', "expected='150s'")
       ForbiddenSubstrings = @('APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_RESET_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT is absent') },
    # --- Ordinal-vs-linguistic (2026-09-14 review round, the gap the -cne
    #     fix left open): a ratified value with a trailing IGNORABLE
    #     character (U+00AD / U+FEFF, both category Cf per .NET's own
    #     Unicode data) compares EQUAL under -cne's linguistic comparer but
    #     UNEQUAL under the verifier's ordinal Python !=. These three exit 0
    #     (vacuously proceed) at e57c71e and exit 2 only once the comparison
    #     is [string]::Equals(..., [System.StringComparison]::Ordinal).
    #     U+200B is deliberately not used here -- the owner's Windows
    #     PowerShell 5.1 reproduction found -cne already rejects it, so a
    #     fixture built on it would be vacuous for this comparison property
    #     either way (a separate U+200B fixture below covers the sanitizer
    #     instead). Whether the message SANITIZES the value once it is
    #     known unequal is a second, independent property (see the block
    #     comment above): at f548a80 the sanitizer class
    #     ([\p{Cc}\p{Cf}\p{Zl}\p{Zp}]) fired on U+FEFF but NOT on U+00AD --
    #     the .NET Framework regex engine's own legacy category table
    #     matches U+00AD as Pd, not Cf, unlike CharUnicodeInfo on the same
    #     host -- so the eligibility and CLOUD_PROVIDER fixtures below were
    #     the two CI failures this round fixes; the ceiling (U+FEFF) fixture
    #     already passed and is a coverage pin. All three must now carry the
    #     fixed-shape placeholder, never the literal character -- expected
    #     below, with the raw character forbidden.
    @{ Name = 'ordinal-vs-linguistic: eligibility timeout 120s + trailing U+00AD (soft hyphen) must reject and sanitize, not compare equal linguistically or render raw (fails at f548a80, the .NET Framework regex engine matches U+00AD as Pd, not Cf)'; Env = @{ STUB_SERVING_ENV_JSON = $m2IgnorableEligibilityJson }
       ExpectSubstrings = @('do not match the ratified attestation', 'APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT', 'sanitized rendering, not the literal value', '-- 5 chars, withheld: contains a non-printable-ASCII character', "expected='120s'")
       ForbiddenSubstrings = @('APP_DEMO_LOGIN_RESET_RESET_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT is absent', 'SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT is absent', [string][char]0x00AD) },
    @{ Name = 'ordinal-vs-linguistic: response ceiling 150s + trailing U+FEFF (BOM) must reject and sanitize, not compare equal linguistically or render raw (already passed at f548a80 -- coverage pin, not new evidence)'; Env = @{ STUB_SERVING_ENV_JSON = $m2IgnorableCeilingJson }
       ExpectSubstrings = @('do not match the ratified attestation', 'SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT', 'sanitized rendering, not the literal value', '-- 5 chars, withheld: contains a non-printable-ASCII character', "expected='150s'")
       ForbiddenSubstrings = @('APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_RESET_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT is absent', [string][char]0xFEFF) },
    # A site OTHER than the four-name loop, per the brief: CLOUD_PROVIDER's
    # own comparison (~L921), independent code from the loop above.
    @{ Name = 'ordinal-vs-linguistic: CLOUD_PROVIDER azure + trailing U+00AD (soft hyphen) must reject and sanitize, not compare equal linguistically or render raw (fails at f548a80, same regex-engine category-table gap)'; Env = @{ STUB_SERVING_ENV_JSON = $m2IgnorableProviderJson }
       ExpectSubstrings = @('do not match the ratified attestation', 'CLOUD_PROVIDER', 'sanitized rendering, not the literal value', '-- 6 chars, withheld: contains a non-printable-ASCII character', "expected='azure'")
       ForbiddenSubstrings = @('APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_RESET_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT is absent', 'SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT is absent', [string][char]0x00AD) },
    # --- Sanitizer coverage (this round, not the comparison above): see the
    #     block comment further up for the U+034F/U+200B classification.
    @{ Name = 'sanitizer coverage: reset timeout 30s + trailing U+034F (combining grapheme joiner, Mn) must sanitize -- outside the OLD four-category class entirely, non-vacuous evidence for the allowlist'; Env = @{ STUB_SERVING_ENV_JSON = $m2SanitizerOnlyResetJson }
       ExpectSubstrings = @('do not match the ratified attestation', 'APP_DEMO_LOGIN_RESET_RESET_TIMEOUT', 'sanitized rendering, not the literal value', '-- 4 chars, withheld: contains a non-printable-ASCII character', "expected='30s'")
       ForbiddenSubstrings = @('APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT is absent', 'SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT is absent', [string][char]0x034F) },
    @{ Name = 'sanitizer coverage: idle threshold 30m + trailing U+200B (zero width space) must sanitize -- category Cf, already sanitized under the OLD class, a coverage pin rather than new evidence'; Env = @{ STUB_SERVING_ENV_JSON = $m2SanitizerOnlyIdleJson }
       ExpectSubstrings = @('do not match the ratified attestation', 'APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD', 'sanitized rendering, not the literal value', '-- 4 chars, withheld: contains a non-printable-ASCII character', "expected='30m'")
       ForbiddenSubstrings = @('APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_RESET_TIMEOUT is absent', 'APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT is absent', 'SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT is absent', [string][char]0x200B) },
    @{ Name = 'malformed / unparseable JSON'; Env = @{ STUB_SERVING_ENV_JSON = $m2MalformedJson }
       ExpectSubstrings = @('did not parse as JSON'); ForbiddenSubstrings = @('MALFORMED-MARKER-DO-NOT-LEAK') },
    @{ Name = 'row carries secretRef instead of value'; Env = @{ STUB_SERVING_ENV_JSON = $m2SecretRefJson }
       ExpectSubstrings = @('do not match the ratified attestation', 'carries a secretRef or a null value', 'APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT', "expected='120s'"); ForbiddenSubstrings = @('kv-prod-root-password') },
    @{ Name = 'duplicate serving environment name (first copy correct) is rejected, not silently kept'; Env = @{ STUB_SERVING_ENV_JSON = $m2DuplicateNameJson }
       # Mirrors the verifier (scripts/verify_demo_reset_azure.py), which
       # raises "duplicate serving environment value: <name>" on the same
       # shape. Must exit 2 here even though the first copy alone would
       # match -- silently keeping the first copy is exactly the
       # pre-wake-pass/post-wake-fail gap this boundary exists to close.
       ExpectSubstrings = @('duplicate serving environment value', 'APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT'); ForbiddenSubstrings = @() },
    @{ Name = 'duplicate serving environment name carrying a bidi override character is rejected without echoing it raw'; Env = @{ STUB_SERVING_ENV_JSON = $m2HostileDuplicateNameJson }
       # The duplicated NAME itself (not the value) carries the bidi
       # override character built at runtime above. The Fail message must
       # still say "duplicate serving environment value" and use the same
       # fixed-shape placeholder the value branch uses (~L827-876), but must
       # never reproduce the marker text or the raw bidi character.
       ExpectSubstrings = @('duplicate serving environment value', 'sanitized rendering, not the literal value')
       ForbiddenSubstrings = @($m2DuplicateNameHostileMarker, [string][char]0x202E) },
    @{ Name = 'the az call itself fails (non-zero exit)'; Env = @{ STUB_AZ_FAIL_MATCH = 'containers[0].env' }
       ExpectSubstrings = @('could not read the serving revision environment'); ForbiddenSubstrings = @() },
    @{ Name = 'the az call exits 0 with no output at all'; Env = @{ STUB_AZ_EMPTY_MATCH = 'containers[0].env' }
       ExpectSubstrings = @('the serving revision environment read returned no output'); ForbiddenSubstrings = @() },
    @{ Name = 'the az call emits a plausible payload and then fails'; Env = @{ STUB_AZ_FAILAFTER_MATCH = 'containers[0].env' }
       ExpectSubstrings = @('could not read the serving revision environment'); ForbiddenSubstrings = @() }
)
foreach ($m2Case in $m2Cases) {
    $e = $good.Clone()
    foreach ($k in $m2Case.Env.Keys) { $e[$k] = $m2Case.Env[$k] }
    $m2Evidence = Join-Path ([IO.Path]::GetTempPath()) "t89-m2-evidence-$([guid]::NewGuid()).json"
    try {
        $r = Invoke-Wrapper -Env $e -Extra @('-EvidenceOutput', $m2Evidence)
        Check "$($m2Case.Name) => exit 2, no wake, no verifier, no evidence written" {
            if ($r.Exit -ne 2) { throw "exit $($r.Exit) (expected 2); output: $($r.Output)" }
            if ($r.Capture -match '(?m)^curl ') { throw 'a wake was issued despite the Azure timeout precondition failing' }
            if ($r.Capture -match '(?m)^python .*--mode ') { throw 'the verifier ran despite the Azure timeout precondition failing' }
            if (Test-Path -LiteralPath $m2Evidence) { throw 'verifier evidence was written despite the Azure timeout precondition failing' }
            foreach ($want in $m2Case.ExpectSubstrings) {
                if (-not $r.Output.Contains($want)) { throw "expected output to contain '$want'; output:`n$($r.Output)" }
            }
            foreach ($bad in $m2Case.ForbiddenSubstrings) {
                if ($r.Output.Contains($bad)) { throw "output leaked untrusted/sensitive content '$bad':`n$($r.Output)" }
            }
        }
    } finally {
        Remove-Item -LiteralPath $m2Evidence -ErrorAction SilentlyContinue
    }
}

Write-Host 'Serving Azure demo-reset timeout precondition (M2): absent-is-a-pass proceeds for idle threshold and CLOUD_PROVIDER'
# These are the deliberately-NOT-symmetric half of Gap 2: unlike the four
# names in the loop above (and unlike ceiling absence, Gap 1), an ABSENT
# APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD or CLOUD_PROVIDER must proceed, because
# each falls back to the same value this wrapper approves (application.yml:135
# for the idle threshold; the verifier's own names.get("CLOUD_PROVIDER",
# "azure") default for the provider).
# Case-sensitive duplicate semantics (2026-09-14 review round): the
# wrapper's $observedAzureTimeoutValues hashtable is Ordinal (~L740),
# matching the verifier's own `names: dict[str, Any]` -- a plain Python
# dict, exact-key, case-sensitive (verify_demo_reset_azure.py:700-705).
# APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT and its lowercase twin are
# therefore DISTINCT keys to both, never a duplicate: the verifier's own
# `if row["name"] in names:` (L703) is a Python dict membership test that
# only raises on an EXACT repeat of the same key, and the wrapper's own
# ContainsKey check (~L751) now agrees. This fixture must proceed (exit 0),
# not be rejected as a duplicate -- and every other gate in it (idle
# threshold, the four checked names, CLOUD_PROVIDER absent) is
# independently correct, so exit 0 here isolates that one semantic.
$m2CaseVariantNameNotADuplicate = '[{"name":"APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD","value":"30m"},{"name":"APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT","value":"120s"},{"name":"app_demo_login_reset_eligibility_timeout","value":"999s"},{"name":"APP_DEMO_LOGIN_RESET_RESET_TIMEOUT","value":"30s"},{"name":"APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT","value":"165s"},{"name":"SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT","value":"150s"}]'
$m2ProceedCases = @(
    @{ Name = 'idle threshold absent proceeds (falls back to the approved 30m default)'; Json = $m2IdleAbsent },
    @{ Name = 'CLOUD_PROVIDER = azure (present and correct) proceeds'; Json = $m2ProviderAzure },
    @{ Name = 'a case-variant twin of a checked name (APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT vs. its lowercase twin) is a distinct key under Ordinal, not a duplicate, and proceeds'; Json = $m2CaseVariantNameNotADuplicate }
)
foreach ($m2Proceed in $m2ProceedCases) {
    $e = $good.Clone(); $e['STUB_SERVING_ENV_JSON'] = $m2Proceed.Json
    $r = Invoke-Wrapper -Env $e
    Check "$($m2Proceed.Name)" {
        if ($r.Exit -ne 0) { throw "exit $($r.Exit) (expected 0); output: $($r.Output)" }
        Assert-ProbeCount $r 1
    }
}
# CLOUD_PROVIDER absent also proceeds -- covered by every case above that
# does not set CLOUD_PROVIDER at all, including the baseline match check
# ($m2RatifiedJson, asserted exit 0 above) and $m2IdleAbsent just above.

Write-Host 'Serving Azure demo-reset timeout precondition (M2): console output must not reproduce untrusted content'
# run_task_8_9_preflight.ps1's mismatch-message construction (~L827-876)
# sanitizes $observedValue -- the one field in this whole precondition that
# is genuinely attacker/deployment controlled, read verbatim from the
# serving Container App's env by the az call above -- before it reaches
# Fail, i.e. Write-Host, i.e. the operator's transcript: a value with a
# character outside the printable-ASCII allowlist ([^\x20-\x7E]), or one
# over 80 characters, is replaced WHOLESALE by a fixed-shape
# placeholder rather than shown even in part. The cases below assert that
# safety property (no raw value, secret name, control character, injected
# line, or bidi override ever reaches the console) and are expected to PASS
# against the current wrapper -- the sanitizer above is what makes them
# pass. Do not loosen these assertions; a future change that makes any of
# them fail is a regression in the sanitizer, not a test to relax.
$m2HostileCases = @(
    @{ Name = 'an embedded newline in the observed value is not reproduced in console output'; Env = @{ STUB_SERVING_ENV_JSON = $m2HostileNewlineJson }
       ForbiddenSubstrings = @('INJECTED-VIA-NEWLINE-MARKER') },
    @{ Name = 'an ANSI escape sequence in the observed value is not reproduced in console output'; Env = @{ STUB_SERVING_ENV_JSON = $m2HostileAnsiJson }
       ForbiddenSubstrings = @('FAKE-ALERT', [string][char]0x1B) },
    @{ Name = 'a very long observed value is not reproduced in console output'; Env = @{ STUB_SERVING_ENV_JSON = $m2HostileLongJson }
       ForbiddenSubstrings = @('PWNED-', 'MARKEREND') },
    @{ Name = 'a bidi override character in the observed value is not reproduced in console output'; Env = @{ STUB_SERVING_ENV_JSON = $m2HostileBidiJson }
       # Discriminates the printable-ASCII allowlist from the ORIGINAL
       # [\x00-\x1F\x7F-\x9F] range only: U+202E is outside \x20-\x7E but is
       # not a C0/C1 control byte, so that pattern would miss it and this
       # case would fail against it. Against the IMMEDIATELY PRECEDING
       # four-category class this case is a COVERAGE PIN, not a
       # discriminator: that engine does match U+202E as \p{Cf}, and this
       # case PASSED on the f548a80 Windows run (282/284 -- the only two
       # failures were the U+00AD fixtures, see the U+00AD gap documented
       # above). It pins the property across the change; it is not evidence
       # the change was needed.
       ForbiddenSubstrings = @('INJECTED-VIA-BIDI-MARKER', [string][char]0x202E) },
    # Same bidi-override property, now under each of the three NEW names --
    # Gap 1's ceiling and Gap 2's idle threshold / CLOUD_PROVIDER route
    # through their own inlined sanitizer blocks (no shared function is
    # permitted in this file), so each is exercised on its own rather than
    # trusting that "the same rule" was actually copied correctly.
    @{ Name = 'a bidi override character in the observed response-ceiling value is not reproduced in console output'; Env = @{ STUB_SERVING_ENV_JSON = $m2HostileCeilingJson }
       ForbiddenSubstrings = @($m2HostileCeilingMarker, [string][char]0x202E) },
    @{ Name = 'a bidi override character in the observed idle-threshold value is not reproduced in console output'; Env = @{ STUB_SERVING_ENV_JSON = $m2HostileIdleJson }
       ForbiddenSubstrings = @($m2HostileIdleMarker, [string][char]0x202E) },
    @{ Name = 'a bidi override character in the observed CLOUD_PROVIDER value is not reproduced in console output'; Env = @{ STUB_SERVING_ENV_JSON = $m2HostileProviderJson }
       ForbiddenSubstrings = @($m2HostileProviderMarker, [string][char]0x202E) }
)
foreach ($m2Hostile in $m2HostileCases) {
    $e = $good.Clone()
    foreach ($k in $m2Hostile.Env.Keys) { $e[$k] = $m2Hostile.Env[$k] }
    $r = Invoke-Wrapper -Env $e
    Check "$($m2Hostile.Name)" {
        if ($r.Exit -ne 2) { throw "exit $($r.Exit) (expected 2); output: $($r.Output)" }
        if ($r.Capture -match '(?m)^curl ') { throw 'a wake was issued despite a hostile observed value' }
        if ($r.Capture -match '(?m)^python .*--mode ') { throw 'the verifier ran despite a hostile observed value' }
        foreach ($bad in $m2Hostile.ForbiddenSubstrings) {
            if ($r.Output.Contains($bad)) { throw "the operator transcript reproduced untrusted content verbatim ('$bad'):`n$($r.Output)" }
        }
    }
}

Write-Host 'A probe that reports no metadata at all stops the run'
$r = Invoke-Wrapper -Env (Probe-Env $good @(@{ META = 'none' }, @{ STATUS = '200'; SENTINEL = '1' }))
Check 'probe with no metadata record => exit 3, one probe, no verifier' {
    if ($r.Exit -ne 3) { throw "exit $($r.Exit) (expected 3); output: $($r.Output)" }
    Assert-ProbeCount $r 1
    Assert-NoVerifier $r
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
    # The sequence ended on its first 200, so exactly one probe was issued.
    $n = ([regex]::Matches($r.Capture, '(?m)^curl ')).Count
    if ($n -ne 1) { throw "probes issued $n times" }
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
$wOut = 't89:%{http_code}:%{time_total}:%{content_type}'
# Each case gets a fresh generated body path, exactly as the wrapper makes one.
$fresh = '<fresh>'
$stubCases = @(
    @{ Name = 'the authorized vector';         Ok = $true;  Args = @('-q', '--noproxy', '*', '-sS', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = 'a retry';                       Ok = $false; Args = @('-q', '--noproxy', '*', '-sS', '-o', $fresh, '-w', $wOut, '--max-time', '90', '--retry', '3', $authUrl) },
    @{ Name = 'a retry on all errors';         Ok = $false; Args = @('-q', '--noproxy', '*', '-sS', '-o', $fresh, '-w', $wOut, '--max-time', '90', '--retry', '2', '--retry-all-errors', $authUrl) },
    @{ Name = 'a redirect follow';             Ok = $false; Args = @('-q', '--noproxy', '*', '-sS', '-L', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = 'a redirect follow (long form)'; Ok = $false; Args = @('-q', '--noproxy', '*', '-sS', '-o', $fresh, '-w', $wOut, '--max-time', '90', '--location', $authUrl) },
    @{ Name = 'an altered -w';                 Ok = $false; Args = @('-q', '--noproxy', '*', '-sS', '-o', $fresh, '-w', 't89:200:0.1:application/json', '--max-time', '90', $authUrl) },
    @{ Name = 'the retired -w';                Ok = $false; Args = @('-q', '--noproxy', '*', '-sS', '-o', $fresh, '-w', '%{http_code}', '--max-time', '90', $authUrl) },
    @{ Name = 'no --max-time';                 Ok = $false; Args = @('-q', '--noproxy', '*', '-sS', '-o', $fresh, '-w', $wOut, $authUrl) },
    @{ Name = 'a longer --max-time';           Ok = $false; Args = @('-q', '--noproxy', '*', '-sS', '-o', $fresh, '-w', $wOut, '--max-time', '120', $authUrl) },
    @{ Name = 'a second --max-time';           Ok = $false; Args = @('-q', '--noproxy', '*', '-sS', '-o', $fresh, '-w', $wOut, '--max-time', '90', '--max-time', '300', $authUrl) },
    @{ Name = 'an alternate URL';              Ok = $false; Args = @('-q', '--noproxy', '*', '-sS', '-o', $fresh, '-w', $wOut, '--max-time', '90', 'https://api.vibhanshu-ai-portfolio.dev/api/portfolio/holdings') },
    @{ Name = 'a second request in one call';  Ok = $false; Args = @('-q', '--noproxy', '*', '-sS', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl, $authUrl) },
    # -q must be FIRST, not merely present: curl only honours it as the very
    # first argument, so both "no -q at all" and "-q after another flag" must be
    # refused -- otherwise a curl configuration file could still inject
    # retry/redirect.
    @{ Name = 'no -q at all (curl config files read)'; Ok = $false; Args = @('--noproxy', '*', '-sS', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = '-q present but not first';      Ok = $false; Args = @('-sS', '-q', '--noproxy', '*', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    # The body path is the one position that varies, and only to a fresh
    # generated temp file.
    @{ Name = 'the body sent to stdout (-o -)'; Ok = $false; Args = @('-q', '--noproxy', '*', '-sS', '-o', '-', '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = 'no body capture (-o NUL)';      Ok = $false; Args = @('-q', '--noproxy', '*', '-sS', '-o', 'NUL', '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = 'a body path outside the temp directory'; Ok = $false; Args = @('-q', '--noproxy', '*', '-sS', '-o', (Join-Path $repo "t89-wake-$([guid]::NewGuid().ToString('N')).body"), '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = 'a body path with another name'; Ok = $false; Args = @('-q', '--noproxy', '*', '-sS', '-o', (Join-Path ([IO.Path]::GetTempPath()) "other-$([guid]::NewGuid().ToString('N')).body"), '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = 'a body file that already exists'; Ok = $false; Existing = $true; Args = @('-q', '--noproxy', '*', '-sS', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    # A TMP ending in a backslash. GetTempPath still returns exactly one
    # trailing backslash, so the wrapper's generated path is unchanged and must
    # be accepted -- and the directory comparison must not loosen into
    # accepting another directory.
    @{ Name = 'the authorized vector with a trailing-backslash TMP'; Ok = $true; TrailingTmp = $true; Args = @('-q', '--noproxy', '*', '-sS', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = 'a body path outside the temp directory with a trailing-backslash TMP'; Ok = $false; TrailingTmp = $true; Args = @('-q', '--noproxy', '*', '-sS', '-o', (Join-Path $repo "t89-wake-$([guid]::NewGuid().ToString('N')).body"), '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = 'a body path in a temp subdirectory with a trailing-backslash TMP'; Ok = $false; TrailingTmp = $true; Args = @('-q', '--noproxy', '*', '-sS', '-o', (Join-Path ([IO.Path]::GetTempPath()) "sub\t89-wake-$([guid]::NewGuid().ToString('N')).body"), '-w', $wOut, '--max-time', '90', $authUrl) },
    # --noproxy '*' immediately after -q: -q skips curl's config files but not
    # proxy environment variables, which only --noproxy '*' bypasses. Each
    # removal, narrowing or move is refused under its own recorded reason.
    @{ Name = '--noproxy removed';                     Ok = $false; Reason = 'noproxy'; Args = @('-q', '-sS', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = '--noproxy narrowed to the gateway host'; Ok = $false; Reason = 'noproxy'; Args = @('-q', '--noproxy', 'api.vibhanshu-ai-portfolio.dev', '-sS', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = '--noproxy narrowed to localhost';       Ok = $false; Reason = 'noproxy'; Args = @('-q', '--noproxy', 'localhost', '-sS', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = '--noproxy with an empty list';          Ok = $false; Reason = 'noproxy'; Args = @('-q', '--noproxy', '""', '-sS', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = '--noproxy *,x';                         Ok = $false; Reason = 'noproxy'; Args = @('-q', '--noproxy', '*,x', '-sS', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = '--noproxy moved after -sS';             Ok = $false; Reason = 'noproxy'; Args = @('-q', '-sS', '--noproxy', '*', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = '--noproxy moved to the end';            Ok = $false; Reason = 'noproxy'; Args = @('-q', '-sS', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl, '--noproxy', '*') },
    @{ Name = 'a second --noproxy narrowing the first'; Ok = $false; Args = @('-q', '--noproxy', '*', '-sS', '--noproxy', 'localhost', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    # Proxy and pre-proxy arguments, refused by name even with --noproxy '*' in
    # place.
    @{ Name = 'a proxy (-x)';                          Ok = $false; Reason = 'proxy argument'; Args = @('-q', '--noproxy', '*', '-sS', '-x', 'http://127.0.0.1:9', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = 'a proxy in a short-option cluster (-sSx)'; Ok = $false; Reason = 'proxy argument'; Args = @('-q', '--noproxy', '*', '-sSx', 'http://127.0.0.1:9', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = 'a proxy (--proxy)';                     Ok = $false; Reason = 'proxy argument'; Args = @('-q', '--noproxy', '*', '-sS', '--proxy', 'http://127.0.0.1:9', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = 'a pre-proxy (--preproxy)';              Ok = $false; Reason = 'proxy argument'; Args = @('-q', '--noproxy', '*', '-sS', '--preproxy', 'socks5://127.0.0.1:9', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = 'a SOCKS4 proxy (--socks4)';             Ok = $false; Reason = 'proxy argument'; Args = @('-q', '--noproxy', '*', '-sS', '--socks4', '127.0.0.1:9', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = 'a SOCKS4a proxy (--socks4a)';           Ok = $false; Reason = 'proxy argument'; Args = @('-q', '--noproxy', '*', '-sS', '--socks4a', '127.0.0.1:9', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = 'a SOCKS5 proxy (--socks5)';             Ok = $false; Reason = 'proxy argument'; Args = @('-q', '--noproxy', '*', '-sS', '--socks5', '127.0.0.1:9', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = 'a SOCKS5 proxy (--socks5-hostname)';    Ok = $false; Reason = 'proxy argument'; Args = @('-q', '--noproxy', '*', '-sS', '--socks5-hostname', '127.0.0.1:9', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = 'proxy credentials (-U)';                Ok = $false; Reason = 'proxy argument'; Args = @('-q', '--noproxy', '*', '-sS', '-U', 'u:p', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = 'proxy credentials (--proxy-user)';      Ok = $false; Reason = 'proxy argument'; Args = @('-q', '--noproxy', '*', '-sS', '--proxy-user', 'u:p', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) },
    @{ Name = 'another --proxy-* option (--proxy-insecure)'; Ok = $false; Reason = 'proxy argument'; Args = @('-q', '--noproxy', '*', '-sS', '--proxy-insecure', '-o', $fresh, '-w', $wOut, '--max-time', '90', $authUrl) }
)
# Every case runs the stub from a directory holding a file, so a stub that
# walked %* with `for` and expanded * against the working directory would record
# that file's name in curl-noproxy-arg instead of *.
$globCwd = Join-Path ([IO.Path]::GetTempPath()) "t89-globcwd-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $globCwd | Out-Null
[IO.File]::WriteAllText((Join-Path $globCwd 't89-glob-canary.txt'), 'canary')
foreach ($sc in $stubCases) {
    $capFile = Join-Path ([IO.Path]::GetTempPath()) "t89-stubcurl-$([guid]::NewGuid()).txt"
    $bodyFile = Join-Path ([IO.Path]::GetTempPath()) "t89-wake-$([guid]::NewGuid().ToString('N')).body"
    $callArgs = @($sc.Args | ForEach-Object { if ($_ -ceq $fresh) { $bodyFile } else { $_ } })
    if ($sc.ContainsKey('Existing')) { [IO.File]::WriteAllText($bodyFile, 'pre-existing') }
    [Environment]::SetEnvironmentVariable('STUB_CAPTURE', $capFile)
    $savedTmp = [Environment]::GetEnvironmentVariable('TMP')
    $savedTemp = [Environment]::GetEnvironmentVariable('TEMP')
    $tmpSeen = $null
    if ($sc.ContainsKey('TrailingTmp')) {
        $slashed = [IO.Path]::GetTempPath()
        if (-not $slashed.EndsWith('\')) { $slashed += '\' }
        [Environment]::SetEnvironmentVariable('TMP', $slashed)
        [Environment]::SetEnvironmentVariable('TEMP', $slashed)
        $tmpSeen = [Environment]::GetEnvironmentVariable('TMP')
    }
    Push-Location -LiteralPath $globCwd
    try {
        $sout = @(& $stubCurl @callArgs 2>$null)
        $sexit = $LASTEXITCODE
    } finally {
        Pop-Location
        [Environment]::SetEnvironmentVariable('TMP', $savedTmp)
        [Environment]::SetEnvironmentVariable('TEMP', $savedTemp)
    }
    $scap = if (Test-Path $capFile) { Get-Content $capFile -Raw } else { '' }
    $bodyAfter = if (Test-Path -LiteralPath $bodyFile) { [IO.File]::ReadAllText($bodyFile) } else { $null }
    Remove-Item $capFile -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $bodyFile -ErrorAction SilentlyContinue
    [Environment]::SetEnvironmentVariable('STUB_CAPTURE', $null)
    Check "curl stub: $($sc.Name) is $(if ($sc.Ok) { 'accepted' } else { 'refused with no metadata' })" {
        if ($sc.ContainsKey('TrailingTmp') -and -not "$tmpSeen".EndsWith('\')) { throw "the fixture did not give the stub a TMP ending in a backslash ('$tmpSeen')" }
        if ($sc.Ok) {
            if ($sexit -ne 0 -or ($sout -join '|') -cne 't89:200:0.012345:application/json' -or $scap -match 'curl-violation') {
                throw "authorized vector rejected (exit $sexit, out '$($sout -join '|')')"
            }
            if ($null -eq $bodyAfter) { throw 'the stub did not write the body file' }
            # Exactly one literal * arrived after --noproxy, read positionally,
            # although the working directory holds a file a glob would match.
            $npLines = @([regex]::Matches($scap, '(?m)^curl-noproxy-arg .*$') | ForEach-Object { $_.Value.TrimEnd() })
            if ($npLines.Count -ne 1 -or $npLines[0] -cne 'curl-noproxy-arg [--noproxy] [*] [-sS]') { throw "the stub did not record exactly one literal * after --noproxy: '$($npLines -join ' | ')'" }
            if ($scap -match 't89-glob-canary') { throw 'a working-directory file name reached the stub arguments: * was globbed' }
        } else {
            if ($sexit -ne 99) { throw "exit $sexit, expected 99" }
            if (($sout -join '').Trim()) { throw "printed metadata '$($sout -join '|')' for an unauthorized vector" }
            if ($scap -notmatch 'curl-violation') { throw 'no violation was recorded' }
            if ($sc.ContainsKey('Reason') -and $scap -notmatch "(?m)^curl-violation $([regex]::Escape($sc.Reason))") { throw "refused, but not for the reason '$($sc.Reason)': $($scap -replace '\s+', ' ')" }
            if ($sc.ContainsKey('Existing') -and $bodyAfter -cne 'pre-existing') { throw 'the stub overwrote a pre-existing body file' }
        }
    }
}
Remove-Item -LiteralPath $globCwd -Recurse -Force -ErrorAction SilentlyContinue

Write-Host 'SkipWake refuses every command left at its real default'
# A regression in this guard would let a run continue to REAL tools. So every
# default-value case runs with stub az, docker and python first on PATH and an
# empty Azure config directory. If the guard ever breaks -- including in a
# mutation run -- the name resolves to a stub, and no Azure call can be made.
# curl.exe needs no shadow: -SkipWake never invokes it.
$skipRig = New-ShadowPathRig 'skip'
foreach ($d in @(
    @{ P = '-AzCommand';     V = 'az' },
    @{ P = '-DockerCommand'; V = 'docker' },
    @{ P = '-CurlCommand';   V = 'curl.exe' },
    @{ P = '-PythonCommand'; V = 'python' }
)) {
    $e = New-ShadowEnvironment $skipRig
    $r = Invoke-Wrapper -Env $e -Extra @('-SkipWake', $d.P, $d.V)
    Check "SkipWake refuses $($d.P) left at its default '$($d.V)', before any child process runs" {
        if ($r.Exit -ne 2) { throw "exit $($r.Exit) (expected 2); output: $($r.Output)" }
        if ($r.Output -notmatch 'offline tests') { throw "did not explain why:`n$($r.Output)" }
        if ($r.Capture -match '(?m)^(az|docker|python|curl) ') { throw "a child process ran before the guard:`n$($r.Capture)" }
    }
}
Remove-Item $skipRig.Root -Recurse -Force -ErrorAction SilentlyContinue

Write-Host 'SkipWake'
$r = Invoke-Wrapper -Env $good.Clone() -Extra @('-SkipWake')
Check 'exits 0 without issuing a wake' {
    if ($r.Exit -ne 0) { throw "exit $($r.Exit); output: $($r.Output)" }
    if ($r.Capture -match '(?m)^curl ') { throw 'wake issued despite -SkipWake' }
}

foreach ($fx in $script:fixtureFiles) { Remove-Item -LiteralPath $fx -ErrorAction SilentlyContinue }

Write-Host ''
Write-Host "$($tests - $failures)/$tests passed"
if ($failures -gt 0) { exit 1 }
exit 0
