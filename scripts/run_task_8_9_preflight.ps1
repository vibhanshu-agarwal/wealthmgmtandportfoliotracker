<#
.SYNOPSIS
    Runs the B2 Task 8.9 bounded wake + read-only preflight as ONE sequence.

    Written for and tested against Windows PowerShell 5.1. It has not been
    exercised under PowerShell 7, whose ConvertFrom-Json array semantics differ
    from the ones this script's replica poll depends on.

.DESCRIPTION
    The Task 8.9 verifier cannot resolve a gateway replica while api-gateway is
    scaled to zero. A single inbound HTTP request activates the existing revision
    without creating a new one; the replica then idles out after roughly 300
    seconds. The 2026-09-12 attempt failed because the checks and the run were
    issued by hand, one paste at a time, and the window expired before the
    verifier reached its replica-list call.

    This wrapper exists to take everything that does not need to be inside the
    window out of it. All preconditions -- Azure session, Docker, the live
    revision/digest comparison against the attestation -- run BEFORE the wake.
    Only the wake, a bounded read-only wait for the replica, and the verifier
    itself happen after it.

    Deliberately NOT supported, and refused rather than ignored:
      * execute mode          -- the mode is fixed to 'preflight'
      * --threshold-override  -- never passed; it forecloses GO and creates a revision
      * credentials           -- none are read, stored, or written by this script
      * wake retries          -- exactly one HTTP request is ever issued

    Exit codes:
      0  preflight passed
      2  a precondition failed BEFORE the wake (nothing was consumed)
      3  the wake was issued and the run stopped after it -- no ready replica,
         a non-200 response, or a transport failure. Treat the wake as consumed
         unless the message says otherwise; a further wake is a fresh owner
         decision. (For curl exits 6/7/35 the message says the request probably
         never reached the ingress, so the wake was probably NOT consumed; the
         exit code stays 3 because that is the conservative reading.)
      4  the verifier ran and did not pass (wake consumed)

.PARAMETER SkipWake
    Runs the sequence without issuing the wake. Intended for the offline tests.
    It is NOT a way to continue past a non-200: waking by hand, seeing a non-200
    and then running with -SkipWake evades the stop this script exists to
    enforce, and the script cannot detect that. Using it against production is an
    owner decision.

.PARAMETER AzCommand
.PARAMETER CurlCommand
.PARAMETER PythonCommand
    Injection points so the sequence can be exercised against local stubs that
    capture arguments. They exist because every hand-issued failure in this
    task's history was an argument-quoting fault at the PowerShell/az.bat
    boundary, not a logic error -- that boundary needs to be testable offline.
#>
[CmdletBinding()]
param(
    [string]$ResourceGroup = 'wealth-azure-prod-rg',
    [string]$GatewayApp = 'api-gateway',
    [string]$PortfolioApp = 'portfolio-service',
    [string]$Workspace = 'wealth-prod-la',
    [string]$Registry = 'wealthprodacr',
    [string]$GatewayUrl = 'https://api.vibhanshu-ai-portfolio.dev',
    [string]$Target = 'production-azure',
    [string]$WakePath = '/actuator/health',
    [string]$ProvenancePath = 'docs/evidence/b2-task-8-9/deployment-provenance-20260911.json',
    [string]$SubscriptionSourcePath = 'docs/evidence/b2-task-8-9/rehearsal-20260911.json',
    [string]$SubscriptionId,
    [string]$EvidenceOutput,
    [int]$OperationTimeoutSeconds = 600,
    [int]$ReplicaWaitSeconds = 90,
    [int]$ReplicaPollSeconds = 5,
    [switch]$SkipWake,
    [string]$AzCommand = 'az',
    [string]$DockerCommand = 'docker',
    [string]$CurlCommand = 'curl.exe',
    [string]$PythonCommand = 'python',
    [string]$VerifierPath = 'scripts/verify_demo_reset_azure.py'
)

Set-StrictMode -Version Latest
# 'Continue', deliberately. Under 'Stop', a native command writing to stderr
# becomes a terminating error in Windows PowerShell 5.1 whenever the caller
# captures a transcript (`& script.ps1 2>&1 | Tee-Object`) -- and real `az`
# emits warnings on stderr routinely, `curl -sS` on every transport failure.
# That would turn this script's classified, exit-coded failures into unhandled
# traps in exactly the invocation style an operator uses to keep a record.
# Every native call below checks $LASTEXITCODE explicitly; StrictMode
# violations and method errors are terminating regardless and still hit the trap.
$ErrorActionPreference = 'Continue'

