<#
.SYNOPSIS
    Runs the B2 Task 8.9 bounded activation sequence + read-only preflight as
    ONE sequence.

    Written for and tested against Windows PowerShell 5.1, which this script now
    enforces at startup: it refuses to run (exit 2, before any child process)
    under any other PSEdition or major version. Other editions have not been
    validated for this safety-critical wrapper.

.DESCRIPTION
    The Task 8.9 verifier cannot resolve a gateway replica while api-gateway is
    scaled to zero. Inbound HTTP activates the existing revision without
    creating a new one; the replica then idles out after roughly 300 seconds.
    The 2026-09-12 attempt failed because the checks and the run were issued by
    hand, one paste at a time, and the window expired before the verifier
    reached its replica-list call.

    This wrapper exists to take everything that does not need to be inside the
    window out of it. All preconditions -- Azure session, Docker, the live
    revision/digest comparison against the attestation -- run BEFORE any
    request. Only the activation sequence, a bounded read-only wait for the
    replica, and the verifier itself happen after it.

    The activation sequence. An earlier revision issued exactly one request and
    stopped unless it returned 200. Both Run A attempts recorded HTTP 503 on
    that request (the 2026-09-12 figure is operator-reported), and
    API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md records an earlier first contact with
    this gateway that timed out, then three 503s, then a 200 -- consistent with
    a scale-from-zero cold start. So the sequence now issues AT MOST SIX probes
    of the same fixed GET, each bounded by --max-time 90, five seconds apart:
      * exit 0 and HTTP 200           -> activation confirmed; no further probe
      * exit 0 and HTTP 503           -> recorded cold-start outcome; probe again
      * curl exit 28 and status 000   -> recorded cold-start timeout; probe again
      * six such outcomes, no 200     -> stop, exit 3
      * anything else                 -> stop at once, exit 3
    Live-run worst case: six 90 s probes and five 5 s intervals, 565 s. This is
    an upper bound, not a claim that probes
    normally run to the curl timeout. Every probe issued belongs to the
    sequence and is treated as consumed; any request beyond it is a fresh owner
    decision. The per-probe cap was raised from 30 s to 90 s, and the budget
    from five probes to six, by the 2026-09-13 governing activation policy
    (docs/superpowers/plans/2026-09-13-b2-task-8-9-activation-policy.md), which
    supersedes the 2026-09-12 readiness packet's activation instructions.

    Each probe prints one bounded fingerprint line: probe number, UTC start,
    curl exit, HTTP status, curl's time_total, an allowlisted content-type
    label, body length and SHA-256, and whether the body is actuator-shaped JSON
    (a top-level scalar "status") with an allowlisted status token. The body,
    headers, raw content type and the temporary body file's path are never
    printed. An 'unclassified' body is not evidence of where a response came
    from.

    Deliberately NOT supported, and refused rather than ignored:
      * execute mode          -- the mode is fixed to 'preflight'
      * --threshold-override  -- never passed; it forecloses GO and creates a revision
      * credentials           -- never passed to any child process. The two
                                 TASK8_9_* values ARE read and cleared for the
                                 duration, then restored in the finally; they
                                 are never logged, written to disk, or sent on
      * curl retries or redirects, or more than six probes -- the request budget
                                 ($script:ProbeBudget) is a literal, assigned
                                 once outside the safety unit and read only
                                 inside it; it is not an input
      * ambient curl configuration -- two separate controls, both literal:
                                 -q (first) makes curl skip its configuration
                                 files (.curlrc / _curlrc). It does NOT touch
                                 proxy environment variables (https_proxy,
                                 HTTPS_PROXY, ALL_PROXY), so --noproxy '*'
                                 follows it and makes curl bypass any proxy for
                                 every host. Other environment inputs curl may
                                 read are not neutralized by either

    Exit codes:
      0  preflight passed
      2  a precondition failed BEFORE any request (nothing was consumed)
      3  the activation sequence started and the run stopped after it -- six
         probes without a 200, any outcome other than 200/503/timeout, a
         malformed metadata record, a probe-internal or cleanup failure, or no
         ready replica. Treat every probe issued as consumed unless the message
         says otherwise; a further request is a fresh owner decision. (For curl
         exits 6/7/35 the message says that probe probably never reached the
         ingress; the exit code stays 3 because that is the conservative
         reading, and any earlier probe in the sequence was issued.)
      4  the verifier ran and did not pass (the sequence is consumed)

.PARAMETER SkipWake
    Runs the sequence without issuing any probe. Intended for the offline tests.
    It is NOT a way to continue past a failed activation sequence: probing by
    hand, seeing it fail and then running with -SkipWake evades the stop this
    script exists to enforce, and the script cannot detect that. Using it
    against production is an owner decision.

.PARAMETER WakeProbeIntervalSeconds
    Seconds between probes. Default '5', the production value; any live run
    must omit it or pass exactly 5 -- enforced below (see $isLiveRun): a
    potentially live invocation (at least one of -AzCommand, -DockerCommand,
    -CurlCommand and -PythonCommand still at its literal default) that requests any other
    interval exits 2 before any child process. Accepted only as a whole number
    0..30 written plainly (no sign, fraction, exponent, whitespace or leading
    zero), validated before any child process; anything else exits 2. It is a
    [string] so that invalid input reaches that validation instead of failing
    parameter binding (exit 1) or being rounded. The offline tests pass 0. It
    changes neither the six-probe budget nor the 90-second per-probe bound.

.PARAMETER EvidenceOutput
    Where the verifier writes its evidence document. Defaults to
    docs/evidence/b2-task-8-9/rehearsal-<yyyyMMdd>.json (inside the repository)
    when omitted. Refused with exit 2 before any request if the resolved path
    already exists -- evidence is never overwritten.

    For a potentially live run ($isLiveRun: at least one of -AzCommand,
    -DockerCommand, -CurlCommand and -PythonCommand still at its literal default), the
    resolved path must also fall OUTSIDE the repository, checked before any
    request -- see the evidence-path externality comment further down for why.
    Consequently the in-repo default above is always rejected for a live run;
    a live invocation must pass an explicit -EvidenceOutput that resolves
    outside the repository. Resolution uses PowerShell's own current location
    ($PWD), not the process's, so a relative path is safe to reason about even
    across a `cd` earlier in the same session.

