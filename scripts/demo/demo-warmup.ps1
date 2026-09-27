#Requires -Version 5.1
<#
  Desktop demo: bounded warm-up and keep-alive for the scale-to-zero backends.
  Operator runs it in its own PowerShell window about 15 minutes before the audience:
    powershell -NoProfile -ExecutionPolicy Bypass -File "<this file>"

  One independent loop per health path (its own background job), running from the start:
    - until the path first answers 200: a probe (timeout 150 s), then -WarmupRetrySec;
    - after that: a probe with timeout -ProbeTimeoutSec, then -IntervalSec.
  A hung or slow service delays only its own heartbeat. A service that is ready early keeps being
  probed while the others warm up, so it cannot go idle during the warm-up.

  The controlling loop (this window) decides, using a 200 no older than -FreshSec per path:
    GO        - all four paths have a fresh 200. Only now start the walkthrough.
    NOT READY - no GO by the warm-up deadline (-WarmupLimitSec after start): exit code 2.
    During the keep-alive it prints a STATUS line whenever freshness changes (GO, or NOT READY with
    the stale paths).
  Bounds are enforced here, not by the jobs: at the warm-up deadline (no GO) or at GO +
  -KeepAliveMinutes, the jobs are stopped, including any probe in flight. The only overrun is the
  few seconds needed to stop and remove the background jobs. Ctrl+C also stops them.

  Makes only anonymous GETs of the four public health paths. No credentials, no writes.
#>
param(
  [string]$Api = 'https://api.vibhanshu-ai-portfolio.dev',
  [string[]]$Paths = @('/actuator/health', '/api/portfolio/health', '/api/market/health', '/api/insights/health'),
  [int]$WarmupLimitSec = 600,
  [int]$WarmupRetrySec = 15,
  [double]$KeepAliveMinutes = 45,
  [int]$ProbeTimeoutSec = 20,
  [int]$IntervalSec = 60,
  [int]$FreshSec = 120,
  [string]$LogFile = ''
)
$ErrorActionPreference = 'Stop'

function Write-Line([string]$Text) {
  $line = '{0:HH:mm:ss}Z {1}' -f (Get-Date).ToUniversalTime(), $Text
  Write-Host $line
  if ($LogFile) { [IO.File]::AppendAllText($LogFile, $line + "`n") }
}

# Per-path loop. Emits "<ISO time> <path> 200|not-200" after every probe.
$pathJob = {
  param([string]$Url, [string]$Path, [int]$RetrySec, [int]$TimeoutSec, [int]$IntervalSec)
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  $ready = $false
  while ($true) {
    # 150 s = the gateway's own response timeout. Deadlines are enforced by the controlling loop,
    # which stops this job (and any probe in flight) at the warm-up or keep-alive deadline.
    $t = if ($ready) { $TimeoutSec } else { 150 }
    $ok = $false
    try { $ok = ((Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec $t).StatusCode -eq 200) } catch { }
    '{0:o} {1} {2}' -f (Get-Date).ToUniversalTime(), $Path, $(if ($ok) { '200' } else { 'not-200' })
    if ($ok) { $ready = $true }
    Start-Sleep -Seconds $(if ($ready) { $IntervalSec } else { $RetrySec })
  }
}

# The warm-up decision, checked in this order so the limit is exact: nothing after the deadline
# can grant GO, even a 200 that arrived in the last poll interval.
function Get-WarmupDecision([datetime]$NowUtc, [datetime]$DeadlineUtc, [int]$StaleCount) {
  if ($NowUtc -ge $DeadlineUtc) { return 'NOTREADY' }
  if ($StaleCount -eq 0) { return 'GO' }
  return 'WAIT'
}

function Invoke-DemoWarmup {
$jobs = @()
try {
  $start = (Get-Date).ToUniversalTime()
  $warmDeadline = $start.AddSeconds($WarmupLimitSec)
  Write-Line ("warm-up: {0} paths, independent loops; GO needs a 200 from each within {1} s; limit {2:HH:mm:ss}Z" -f $Paths.Count, $FreshSec, $warmDeadline)
  $jobs = @($Paths | ForEach-Object { Start-Job -ScriptBlock $pathJob -ArgumentList ($Api + $_), $_, $WarmupRetrySec, $ProbeTimeoutSec, $IntervalSec })

  $last200 = @{}
  $go = $null; $end = $null; $status = ''
  while ($true) {
    foreach ($line in @($jobs | ForEach-Object { Receive-Job $_ })) {
      Write-Line "probe: $line"
      $parts = "$line" -split ' '
      if ($parts.Count -eq 3 -and $parts[2] -eq '200') {
        $last200[$parts[1]] = [datetime]::Parse($parts[0], [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AdjustToUniversal)
      }
    }
    $now = (Get-Date).ToUniversalTime()
    $stale = @($Paths | Where-Object { -not $last200.ContainsKey($_) -or ($now - $last200[$_]).TotalSeconds -gt $FreshSec })
    if ($null -eq $go) {
      $decision = Get-WarmupDecision $now $warmDeadline $stale.Count
      if ($decision -eq 'GO') {
        $go = $now; $end = $go.AddMinutes($KeepAliveMinutes); $status = 'GO'
        Write-Line ("GO: all {0} services answered 200 within the last {1} s. Keep-alive continues until {2:HH:mm:ss}Z (Ctrl+C stops)." -f $Paths.Count, $FreshSec, $end)
      } elseif ($decision -eq 'NOTREADY') {
        $missing = if ($stale.Count -gt 0) { $stale -join ', ' } else { 'none; the last one arrived after the limit' }
        Write-Line ("NOT READY: GO was not reached within the {0} s limit (no fresh 200 from: {1}). Use the operator script fallbacks, or postpone." -f $WarmupLimitSec, $missing)
        exit 2
      }
    } else {
      $s = if ($stale.Count -eq 0) { 'GO' } else { "NOT READY (no 200 within $FreshSec s: $($stale -join ', '))" }
      if ($s -ne $status) { $status = $s; Write-Line "STATUS: $s" }
      if ($now -ge $end) { Write-Line 'keep-alive: finished'; exit 0 }
    }
    Start-Sleep -Milliseconds 500
  }
} finally {
  # Defence in depth, on every exit and on Ctrl+C. Background-job processes also end with this
  # window (the offline tests pass even without this block, including a hard kill), so this only
  # makes the stop explicit and tidy.
  if ($jobs.Count -gt 0) { $jobs | Stop-Job -ErrorAction SilentlyContinue; $jobs | Remove-Job -Force -ErrorAction SilentlyContinue }
}
}

# Dot-sourcing (the offline tests) only defines the functions.
if ($MyInvocation.InvocationName -ne '.') { Invoke-DemoWarmup }