$script:WakeIssued = $false
trap {
    $consumed = if ($script:WakeIssued) { 'THE WAKE WAS ISSUED and is consumed; a further wake is a fresh owner decision.' }
                else { 'No wake had been issued; nothing was consumed.' }
    Write-Host "FAIL (unhandled): $($_.Exception.Message)" -ForegroundColor Red
    Write-Host $consumed -ForegroundColor Yellow
    exit $(if ($script:WakeIssued) { 3 } else { 2 })
}

# Scrub the task credentials before ANY child process. The verifier reads them
# at parse time in every mode; every az/docker/curl/python child below would
# otherwise inherit them. An earlier revision scrubbed only before the python
# children, leaving nine az/docker children with the values in their
# environment. Restored in the finally at the end of the script.
$savedToken = $env:TASK8_9_ACCESS_TOKEN
$savedPassword = $env:TASK8_9_DEMO_PASSWORD
$env:TASK8_9_ACCESS_TOKEN = $null
$env:TASK8_9_DEMO_PASSWORD = $null
try {

function Write-Step { param([string]$Text) Write-Host "==> $Text" }
function Fail {
    param([string]$Text, [int]$Code)
    Write-Host "FAIL: $Text" -ForegroundColor Red
    exit $Code
}

# --- Inputs -----------------------------------------------------------------
# JMESPath queries below never contain parentheses. A parenthesised --query does
# not survive PowerShell -> az.bat -> cmd: 'length(@)' arrives as 'length(@'.
# Counting is done in PowerShell instead.

if (-not (Test-Path $ProvenancePath)) { Fail "provenance file not found: $ProvenancePath" 2 }
if (-not (Test-Path $VerifierPath)) { Fail "verifier not found: $VerifierPath" 2 }

$provenance = Get-Content $ProvenancePath -Raw | ConvertFrom-Json
$attestedRevision = $provenance.services.$GatewayApp.revision
$attestedDigest = $provenance.services.$GatewayApp.digest
if (-not $attestedRevision -or -not $attestedDigest) {
    Fail "provenance does not attest $GatewayApp" 2
}
Write-Step "Attested: $attestedRevision @ $attestedDigest"

if (-not $EvidenceOutput) {
    $EvidenceOutput = "docs/evidence/b2-task-8-9/rehearsal-$(Get-Date -Format 'yyyyMMdd').json"
}
if (Test-Path $EvidenceOutput) {
    Fail "evidence output already exists, refusing to overwrite: $EvidenceOutput" 2
}

if (-not $SubscriptionId) {
    if (-not (Test-Path $SubscriptionSourcePath)) {
        Fail "no -SubscriptionId given and source not found: $SubscriptionSourcePath" 2
    }
    $SubscriptionId = (Get-Content $SubscriptionSourcePath -Raw | ConvertFrom-Json).target.subscriptionId
}
if (-not $SubscriptionId) { Fail 'subscription id could not be resolved' 2 }
# The value itself is never printed.
Write-Step "Subscription resolved (length $($SubscriptionId.Length))"

# --- Preconditions, all OUTSIDE the timing window ---------------------------

Write-Step 'Checking the wake request is the authorized one'
if ($WakePath -ne '/actuator/health') {
    Fail "wake path '$WakePath' is not the authorized '/actuator/health'" 2
}
if ($GatewayUrl -ne 'https://api.vibhanshu-ai-portfolio.dev') {
    Fail "gateway url '$GatewayUrl' is not the authorized host" 2
}

Write-Step 'Checking Azure session'
$account = & $AzCommand account show --query name -o tsv
if ($LASTEXITCODE -ne 0 -or -not $account) { Fail 'no authenticated az session' 2 }
Write-Step "  az account: $account"

Write-Step 'Checking Docker daemon'
$dockerOs = ''
try { $dockerOs = & $DockerCommand version --format '{{.Server.Os}}' } catch { $dockerOs = '' }
if ($LASTEXITCODE -ne 0 -or -not $dockerOs) { Fail 'Docker daemon is not reachable' 2 }
if ($dockerOs -ne 'linux') { Fail "Docker is in '$dockerOs' mode; Linux containers are required" 2 }
Write-Step "  docker server os: $dockerOs"

Write-Step 'Checking live serving revision against the attestation'
$servingRevision = & $AzCommand containerapp show --name $GatewayApp --resource-group $ResourceGroup --query 'properties.latestReadyRevisionName' -o tsv
if ($LASTEXITCODE -ne 0) { Fail 'could not read the container app' 2 }
if (-not $servingRevision) { Fail 'the container app read returned no revision name' 2 }
$servingImage = & $AzCommand containerapp show --name $GatewayApp --resource-group $ResourceGroup --query 'properties.template.containers[0].image' -o tsv
if ($LASTEXITCODE -ne 0) { Fail 'could not read the serving image' 2 }
# Emptiness is checked SEPARATELY from the -like comparison below, and before
# it. `az --query <path> -o tsv` exits 0 and prints nothing when the path does
# not resolve, and a no-output native command assigns AutomationNull, against
# which -notlike returns an empty collection -- i.e. FALSE. The digest guard is
# therefore silently inert on an empty read unless emptiness is caught here
# first. (-ne does not share this behaviour, which is why only the two -like
# guards were affected.) Found by branch-removal mutation on 2026-09-12.
if (-not $servingImage) { Fail 'the container app read returned no image reference' 2 }

if ($servingRevision -ne $attestedRevision) {
    Fail "serving revision '$servingRevision' does not match attested '$attestedRevision' -- the attestation is stale; STOP" 2
}
if ([string]$servingImage -notlike "*@$attestedDigest") {
    Fail "serving image '$servingImage' does not carry attested digest $attestedDigest -- STOP" 2
}
Write-Step "  serving $servingRevision at the attested digest"

Write-Step 'Checking the active subscription matches the one the verifier will assert'
$activeSub = & $AzCommand account show --query id -o tsv
if ($LASTEXITCODE -ne 0) { Fail 'could not read the active subscription' 2 }
if (-not $activeSub) { Fail 'the active subscription read returned nothing' 2 }
if ($activeSub -ne $SubscriptionId) {
    Fail 'the active az subscription differs from the one being passed to the verifier; it would fail post-wake' 2
}

Write-Step 'Checking portfolio-service against its attestation'
$pRevision = & $AzCommand containerapp show --name $PortfolioApp --resource-group $ResourceGroup --query 'properties.latestReadyRevisionName' -o tsv
if ($LASTEXITCODE -ne 0) { Fail 'could not read portfolio-service' 2 }
if (-not $pRevision) { Fail 'the portfolio-service read returned no revision name' 2 }
$pImage = & $AzCommand containerapp show --name $PortfolioApp --resource-group $ResourceGroup --query 'properties.template.containers[0].image' -o tsv
if ($LASTEXITCODE -ne 0) { Fail 'could not read the portfolio-service image' 2 }
if (-not $pImage) { Fail 'the portfolio-service read returned no image reference' 2 }
$pAttestedRevision = $provenance.services.$PortfolioApp.revision
$pAttestedDigest = $provenance.services.$PortfolioApp.digest
if ($pRevision -ne $pAttestedRevision) {
    Fail "portfolio-service serving '$pRevision' does not match attested '$pAttestedRevision' -- STOP" 2
}
if ([string]$pImage -notlike "*@$pAttestedDigest") {
    Fail "portfolio-service image does not carry attested digest -- STOP" 2
}
Write-Step "  $pRevision at the attested digest"

Write-Step 'Checking the Log Analytics workspace is readable'
$wsId = & $AzCommand monitor log-analytics workspace show --workspace-name $Workspace --resource-group $ResourceGroup --query customerId -o tsv
if ($LASTEXITCODE -ne 0 -or -not $wsId) { Fail 'could not read the Log Analytics workspace; the verifier would fail post-wake' 2 }
Write-Step '  workspace readable'

Write-Step 'Checking the verifier can start'
& $PythonCommand $VerifierPath --help > $null
if ($LASTEXITCODE -ne 0) { Fail 'the verifier could not be started by this interpreter' 2 }
$evidenceDir = Split-Path $EvidenceOutput -Parent
if ($evidenceDir -and -not (Test-Path $evidenceDir)) { Fail "evidence directory does not exist: $evidenceDir" 2 }
Write-Step '  verifier starts and the evidence directory exists'

Write-Step 'Checking no newer gateway revision exists'
$revisions = @(& $AzCommand containerapp revision list --name $GatewayApp --resource-group $ResourceGroup --query '[].name' -o tsv)
if ($LASTEXITCODE -ne 0) { Fail 'could not list revisions' 2 }
# An empty list would make "none newer than the attested revision" vacuously
# true -- the same emptiness class as the digest guards above. The app always
# has at least the serving revision, so nothing here is a legitimate zero.
if ($revisions.Count -eq 0) { Fail 'the revision list came back empty; the attestation cannot be checked' 2 }
$newer = @($revisions | Where-Object { $_ -and $_ -gt $attestedRevision })
if ($newer.Count -gt 0) {
    Fail "a newer revision exists: $($newer -join ', ') -- the attestation may be invalidated; STOP" 2
}
Write-Step "  $($revisions.Count) revision(s), none newer than $attestedRevision"

# --- The timing window starts here ------------------------------------------

if ($SkipWake) {
    # Test-only in effect, not just in the docstring: refuse unless every
    # external command is a stub. Otherwise an operator could wake by hand,
    # see a non-200, and run with -SkipWake to evade the stop this script
    # exists to enforce -- which it cannot detect.
    # Each comparison is parenthesised deliberately: in PowerShell the comma
    # binds tighter than -eq, so @($a -eq 'x', $b -eq 'y') parses as
    # $a -eq ('x', $b) -eq 'y' and the guard silently never fires.
    $defaults = @(
        ($AzCommand -eq 'az'),
        ($DockerCommand -eq 'docker'),
        ($CurlCommand -eq 'curl.exe'),
        ($PythonCommand -eq 'python')
    )
    if ($defaults -contains $true) {
        Fail '-SkipWake is for the offline tests. It refuses to run against real commands, because it would bypass the non-200 stop the packet requires. Override every one of -AzCommand, -DockerCommand, -CurlCommand and -PythonCommand to use it.' 2
    }
    Write-Step 'SkipWake set: not issuing a wake request'
    Write-Host 'WARNING: -SkipWake bypasses the wake and its non-200 stop. If a wake was issued by hand and did not return 200, stop now: continuing is a fresh owner decision.' -ForegroundColor Yellow
} else {
    Write-Step "Waking: GET $GatewayUrl$WakePath (one request, no retry, no client timeout)"
    $script:WakeIssued = $true
    $wakeStatus = & $CurlCommand -sS -o NUL -w '%{http_code}' "$GatewayUrl$WakePath"
    $wakeCurlExit = $LASTEXITCODE
    Write-Step "  wake responded HTTP $wakeStatus (curl exit $wakeCurlExit)"
    if ($wakeCurlExit -ne 0) {
        # Only DNS/connect/TLS-handshake failures imply the request never
        # reached the ingress. 28/52/56/18 and friends all happen AFTER it was
        # delivered, so the wake may well be spent -- do not claim otherwise.
        $preDelivery = @(6, 7, 35)
        $verdictText = if ($preDelivery -contains $wakeCurlExit) {
            'the request did not reach the ingress, so the wake was probably NOT consumed'
        } else {
            'the request may already have been delivered, so the wake may be consumed'
        }
        Fail "the wake failed in transport (curl exit $wakeCurlExit): $verdictText. Re-waking is a fresh owner decision." 3
    }
    if (-not $wakeStatus) {
        Fail 'the wake returned no status code, so it cannot be shown to have been 200. The packet requires stopping here.' 3
    }
    if ($wakeStatus -ne '200') {
        # The authorization packet is explicit: "On any non-200, stop and
        # report -- do not re-issue the request", and it carries no override
        # clause. The custom-domain runbook does not authorize proceeding
        # either; its warm-up tolerance sits inside a loop that still waits for
        # three consecutive 200s.
        #
        # There is deliberately NO flag to continue anyway. This script cannot
        # verify that an owner decided to proceed, so a switch here would turn a
        # prohibition into an operator keystroke. Continuing requires a fresh
        # owner decision and, if it is to become routine, an amended packet.
        Fail "the wake returned HTTP $wakeStatus, not 200. The packet requires stopping here, and this script has no override. The wake is consumed; continuing is a fresh owner decision." 3
    }
}

Write-Step "Waiting up to ${ReplicaWaitSeconds}s for a replica (read-only polling)"
$deadline = (Get-Date).AddSeconds($ReplicaWaitSeconds)
$replica = $null
$pollErrors = 0
$sawNotReady = $false
while ((Get-Date) -lt $deadline) {
    $raw = & $AzCommand containerapp replica list --name $GatewayApp --resource-group $ResourceGroup --revision $attestedRevision -o json
    if ($LASTEXITCODE -ne 0) {
        # An az failure is not the same as "no replica yet". Count it so the
        # final message can distinguish a tooling fault from scale-to-zero,
        # but do not abort on one transient error and lose the wake.
        $pollErrors++
        Start-Sleep -Seconds $ReplicaPollSeconds
        continue
    }
    # Decode first, THEN wrap. @($raw | ConvertFrom-Json) does not do this:
    # ConvertFrom-Json emits a JSON array as a single Object[], so @(...) wraps
    # it again and element 0 is the whole array. An empty list then threw on
    # .name under StrictMode and aborted the wait on its first poll -- which is
    # the normal state in the seconds after a wake.
    $parsed = @()
    if ($raw) {
        try { $decoded = $raw | ConvertFrom-Json; $parsed = @($decoded) } catch { $parsed = @() }
    }
    # Only the FIRST listed replica matters: the verifier execs into
    # replicas[0] (verify_demo_reset_azure.py), so readiness of any other
    # replica is irrelevant to whether its exec will succeed.
    foreach ($candidate in @($parsed | Select-Object -First 1)) {
        $name = $null
        try { $name = $candidate.name } catch { $name = $null }
        if (-not $name) { continue }
        # A listed replica is not necessarily a usable one: the verifier's exec
        # has no readiness gate, so require Running here rather than accepting
        # the first name that appears.
        # Readiness. The production shape is RAW ARM:
        #   { id, name, type, properties: { runningState, containers: [ { ready, runningState, ... } ] } }
        # `az containerapp replica list` is a passthrough -- the installed
        # containerapp extension's list_replicas returns the client result
        # unmodified -- and this script passes no --query.
        #
        # An earlier revision read a flat {name, containers:[...]} shape taken
        # from docs/evidence/b1-task-6-6/g2b-serving-proof-20260903.json. That
        # file is a --query PROJECTION, not raw output: its sibling revision
        # entries are projected too ({active,health,images,...}). The flat form
        # is still read below so a projected input also works, but the ARM form
        # is what production returns.
        $states = @()
        $ready = @()
        foreach ($expr in @(
            { $candidate.properties.runningState },
            { $candidate.properties.containers[0].runningState },
            { $candidate.containers[0].runningState }
        )) {
            $v = $null
            try { $v = & $expr } catch { $v = $null }
            if ($v) { $states += "$v" }
        }
        foreach ($expr in @(
            { $candidate.properties.containers[0].ready },
            { $candidate.containers[0].ready }
        )) {
            $v = $null
            try { $v = & $expr } catch { $v = $null }
            if ($null -ne $v) { $ready += [bool]$v }
        }

        # Any stated not-ready wins, from either the replica state or the
        # container. These are checked separately so a fixture can exercise
        # each on its own.
        if ($ready -contains $false) { $sawNotReady = $true; continue }
        if (@($states | Where-Object { $_ -ne 'Running' }).Count -gt 0) { $sawNotReady = $true; continue }

        if ($states.Count -eq 0 -and $ready.Count -eq 0) {
            # No readiness information at all. Accepted deliberately: the
            # verifier's exec will attempt this replica regardless, and refusing
            # on a field the API may not populate would spend the wake for
            # nothing. Announced so it is visible in the transcript.
            Write-Step '  (replica reports no runningState; accepting)'
        }
        $replica = $name
        break
    }
    if ($replica) { break }
    Start-Sleep -Seconds $ReplicaPollSeconds
}

if (-not $replica) {
    $why = "no ready replica appeared within ${ReplicaWaitSeconds}s."
    if ($pollErrors -gt 0) { $why += " NOTE: $pollErrors of the polls failed to reach Azure, so this may be a tooling fault rather than scale-to-zero." }
    if ($sawNotReady) { $why += " A replica was listed but never reached Running." }
    Fail "$why The wake is consumed; a further wake is a fresh owner decision." 3
}
Write-Step "  replica up: $replica"

# --- The verifier -----------------------------------------------------------
# Mode is fixed. --threshold-override is never passed. No credential env var is
# set by this script, so execute mode could not run even if it were requested.

Write-Step 'Starting preflight'
$verifierArgs = @(
    $VerifierPath,
    '--mode', 'preflight',
    '--target', $Target,
    '--subscription', $SubscriptionId,
    '--resource-group', $ResourceGroup,
    '--gateway-app', $GatewayApp,
    '--portfolio-app', $PortfolioApp,
    '--workspace', $Workspace,
    '--registry', $Registry,
    '--gateway-url', $GatewayUrl,
    '--deployment-provenance', $ProvenancePath,
    '--evidence-output', $EvidenceOutput,
    '--operation-timeout-seconds', "$OperationTimeoutSeconds"
)
& $PythonCommand @verifierArgs
$verifierExit = $LASTEXITCODE

} finally {
    $env:TASK8_9_ACCESS_TOKEN = $savedToken
    $env:TASK8_9_DEMO_PASSWORD = $savedPassword
}

Write-Step "verifier exit $verifierExit; evidence at $EvidenceOutput"
if ($verifierExit -ne 0) {
    Fail "preflight did not pass. The wake is consumed; a retry is a fresh owner decision." 4
}

Write-Step 'preflight passed'
Write-Step 'Reminder: this is preflight_passed / go=null. It is NOT a GO and does not close Task 8.9.'
exit 0