.PARAMETER AzCommand
.PARAMETER DockerCommand
.PARAMETER CurlCommand
.PARAMETER PythonCommand
    Injection points so the sequence can be exercised against local stubs that
    capture arguments. They exist because every hand-issued failure in this
    task's history was an argument-quoting fault at the PowerShell/az.bat
    boundary, not a logic error -- that boundary needs to be testable offline.
    The live-run guards ($isLiveRun) compare these four values against their
    LITERAL defaults only: a full path or an alternate spelling of a real tool
    (C:\Windows\System32\curl.exe, curl) counts as overridden, so once all four
    are spelled non-literally the interval and evidence-path guards no longer
    apply. They catch the accidental fully-default case; they cannot tell a
    stub from a real tool.
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
    [string]$WakeProbeIntervalSeconds = '5',
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
    $consumed = if ($script:WakeIssued) { 'THE WAKE WAS ISSUED: at least one activation probe was sent, and every probe sent is consumed; a further request is a fresh owner decision.' }
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

# Windows PowerShell 5.1 gate, checked before any child process runs -- indeed,
# before anything else in the script from this point on. This wrapper is
# written for and tested against Windows PowerShell 5.1 only (see the header).
# Other editions have not been validated for this safety-critical wrapper.
# #Requires -Version 5.1 is deliberately NOT used: PowerShell 7 satisfies that
# minimum version number and would pass the very gate it exists to block --
# 5.1 is Windows PowerShell's own version, but "at least 5.1" is also true of
# 7.x.
if ($PSVersionTable.PSEdition -ne 'Desktop' -or $PSVersionTable.PSVersion.Major -ne 5) {
    Fail "this script requires Windows PowerShell 5.1 (PSEdition 'Desktop', major version 5); running under PSEdition '$($PSVersionTable.PSEdition)', version $($PSVersionTable.PSVersion)" 2
}

# --- SAFETY-CRITICAL UNIT: the activation sequence and the decision after it ---
#
# The verifier may start only after this function returns, and it returns only
# when a probe -- one invocation of this fixed request, with this fixed URL and
# argument vector -- exited 0 and reported exactly the scalar status '200'
# within a sequence of at most six probes in which every earlier probe was an
# exact 503 or a curl timeout. Everything that decides that lives in this
# function, and its structure keeps the decision self-contained and hard to
# change by accident:
#
#   * the URL, every curl argument, the probe budget ($script:ProbeBudget, a
#     literal 6 assigned once just below, outside the function) and the
#     90-second bound are literals. No parameter, environment value or
#     configuration input feeds them; only the pause between probes is a
#     (validated) parameter;
#   * -q is the first argument, so curl skips its configuration files
#     (~/.curlrc, %APPDATA%\_curlrc): a probe cannot be given a retry, a
#     redirect, or another URL by a config file. -q does NOT affect proxy
#     environment variables (https_proxy, HTTPS_PROXY, ALL_PROXY), so
#     --noproxy '*' comes immediately after it: '*' is curl's single wildcard
#     for "every host", so no environment-configured proxy is used and the
#     request goes directly to the literal URL. '*' is quoted so PowerShell
#     passes the literal character;
#   * the body goes to a fresh temporary file per probe, never to the
#     transcript, and is removed in a finally. curl's own stderr is discarded
#     because it names that file on a local write failure;
#   * the metadata must be ONE [string] record matching an exact pattern.
#     Nothing is repaired: an empty, multi-line (a redirect or retry printing
#     twice) or malformed record stops the sequence;
#   * the status is local and never returned. The caller gets no value to branch
#     on: if this function returns, a probe was an exact 200;
#   * every stop ends in a direct `exit 3` -- a non-retryable outcome, an
#     internal error, a cleanup failure, exhaustion. `exit` is a keyword, so no
#     alias, function definition or trap can intercept it, and it never goes
#     through Fail, which is ordinary code that could be redefined. An exit
#     inside the try still runs the finally (Windows PowerShell 5.1; the suite
#     proves it by leftover-file checks on every stop path).
#
# The test suite adds targeted regression guards over this function's structure
# (one definition, one call, one curl invocation with these arguments inside one
# `for` bounded by $script:ProbeBudget -- a literal 6, assigned once and read
# nowhere else as a raw number -- the exact retryable set, four direct exit-3
# stops, one return after a 200, its decision variables read nowhere else, and
# only allowlisted values printed). They catch accidental structural drift;
# earlier guards that pinned only a condition's shape were defeated by edits
# just outside it. These structural guards are not proof against a deliberate
# in-unit rewrite -- that is a code-review responsibility. The runtime guarantee
# is fail-closed: without an exact 200 inside the budget, the verifier does not
# run.
#
# The probe budget. A literal, not a parameter -- see the "Deliberately NOT
# supported" list above: the request budget must not become an input. Assigned
# once here, outside the function, and read only inside it (the loop bound and
# the progress strings below), so the announced ceiling and the actual loop
# bound can never drift apart: they are the same variable.
$script:ProbeBudget = 6
function Invoke-AuthorizedWake {
    param(
        [Parameter(Mandatory = $true)][string]$Curl,
        [Parameter(Mandatory = $true)][int]$IntervalSeconds
    )
    Write-Host "==> Activation sequence: at most $script:ProbeBudget probes of GET https://api.vibhanshu-ai-portfolio.dev/actuator/health, each bounded by --max-time 90 (no curl retry, no redirect; curl config files skipped via -q; environment proxies bypassed for every host via --noproxy '*'). Only HTTP 503 or a curl timeout (exit 28, status 000) leads to another probe; the first HTTP 200 ends the sequence. Every probe issued belongs to this sequence and is consumed."
    for ($probe = 1; $probe -le $script:ProbeBudget; $probe++) {
        if ($probe -gt 1) { Start-Sleep -Seconds $IntervalSeconds }
        $bodyPath = Join-Path ([IO.Path]::GetTempPath()) ('t89-wake-' + [guid]::NewGuid().ToString('N') + '.body')
        $activated = $false
        $cleanupFailed = $false
        try {
            $startedUtc = (Get-Date).ToUniversalTime().ToString('o')
            $script:WakeIssued = $true
            $probeMeta = & $Curl -q --noproxy '*' -sS -o $bodyPath -w 't89:%{http_code}:%{time_total}:%{content_type}' --max-time 90 'https://api.vibhanshu-ai-portfolio.dev/actuator/health' 2>$null
            $probeExit = $LASTEXITCODE

            # One record, exactly: t89:<3-digit status>:<seconds>:<content type>.
            # [0-9], not \d (which matches non-ASCII digits); \A..\z, not ^..$
            # ($ also matches before a trailing newline). Case-sensitive.
            $metaOk = $false
            $httpStatus = 'invalid'
            $duration = 'invalid'
            $typeLabel = 'unknown'
            if ($probeMeta -is [string]) {
                $record = [regex]::Match($probeMeta, '\At89:(000|[1-5][0-9][0-9]):([0-9]{1,6}\.[0-9]{1,6}):([^\r\n]*)\z')
                if ($record.Success) {
                    $metaOk = $true
                    $httpStatus = $record.Groups[1].Value
                    $duration = $record.Groups[2].Value
                    # The server's content type is classified, never printed.
                    $media = ($record.Groups[3].Value -split ';', 2)[0].Trim().ToLowerInvariant()
                    if ($media -ceq 'application/json') { $typeLabel = 'application/json' }
                    elseif ([regex]::IsMatch($media, '\Aapplication/[a-z0-9!#$&^_.+-]+\+json\z')) { $typeLabel = 'application/*+json' }
                    elseif ($media -ceq 'text/plain') { $typeLabel = 'text/plain' }
                    elseif ($media -ceq 'text/html') { $typeLabel = 'text/html' }
                    elseif ($media.Length -gt 0) { $typeLabel = 'other' }
                }
            }

            # The exact captured bytes. A missing file is zero bytes: a DNS or
            # connect failure leaves none. A read failure is not guessed around;
            # it reaches the catch below and stops the sequence.
            $bodyBytes = [byte[]]@()
            if (Test-Path -LiteralPath $bodyPath) { $bodyBytes = [IO.File]::ReadAllBytes($bodyPath) }
            $bodySha = (Get-FileHash -InputStream ([IO.MemoryStream]::new($bodyBytes)) -Algorithm SHA256).Hash.ToLowerInvariant()

            # actuator-json only for a JSON object with a top-level scalar
            # "status" (exact key). A parse failure is 'unclassified', never a
            # stop and never echoed: ConvertFrom-Json's error text can quote
            # body content ("Invalid JSON primitive: <token>"), so the catch
            # below discards it. In Windows PowerShell 5.1 a parse failure is a
            # terminating error on its own; -ErrorAction Stop is not what makes
            # it catchable, and is kept only as a defensive default.
            $bodyClass = 'unclassified'
            $statusToken = 'other'
            try {
                $bodyText = [Text.UTF8Encoding]::new($false, $true).GetString($bodyBytes)
                $bodyDoc = ConvertFrom-Json -InputObject $bodyText -ErrorAction Stop
                if ($bodyDoc -is [System.Management.Automation.PSCustomObject]) {
                    $statusProp = $bodyDoc.PSObject.Properties['status']
                    if ($null -ne $statusProp -and $statusProp.Name -ceq 'status') {
                        $statusValue = $statusProp.Value
                        if ($null -ne $statusValue -and -not ($statusValue -is [System.Management.Automation.PSCustomObject]) -and -not ($statusValue -is [array])) {
                            $bodyClass = 'actuator-json'
                            if ($statusValue -is [string] -and [regex]::IsMatch($statusValue, '\A[A-Z][A-Z0-9_]{0,31}\z')) { $statusToken = $statusValue }
                        }
                    }
                }
            } catch {
                $bodyClass = 'unclassified'
                $statusToken = 'other'
            }

            $fingerprint = "==>   probe $probe/$script:ProbeBudget started-utc=$startedUtc curl-exit=$probeExit http=$httpStatus duration-s=$duration content-type=$typeLabel body-bytes=$($bodyBytes.Length) body-sha256=$bodySha body-class=$bodyClass"
            if ($bodyClass -ceq 'actuator-json') { $fingerprint += " actuator-status=$statusToken" }
            Write-Host $fingerprint

            if ($probeExit -eq 0 -and $metaOk -and $httpStatus -ceq '200') {
                $activated = $true
            } elseif ($probeExit -eq 0 -and $metaOk -and $httpStatus -ceq '503') {
                Write-Host "==>   probe $probe/$($script:ProbeBudget): HTTP 503 is a recorded cold-start outcome"
            } elseif ($probeExit -eq 28 -and $metaOk -and $httpStatus -ceq '000') {
                Write-Host "==>   probe $probe/$($script:ProbeBudget): a curl timeout (exit 28, status 000) is a recorded cold-start outcome"
            } else {
                $earlier = ''
                if ($probe -gt 1) { $earlier = " The $($probe - 1) earlier probe(s) in this sequence were issued and are consumed." }
                if ($probeExit -ne 0) {
                    # Only DNS, connect and TLS-handshake failures (6, 7, 35)
                    # suggest this request never reached the ingress as an HTTP
                    # request. Even that is hedged: a failed TLS handshake (35)
                    # happens after a TCP connection to the TLS terminator was
                    # made. Anything later may already have been delivered.
                    if (@(6, 7, 35) -contains $probeExit) {
                        Write-Host "FAIL: probe $probe/$script:ProbeBudget failed in transport (curl exit $probeExit): a DNS, connect or TLS-handshake failure, so this request probably did not reach the ingress as an HTTP request and was probably NOT consumed.$earlier The sequence stops here; re-waking is a fresh owner decision." -ForegroundColor Red
                    } else {
                        Write-Host "FAIL: probe $probe/$script:ProbeBudget failed in transport (curl exit $probeExit): this request may already have been delivered, so it may be consumed.$earlier The sequence stops here; re-waking is a fresh owner decision." -ForegroundColor Red
                    }
                } elseif (-not $metaOk) {
                    Write-Host "FAIL: probe $probe/$script:ProbeBudget did not report exactly one well-formed metadata record, so it cannot be shown to have been 200 or a recorded cold-start outcome. The request is consumed.$earlier The sequence stops here and this script has no override; continuing is a fresh owner decision." -ForegroundColor Red
                } else {
                    # There is deliberately no flag to continue anyway: this
                    # script cannot verify that an owner decided to proceed, so
                    # a switch would turn a prohibition into an operator keystroke.
                    Write-Host "FAIL: probe $probe/$script:ProbeBudget returned HTTP $httpStatus, which is neither 200 nor a recorded cold-start outcome (503, or a curl timeout). The request is consumed.$earlier The sequence stops here and this script has no override; continuing is a fresh owner decision." -ForegroundColor Red
                }
                exit 3
            }
        } catch {
            Write-Host "FAIL: probe $probe/$script:ProbeBudget hit an internal error ($($_.Exception.GetType().Name)); its details are withheld because they can name the temporary body file. Treat the request as consumed. The sequence stops here; a further request is a fresh owner decision." -ForegroundColor Red
            exit 3
        } finally {
            # Runs on every path above, including the exits. A failure to remove
            # the body file is never ignored: $ErrorActionPreference is Continue,
            # so Remove-Item is made terminating and the file is checked again.
            # A directory at the path is not something curl creates; it is left
            # alone and reported rather than removed.
            try {
                if (Test-Path -LiteralPath $bodyPath -PathType Container) {
                    $cleanupFailed = $true
                } elseif (Test-Path -LiteralPath $bodyPath) {
                    Remove-Item -LiteralPath $bodyPath -Force -ErrorAction Stop
                }
                if (Test-Path -LiteralPath $bodyPath) { $cleanupFailed = $true }
            } catch {
                $cleanupFailed = $true
            }
            if ($cleanupFailed) {
                Write-Host "FAIL: probe $probe/$($script:ProbeBudget): its temporary response-body file could not be removed (location withheld). The run stops here, fail-closed." -ForegroundColor Red
            }
        }
        if ($cleanupFailed) { exit 3 }
        if ($activated) {
            Write-Host "==>   probe $probe/$script:ProbeBudget returned HTTP 200: activation confirmed; no further probe will be issued"
            return
        }
    }
    Write-Host "FAIL: $script:ProbeBudget of $script:ProbeBudget activation probes were issued and none returned HTTP 200 (each was HTTP 503 or a curl timeout). The request budget is spent, and this script has no override; any further request is a fresh owner decision." -ForegroundColor Red
    exit 3
}

# Whether each external-command parameter is still at its literal default,
# computed once and shared by every guard below that needs to tell a live
# invocation from an offline test or a stub-based run -- the -SkipWake guard
# immediately below, and the live-run interval and evidence-path checks
# further down. One definition, reused everywhere, so there is no second,
# divergent notion of what "live" means.
#
# It compares the four LITERAL defaults and nothing more: a full path to a real
# tool, or `curl` instead of `curl.exe`, is already "overridden" as far as this
# is concerned. It guards the accidental case and cannot tell a stub from a
# real tool.
#
# Each comparison is parenthesised: in PowerShell the comma binds tighter than
# -eq, so @($a -eq 'x', $b -eq 'y') parses as $a -eq ('x', $b) -eq 'y' and
# the guard would silently never fire.
$commandsAtDefault = @(
    ($AzCommand -eq 'az'),
    ($DockerCommand -eq 'docker'),
    ($CurlCommand -eq 'curl.exe'),
    ($PythonCommand -eq 'python')
)

# -SkipWake is for the offline tests. Refuse it here, BEFORE any child process
# runs, unless every external command has been overridden away from its
# default. Checked this early deliberately: a later check ran only after the
# pre-wake az and docker calls, so a test exercising a default -- or a mutant
# that broke this guard -- would have reached a real tool first.
if ($SkipWake) {
    if ($commandsAtDefault -contains $true) {
        Fail '-SkipWake is for the offline tests. Every one of -AzCommand, -DockerCommand, -CurlCommand and -PythonCommand must be overridden away from its default, because skipping the wake also skips the activation sequence and its stop rules. This check compares the literal defaults only and cannot tell a stub from a real tool.' 2
    }
}

# Fail-closed liveness predicate: if ANY external-command parameter remains at
# its literal default, a real tool may still be reached. Only a fully stubbed
# invocation may bypass the live interval and evidence-path guards. Read by
# both guards so there is one shared definition of "potentially live".
$isLiveRun = ($commandsAtDefault -contains $true)

# -WakeProbeIntervalSeconds, validated before any child process for the same
# reason as the guard above. It is a [string] so every invalid value reaches
# this check: an [int] parameter fails binding with exit 1 under -File for
# non-numeric input, before this script runs, and silently rounds 29.5. Only a
# plain whole number 0..30 passes -- no sign, fraction, exponent, whitespace or
# leading zero ('0' itself is accepted). [0-9] rather than \d, which also
# matches non-ASCII digits; \z rather than $, which also matches before a
# trailing newline. The value is not echoed back.
if (-not [regex]::IsMatch($WakeProbeIntervalSeconds, '\A(?:[0-9]|[12][0-9]|30)\z')) {
    Fail '-WakeProbeIntervalSeconds must be a whole number of seconds from 0 to 30, written without sign, fraction, exponent, whitespace or leading zero. The production value is 5; a live run must omit the parameter or pass exactly 5.' 2
}
$probeIntervalSeconds = [int]$WakeProbeIntervalSeconds

# A live run ($isLiveRun above) must use exactly the production interval; the
# offline tests pass 0, which is why this is conditioned on liveness rather
# than a flat rule. Checked before any child process, same as the format check
# just above.
if ($isLiveRun -and $probeIntervalSeconds -ne 5) {
    Fail '-WakeProbeIntervalSeconds must be omitted or set to exactly 5 for a live run: at least one of -AzCommand, -DockerCommand, -CurlCommand and -PythonCommand is still at its literal default, so this is treated as a potentially live invocation. This compares literal defaults only and cannot reliably distinguish a stub from a real tool.' 2
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

# Ambiguous and prefixed spellings, refused for live runs BEFORE the overwrite
# check below, so the refusal is not masked by a same-named file that Test-Path
# happens to find in the drive's current directory. Drive-relative (`C:x.json`)
# and root-relative (`\x.json`) forms: Windows PowerShell 5.1 reports them as
# rooted even though their base depends on process/drive state, so the guard
# further down would canonicalize them against a different base from the one
# Test-Path and the verifier child use. Any path beginning with two separators
# (UNC, or the `\\?\` and `\\.\` prefixes): GetFullPath keeps such prefixes
# intact, so the repository comparison further down would not see through them.
$isDriveRelative = [regex]::IsMatch($EvidenceOutput, '\A[A-Za-z]:(?:\z|[^\\/])')
$isRootRelative = [regex]::IsMatch($EvidenceOutput, '\A[\\/](?![\\/])')
$isDoubleSeparator = [regex]::IsMatch($EvidenceOutput, '\A[\\/]{2}')
if ($isLiveRun -and ($isDriveRelative -or $isRootRelative -or $isDoubleSeparator)) {
    Fail "-EvidenceOutput must be a fully qualified drive path or an ordinary relative path for a live run; drive-relative and root-relative Windows spellings are refused, as is any path beginning with two separators (UNC, or the \\?\ and \\.\ prefixes): '$EvidenceOutput'." 2
}

if (Test-Path $EvidenceOutput) {
    Fail "evidence output already exists, refusing to overwrite: $EvidenceOutput" 2
}

# Evidence-path externality, live runs only ($isLiveRun above). The repo root
# is this script's own parent directory -- $PSScriptRoot is scripts/, so its
# parent is the repository root -- deliberately not a `git` call: this wrapper
# shells out only to curl/az/python/docker, and adding a git dependency here
# would be a new one. Both the repo root and -EvidenceOutput are canonicalized
# with [IO.Path]::GetFullPath(). A fully qualified drive evidence path can be
# canonicalized directly (the ambiguous and prefixed spellings were refused
# above, before the overwrite check). An
# ordinary relative evidence path is first joined to $PWD.ProviderPath so
# it resolves against PowerShell's current filesystem location, the same base
# used by Test-Path and the verifier child process. Calling GetFullPath on the
# relative value alone would instead use [Environment]::CurrentDirectory,
# which Windows PowerShell does not keep synchronized with $PWD after every
# Set-Location, and could make this guard inspect a different path from the
# one the verifier writes. GetFullPath works for a path that does not exist
# yet, which is the normal case here (unlike Resolve-Path). The result is
# compared case-insensitively with a trailing directory separator appended to
# the repo root so that a prefix SIBLING (e.g. C:\repo-backup\x.json next to
# C:\repo) is not falsely rejected -- without the trailing separator,
# 'C:\repo-backup' would wrongly appear to start with 'C:\repo'.
#
# Like GetFullPath, this is lexical only: it does NOT resolve junctions,
# symlinks or other reparse points. So this is a misuse guard against an
# accidentally in-repo evidence path, not a security boundary against a
# deliberately engineered one.
if ($isLiveRun) {
    $repoRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
    $repoRootWithSep = $repoRoot
    # An explicit [string] cast, not a bare [IO.Path]::DirectorySeparatorChar:
    # that property is a [char], and .NET Framework's String.EndsWith has no
    # EndsWith(char) overload, so passing it relies on PowerShell's own
    # char->string coercion at the method-binding boundary. Casting removes
    # that doubt.
    $dirSep = [string][IO.Path]::DirectorySeparatorChar
    if (-not $repoRootWithSep.EndsWith($dirSep)) {
        $repoRootWithSep += $dirSep
    }
    if ([IO.Path]::IsPathRooted($EvidenceOutput)) {
        $evidenceFull = [IO.Path]::GetFullPath($EvidenceOutput)
    } else {
        $evidenceFull = [IO.Path]::GetFullPath((Join-Path $PWD.ProviderPath $EvidenceOutput))
    }
    if ($evidenceFull.Equals($repoRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
        $evidenceFull.StartsWith($repoRootWithSep, [System.StringComparison]::OrdinalIgnoreCase)) {
        Fail "-EvidenceOutput must resolve outside the repository for a live run: '$EvidenceOutput' resolves to '$evidenceFull', inside '$repoRoot'. This is a lexical, case-insensitive comparison and does not resolve junctions or other reparse points, so it is a misuse guard, not a security boundary." 2
    }
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
# Emptiness is checked SEPARATELY from the comparison below, and before it.
# `az --query <path> -o tsv` exits 0 and prints nothing when the path does not
# resolve, and a no-output native command assigns AutomationNull.
#
# The operator families then diverge, and the split is wider than -like:
#   PATTERN operators   -like -notlike -match -notmatch -replace
#                       enumerate the left operand. AutomationNull enumerates
#                       as empty, so they return an empty collection -- FALSE.
#   COMPARISON operators -eq -ne -lt -le -gt -ge
#                       unwrap it to $null first and return a scalar, so
#                       `$x -ne 'value'` is True as expected.
# So the digest guards were silently inert on an empty read while the revision
# guards beside them were not. Any FUTURE guard written with -notmatch on child
# output would reopen the same hole; catch emptiness explicitly, as here.
# Found by branch-removal mutation on 2026-09-12.
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

Write-Step 'Checking the serving Azure demo-reset timeouts, gateway response ceiling, idle threshold and cloud provider against the ratified attestation'
# `az containerapp revision show` is a control-plane read on the revision
# object itself (like the show/list calls above), not a call into a running
# container, so it works at zero replicas -- no wake needed. Run A attempt 3
# spent an irreversible wake only to learn that the serving
# APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT did not equal the value this
# wrapper asserts below; a mismatch of that class is checked here instead,
# before any request. The three timeout literals compared here MUST stay
# equal to the --eligibility-timeout/--reset-timeout/--overall-timeout
# arguments passed to the verifier further down: AzureTimeoutDriftGuardTest
# (scripts/tests/test_verify_demo_reset_azure.py) pins those against
# infrastructure/terraform/azure/main.tf, ratified in
# docs/evidence/b2-task-8-9/2026-09-14-azure-timeout-ratification.md.
#
# SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT (the gateway
# route's response ceiling) is folded into this SAME expected-name/value
# table, with the SAME absent-is-a-mismatch semantics as the three timeouts
# above -- deliberately, not as an oversight: the ratified rationale for the
# eligibility timeout is `120s < 150s`, i.e. eligibility strictly below the
# route ceiling. api-gateway/src/main/resources/application-prod.yml:52 sets
# `response-timeout: 55s`, so an ABSENT override here would mean the
# effective ceiling is 55s, making `120s < 55s` FALSE and silently
# invalidating the rationale a passing preflight is supposed to attest.
# Absence is therefore a reject, exactly like the three timeouts, never a
# pass. Its literal here MUST stay equal to
# infrastructure/terraform/azure/main.tf:255; AzureTimeoutDriftGuardTest pins
# that equality too.
$expectedAzureTimeoutNames = @(
    'APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT',
    'APP_DEMO_LOGIN_RESET_RESET_TIMEOUT',
    'APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT',
    'SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT'
)
$expectedAzureTimeoutValues = @{
    'APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT'                       = '120s'
    'APP_DEMO_LOGIN_RESET_RESET_TIMEOUT'                             = '30s'
    'APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT'                           = '165s'
    'SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT' = '150s'
}
$envRaw = & $AzCommand containerapp revision show --name $GatewayApp --resource-group $ResourceGroup --revision $attestedRevision --query 'properties.template.containers[0].env' -o json
if ($LASTEXITCODE -ne 0) { Fail 'could not read the serving revision environment; the verifier would fail post-wake' 2 }
# Emptiness is checked separately from parsing, same as the digest/revision
# guards above: a quiet `az --query` miss is not the same as a parse failure,
# and each is reported distinctly rather than crashing or reading as a pass.
if (-not $envRaw) { Fail 'the serving revision environment read returned no output' 2 }
# Decode first, THEN wrap: the pipeline is decoded into a plain variable
# ($envDecoded) first, and @() below wraps THAT variable, never the pipeline
# itself. See the replica-list comment further down for why wrapping the
# pipeline directly instead -- @($envRaw | ConvertFrom-Json) -- gets this
# wrong: ConvertFrom-Json emits a JSON array as a single Object[], so @()
# around the pipeline collects that one object and element 0 becomes the
# whole row array instead of the first row.
$envRows = @()
try { $envDecoded = $envRaw | ConvertFrom-Json; $envRows = @($envDecoded) } catch { Fail 'the serving revision environment did not parse as JSON' 2 }
# Ordinal, not the @{} literal's default comparer: a PowerShell @{} hashtable
# is case-INsensitive on its KEYS (independent of the ordinal-equality fix on
# the VALUE side just below), so a wrongly-cased name -- e.g. a lowercase twin of
# APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT, or a mixed-case twin of
# SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT, with no
# correctly-cased row alongside it -- would satisfy every ContainsKey/indexer
# lookup below by matching case-insensitively. The verifier's own lookup table
# (`names: dict[str, Any]`, scripts/verify_demo_reset_azure.py:700, read via
# `names.get(name, yaml_defaults[name])` at L753) is a Python dict: exact-key,
# case-sensitive. So is the JVM env-var lookup the serving container actually
# performs. A non-uppercase spelling of an expected name must therefore read
# as ABSENT here, exactly as it does in both of those, not as a case-blind
# match that only fails after the wake. [hashtable]::new(...) is a static
# method call, not `New-Object`; matches the ::new idiom already used above
# for [IO.MemoryStream] and [Text.UTF8Encoding].
$observedAzureTimeoutValues = [hashtable]::new([System.StringComparer]::Ordinal)
foreach ($row in $envRows) {
    $rowName = $null
    try { $rowName = $row.name } catch { $rowName = $null }
    if (-not $rowName) { continue }
    # A duplicated name is mirrored from the verifier
    # (scripts/verify_demo_reset_azure.py), which raises on it rather than
    # silently keeping the first copy. Keeping the first copy here would let
    # a duplicated name whose first copy is correct pass this pre-wake
    # boundary and then fail the verifier post-wake -- precisely the
    # wake-costing class this boundary exists to remove.
    if ($observedAzureTimeoutValues.ContainsKey($rowName)) {
        # $rowName is deployment-controlled -- read verbatim from the serving
        # Container App's env by the az call above, same trust boundary as
        # $observedValue below -- and reaches Fail, i.e. Write-Host, i.e.
        # this operator's console transcript (captured as evidence under
        # docs/evidence/b2-task-8-9/), so it is sanitized with the exact same
        # rule as $observedValue.
        #
        # The rule is a printable-ASCII allowlist, [^\x20-\x7E], not a list of
        # Unicode categories. An earlier revision matched on the four
        # categories control (Cc), format (Cf), line separator (Zl) and
        # paragraph separator (Zp), and missed U+00AD (SOFT HYPHEN): the .NET
        # Framework regex engine's legacy Unicode category table matches
        # U+00AD as category Pd (dash punctuation), not Cf -- a property of
        # that regex engine's own category table, not of the .NET Framework's
        # Unicode data generally, since CharUnicodeInfo on the same host
        # reports U+00AD as UnicodeCategory.Format. The same run's U+FEFF
        # fixture passed, because that engine does match U+FEFF as category
        # Cf; U+034F (COMBINING GRAPHEME JOINER, category Mn) was never in
        # the old class at all. Adding categories piecemeal chases this
        # table one gap at a time. Every ratified name and value in this
        # precondition is printable ASCII, so anything outside \x20-\x7E is
        # by definition not an approved value and is safe to withhold
        # outright: a length over 80, or any character outside that
        # printable range, replaces the WHOLE name with a fixed-shape
        # description below -- never a partial excerpt, since a truncated
        # prefix of a hostile name is still hostile content.
        $rowNameText = [string]$rowName
        $rowNameHasControlChars = [regex]::IsMatch($rowNameText, '[^\x20-\x7E]')
        $rowNameTooLong = $rowNameText.Length -gt 80
        if ($rowNameHasControlChars -or $rowNameTooLong) {
            $rowNameSanitizeReason = if ($rowNameHasControlChars) { 'withheld: contains a non-printable-ASCII character' } else { 'exceeds the 80-character display bound' }
            $rowNameDisplay = "<sanitized rendering, not the literal value -- $($rowNameText.Length) chars, $rowNameSanitizeReason>"
        } else {
            $rowNameDisplay = $rowNameText
        }
        Fail "duplicate serving environment value: $rowNameDisplay" 2
    }
    # A row may carry `secretRef` instead of `value` (or `value` may be
    # explicitly null); under StrictMode, referencing `.value` on a decoded
    # object that has no such JSON key throws rather than returning $null, so
    # this is wrapped like every other optional-field read in this script. A
    # secretRef row is a mismatch, not a crash: the attested timeouts are
    # plain values, never secret references.
    $rowValue = $null
    try { $rowValue = $row.value } catch { $rowValue = $null }
    $observedAzureTimeoutValues[$rowName] = $rowValue
}
$timeoutMismatches = @()
# [string]::Equals(..., [System.StringComparison]::Ordinal), not -cne: -cne
# is case-sensitive, but it is still a LINGUISTIC (InvariantCulture)
# comparison under the hood, not an ordinal, code-unit-for-code-unit one --
# and the verifier compares these values with Python's != (`if observed !=
# expected:`), which has no culture layer at all, so -cne and != can
# disagree even when both sides already agree on case. The owner
# reproduced this on real Windows PowerShell 5.1 Desktop: '120s' with a
# trailing U+00AD (SOFT HYPHEN), U+FEFF (BOM / ZERO WIDTH NO-BREAK SPACE) or
# U+034F (COMBINING GRAPHEME JOINER) each compares EQUAL to the bare '120s'
# under -cne -- PowerShell's culture-aware comparer treats all three as
# linguistically ignorable -- while both Python's != and .NET's
# StringComparison.Ordinal correctly see them as unequal. (U+200B ZERO
# WIDTH SPACE, raised by an earlier review as an example of the same
# defect, does NOT reproduce: -cne already rejects it, so it is not a
# member of this ignorable-under-linguistic-comparison class and is not
# used in the regression fixtures below for that reason -- a fixture built
# on it would be vacuous, since it already exits 2 without this change.) A
# value carrying a trailing ignorable character -- plausible from a paste
# into `az containerapp update --set-env-vars`, which the Terraform drift
# guard below never sees -- would sail through a -cne gate and then fail
# the verifier post-wake: the exact wake-costing class this preflight
# exists to catch before the wake, not after it. [string]::Equals with
# StringComparison.Ordinal matches Python's != exactly: no culture table,
# no ignorable characters, code-unit-for-code-unit. It also still rejects a
# merely mixed-case value (e.g. '150S'), since that is unequal under
# Ordinal too -- so this subsumes the case-sensitivity rationale this
# comment previously stated on its own.
foreach ($name in $expectedAzureTimeoutNames) {
    $expectedValue = $expectedAzureTimeoutValues[$name]
    if (-not $observedAzureTimeoutValues.ContainsKey($name)) {
        # Absent entirely: the container inherits application.yml's generic
        # default, which is NOT the attested Azure override -- also a
        # mismatch, not a pass.
        $timeoutMismatches += "$name is absent from the serving revision (would inherit the application default); observed=<absent> expected='$expectedValue'"
        continue
    }
    $observedValue = $observedAzureTimeoutValues[$name]
    if ($null -eq $observedValue) {
        $timeoutMismatches += "$name carries a secretRef or a null value instead of a plain string; observed=<no plain value> expected='$expectedValue'"
        continue
    }
    if (-not [string]::Equals([string]$observedValue, $expectedValue, [System.StringComparison]::Ordinal)) {
        # $observedValue is deployment-controlled -- read verbatim from the
        # serving Container App's env by the az call above -- and is the
        # only field in this message that is not one of the wrapper's own
        # literals ($name, $expectedValue). It reaches Fail, i.e. Write-Host,
        # i.e. this operator's console transcript (captured as evidence
        # under docs/evidence/b2-task-8-9/), so it is never interpolated
        # raw. It is sanitized against a printable-ASCII allowlist
        # ([^\x20-\x7E]), not a list of Unicode categories -- see the
        # $rowNameHasControlChars comment above for the ratified rationale
        # and the .NET Framework regex engine's category-table gap that made
        # the category-list approach unreliable. A control character (CR,
        # LF, TAB, ESC), a bidi override, a line/paragraph separator
        # (U+200E, U+202E, U+2028, U+2029), or any other character outside
        # \x20-\x7E (U+00AD, U+FEFF and U+034F included) could inject a fake
        # transcript line, a terminal escape sequence, or visually
        # reorder/break a rendered line, and an unbounded length could blow
        # the transcript up outright. Either one replaces the WHOLE value
        # with a fixed-shape description below -- never a partial excerpt: a
        # truncated PREFIX of a hostile value is still hostile content, so
        # the only safe truncation is to none of it. The description is
        # deliberately shaped so a reader cannot mistake it for a quoted
        # excerpt of what was actually deployed. A plain value within the
        # bound is unaffected and shown exactly as before.
        $observedText = [string]$observedValue
        $observedHasControlChars = [regex]::IsMatch($observedText, '[^\x20-\x7E]')
        $observedTooLong = $observedText.Length -gt 80
        if ($observedHasControlChars -or $observedTooLong) {
            $observedSanitizeReason = if ($observedHasControlChars) { 'withheld: contains a non-printable-ASCII character' } else { 'exceeds the 80-character display bound' }
            $observedDisplay = "<sanitized rendering, not the literal value -- $($observedText.Length) chars, $observedSanitizeReason>"
        } else {
            $observedDisplay = $observedText
        }
        $timeoutMismatches += "$name observed='$observedDisplay' expected='$expectedValue'"
    }
}
# APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD: DELIBERATELY NOT the same rule as the
# four names in the loop above. application.yml:135 is
# `${APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD:30m}`, so an ABSENT override
# correctly falls back to the approved 30m default -- absence is a PASS here.
# Terraform does not set this name, so absent is the expected live state.
# Present-and-not-30m is still a reject: 30m is the only ratified value.
$idleThresholdName = 'APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD'
$idleThresholdExpectedValue = '30m'
if ($observedAzureTimeoutValues.ContainsKey($idleThresholdName)) {
    $idleThresholdObservedValue = $observedAzureTimeoutValues[$idleThresholdName]
    if ($null -eq $idleThresholdObservedValue) {
        $timeoutMismatches += "$idleThresholdName carries a secretRef or a null value instead of a plain string; observed=<no plain value> expected='$idleThresholdExpectedValue'"
    } elseif (-not [string]::Equals([string]$idleThresholdObservedValue, $idleThresholdExpectedValue, [System.StringComparison]::Ordinal)) {
        # $idleThresholdObservedValue is deployment-controlled, read verbatim
        # from the serving Container App's env by the same az call above --
        # same trust boundary as $observedValue in the loop above, so it is
        # sanitized with the exact same rule before it can reach Fail /
        # Write-Host / the operator's evidence transcript: a printable-ASCII
        # allowlist ([^\x20-\x7E]), not a list of Unicode categories -- see
        # the comment above $rowNameHasControlChars for why. A length over
        # 80, or any character outside \x20-\x7E, replaces the WHOLE value
        # -- never a prefix -- with a fixed-shape description.
        $idleThresholdObservedText = [string]$idleThresholdObservedValue
        $idleThresholdHasControlChars = [regex]::IsMatch($idleThresholdObservedText, '[^\x20-\x7E]')
        $idleThresholdTooLong = $idleThresholdObservedText.Length -gt 80
        if ($idleThresholdHasControlChars -or $idleThresholdTooLong) {
            $idleThresholdSanitizeReason = if ($idleThresholdHasControlChars) { 'withheld: contains a non-printable-ASCII character' } else { 'exceeds the 80-character display bound' }
            $idleThresholdObservedDisplay = "<sanitized rendering, not the literal value -- $($idleThresholdObservedText.Length) chars, $idleThresholdSanitizeReason>"
        } else {
            $idleThresholdObservedDisplay = $idleThresholdObservedText
        }
        $timeoutMismatches += "$idleThresholdName observed='$idleThresholdObservedDisplay' expected='$idleThresholdExpectedValue'"
    }
}
# CLOUD_PROVIDER: also DELIBERATELY NOT the same rule as the four names in
# the loop above. scripts/verify_demo_reset_azure.py defaults to 'azure' when
# this name is absent (names.get("CLOUD_PROVIDER", "azure")), so absence is a
# PASS here too. Present-and-not-'azure' is a reject.
$cloudProviderName = 'CLOUD_PROVIDER'
$cloudProviderExpectedValue = 'azure'
if ($observedAzureTimeoutValues.ContainsKey($cloudProviderName)) {
    $cloudProviderObservedValue = $observedAzureTimeoutValues[$cloudProviderName]
    if ($null -eq $cloudProviderObservedValue) {
        $timeoutMismatches += "$cloudProviderName carries a secretRef or a null value instead of a plain string; observed=<no plain value> expected='$cloudProviderExpectedValue'"
    } elseif (-not [string]::Equals([string]$cloudProviderObservedValue, $cloudProviderExpectedValue, [System.StringComparison]::Ordinal)) {
        # Same trust boundary, same sanitiser, as $idleThresholdObservedValue
        # and $observedValue above -- see those comments for the rule.
        $cloudProviderObservedText = [string]$cloudProviderObservedValue
        $cloudProviderHasControlChars = [regex]::IsMatch($cloudProviderObservedText, '[^\x20-\x7E]')
        $cloudProviderTooLong = $cloudProviderObservedText.Length -gt 80
        if ($cloudProviderHasControlChars -or $cloudProviderTooLong) {
            $cloudProviderSanitizeReason = if ($cloudProviderHasControlChars) { 'withheld: contains a non-printable-ASCII character' } else { 'exceeds the 80-character display bound' }
            $cloudProviderObservedDisplay = "<sanitized rendering, not the literal value -- $($cloudProviderObservedText.Length) chars, $cloudProviderSanitizeReason>"
        } else {
            $cloudProviderObservedDisplay = $cloudProviderObservedText
        }
        $timeoutMismatches += "$cloudProviderName observed='$cloudProviderObservedDisplay' expected='$cloudProviderExpectedValue'"
    }
}
if ($timeoutMismatches.Count -gt 0) {
    $timeoutFailMessage = "serving Azure demo-reset timeouts do not match the ratified attestation -- no wake was consumed; reconcile the ratification (docs/evidence/b2-task-8-9/2026-09-14-azure-timeout-ratification.md) with the deployment before retrying: $($timeoutMismatches -join '; ')"
    Fail $timeoutFailMessage 2
}
Write-Step "  serving Azure demo-reset timeouts match the ratified attestation ($($expectedAzureTimeoutNames -join ', '))"
Write-Step "  serving APP_DEMO_LOGIN_RESET_IDLE_THRESHOLD and CLOUD_PROVIDER are absent-safe or match their approved values (absent falls back to the approved default; present must equal it)"

# --- The timing window starts here ------------------------------------------

if ($SkipWake) {
    Write-Step 'SkipWake set: not issuing any activation probe'
    Write-Host 'WARNING: -SkipWake bypasses the activation sequence and its stop rules. If probes were issued by hand and none returned 200, stop now: continuing is a fresh owner decision.' -ForegroundColor Yellow
} else {
    Invoke-AuthorizedWake -Curl $CurlCommand -IntervalSeconds $probeIntervalSeconds
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
    '--operation-timeout-seconds', "$OperationTimeoutSeconds",
    # The verifier's own --eligibility-timeout/--reset-timeout/--overall-timeout
    # defaults (45s/10s/60s) are the generic, non-Azure values from the
    # 2026-09-09 decision (docs/superpowers/plans/2026-09-06-b2-wave8-decision-record.md)
    # -- correct for every deployment except this one. This wrapper targets the
    # attested Azure Production gateway, which Terraform runs with wider,
    # Azure-specific overrides (infrastructure/terraform/azure/main.tf, PR #251),
    # ratified in
    # docs/evidence/b2-task-8-9/2026-09-14-azure-timeout-ratification.md.
    # Literals, like the probe budget and the gateway URL above: not script
    # parameters, so this wrapper cannot silently drift onto an unratified value.
    '--eligibility-timeout', '120s',
    '--reset-timeout', '30s',
    '--overall-timeout', '165s',
    # The verifier's own cross-field guard (_validate_config) requires
    # login-timeout-seconds to exceed overall-timeout regardless of mode,
    # including preflight, where no login is ever attempted (this wrapper
    # never requests execute mode -- see above). Its 120s default was sized
    # for the generic 60s overall deadline (60s orchestration + 60s cold-start
    # headroom); left unset here it would now be 120s <= 165s and this
    # preflight would fail closed on that guard alone. 225s applies the same
    # 60s cold-start headroom on top of the Azure 165s overall deadline.
    '--login-timeout-seconds', '225'
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
