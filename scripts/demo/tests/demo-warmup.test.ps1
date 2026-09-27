#Requires -Version 5.1
# Offline tests for demo-warmup.ps1 against test/mock-health-server.mjs. Never contacts Production.
# Timings are scaled down (seconds instead of minutes); the oracles are server-side request
# arrival times, i.e. what the scale-to-zero idle timer would actually see.
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$script = Join-Path (Split-Path -Parent $here) 'demo-warmup.ps1'
$script:fails = 0
function Check([string]$Name, $Actual, $Expected) {
  if ("$Actual" -ceq "$Expected") { Write-Host "  ok   $Name" } else { Write-Host "  FAIL $Name : expected [$Expected] got [$Actual]"; $script:fails++ }
}
$AllPaths = @('/actuator/health', '/api/portfolio/health', '/api/market/health', '/api/insights/health')

function Invoke-Case([string]$Name, [hashtable]$Config, [string[]]$ScriptArgs, [scriptblock]$Assert, [int]$KillAfterSec = 0) {
  Write-Host "== $Name =="
  $work = Join-Path $env:TEMP ('dw-' + [guid]::NewGuid()); New-Item -ItemType Directory $work | Out-Null
  $log = Join-Path $work 'req.jsonl'; New-Item -ItemType File $log | Out-Null
  $cfg = Join-Path $work 'cfg.json'; [IO.File]::WriteAllText($cfg, ($Config | ConvertTo-Json -Depth 4 -Compress))
  if ($Config.Count -eq 0) { [IO.File]::WriteAllText($cfg, '{}') }
  $port = Get-Random -Minimum 20000 -Maximum 40000
  $mock = Start-Process node -ArgumentList @((Join-Path $here 'mock-health-server.mjs'), $port, $log, $cfg) -PassThru -WindowStyle Hidden
  Start-Sleep 1
  try {
    $outFile = Join-Path $work 'out.txt'
    $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $script, '-Api', "http://127.0.0.1:$port", '-LogFile', (Join-Path $work 'script.log')) + $ScriptArgs
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $proc = Start-Process powershell.exe -ArgumentList $argList -PassThru -WindowStyle Hidden -RedirectStandardOutput $outFile
    $null = $proc.Handle   # without this, .NET reports no ExitCode for a -PassThru process
    if ($KillAfterSec -gt 0) { Start-Sleep -Seconds $KillAfterSec; Stop-Process -Id $proc.Id -Force; $null = $proc.WaitForExit(10000) }
    else { $null = $proc.WaitForExit(300000) }
    $elapsed = $sw.Elapsed.TotalSeconds
    $exitAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    Start-Sleep -Seconds 6   # anything arriving after this pause came from a leftover probe
    $reqs = @(Get-Content $log | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json })
    $out = if (Test-Path (Join-Path $work 'script.log')) { Get-Content (Join-Path $work 'script.log') -Raw } else { '' }
    & $Assert $proc.ExitCode $elapsed $reqs $out $exitAt
  } finally { Stop-Process -Id $mock.Id -Force -ErrorAction SilentlyContinue; Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue }
}
# Largest gap in seconds between consecutive arrivals on one path, from the first to the last.
function Get-MaxGap($reqs, [string]$Path) {
  $t = @($reqs | Where-Object { $_.path -eq $Path } | ForEach-Object { [int64]$_.t } | Sort-Object)
  if ($t.Count -lt 2) { return 999 }
  $max = 0; for ($i = 1; $i -lt $t.Count; $i++) { $max = [Math]::Max($max, ($t[$i] - $t[$i - 1]) / 1000.0) }
  return [Math]::Round($max, 1)
}

Write-Host '== defaults are the Production values =='
$ast = [Management.Automation.Language.Parser]::ParseFile($script, [ref]$null, [ref]$null)
$defaults = @{}; $ast.ParamBlock.Parameters | ForEach-Object { $defaults[$_.Name.VariablePath.UserPath] = "$($_.DefaultValue.Extent.Text)" }
Check 'Api' $defaults['Api'] "'https://api.vibhanshu-ai-portfolio.dev'"
Check 'WarmupLimitSec' $defaults['WarmupLimitSec'] '600'
Check 'KeepAliveMinutes' $defaults['KeepAliveMinutes'] '45'
Check 'ProbeTimeoutSec' $defaults['ProbeTimeoutSec'] '20'
Check 'IntervalSec' $defaults['IntervalSec'] '60'

Check 'FreshSec' $defaults['FreshSec'] '120'

Write-Host '== warm-up decision at the exact limit (deterministic) =='
. $script   # defines the functions only; the main loop does not run when dot-sourced
$dl = [datetime]'2026-09-24T12:10:00Z'
Check 'all fresh 1 ms before the limit -> GO' (Get-WarmupDecision $dl.AddMilliseconds(-1) $dl 0) 'GO'
Check 'all fresh exactly at the limit -> NOTREADY (a late 200 cannot grant GO)' (Get-WarmupDecision $dl $dl 0) 'NOTREADY'
Check 'all fresh 0.4 s after the limit -> NOTREADY' (Get-WarmupDecision $dl.AddMilliseconds(400) $dl 0) 'NOTREADY'
Check 'one stale before the limit -> WAIT' (Get-WarmupDecision $dl.AddSeconds(-5) $dl 1) 'WAIT'
Check 'one stale after the limit -> NOTREADY' (Get-WarmupDecision $dl.AddSeconds(1) $dl 1) 'NOTREADY'
Check 'dot-sourcing started no background job' (@(Get-Job).Count) 0

Invoke-Case 'all healthy: GO, then regular heartbeats on every path' @{} @('-WarmupLimitSec', '20', '-KeepAliveMinutes', '0.4', '-IntervalSec', '2', '-ProbeTimeoutSec', '2', '-FreshSec', '6') {
  param($code, $elapsed, $reqs, $out, $exitAt)
  Check 'exit 0' $code 0
  Check 'GO reported' ($out -match 'GO: all 4 services answered 200') 'True'
  foreach ($p in $AllPaths) { Check "$p heartbeats >= 6" (@($reqs | Where-Object { $_.path -eq $p }).Count -ge 6) 'True' }
  foreach ($p in $AllPaths) { Check "$p max gap <= 4 s" ((Get-MaxGap $reqs $p) -le 4) 'True' }
  Check 'no request after exit' (@($reqs | Where-Object { $_.t -gt $exitAt + 1500 }).Count) 0
}

Invoke-Case 'slow start: 503 twice, then 200 -> GO within the limit' @{ '/api/market/health' = @{ fail503 = 2 } } @('-WarmupLimitSec', '20', '-WarmupRetrySec', '1', '-KeepAliveMinutes', '0.05', '-IntervalSec', '1', '-ProbeTimeoutSec', '2', '-FreshSec', '6') {
  param($code, $elapsed, $reqs, $out, $exitAt)
  Check 'exit 0' $code 0
  Check 'GO reported' ($out -match 'GO: all 4') 'True'
}

Invoke-Case 'slow responses (4 s each) still count' @{ '/api/insights/health' = @{ delayMs = 4000 } } @('-WarmupLimitSec', '20', '-KeepAliveMinutes', '0.05', '-IntervalSec', '1', '-ProbeTimeoutSec', '6', '-FreshSec', '10') {
  param($code, $elapsed, $reqs, $out, $exitAt)
  Check 'exit 0' $code 0
  Check 'GO reported' ($out -match 'GO: all 4') 'True'
}

# Codex's handoff case: three paths ready at once, one ready only ~10 s later.
Invoke-Case 'delayed path: the early paths keep being probed, and GO needs a fresh 200 from all four' @{ '/api/market/health' = @{ fail503 = 8 } } @('-WarmupLimitSec', '30', '-WarmupRetrySec', '1', '-KeepAliveMinutes', '0.1', '-IntervalSec', '2', '-ProbeTimeoutSec', '2', '-FreshSec', '5') {
  param($code, $elapsed, $reqs, $out, $exitAt)
  Check 'exit 0' $code 0
  $lines = @($out -split "`n")
  $goAt = [array]::FindIndex($lines, [Predicate[string]] { param($l) $l -match 'GO: all 4' })
  $mkt200 = [array]::FindIndex($lines, [Predicate[string]] { param($l) $l -match '/api/market/health 200' })
  Check 'GO printed' ($goAt -ge 0) 'True'
  Check 'GO only after the delayed path first answered 200' ($mkt200 -ge 0 -and $mkt200 -lt $goAt) 'True'
  $mktFirst200 = @($reqs | Where-Object { $_.path -eq '/api/market/health' })[8].t   # 9th request = first 200
  foreach ($p in '/actuator/health', '/api/portfolio/health', '/api/insights/health') {
    $early = @($reqs | Where-Object { $_.path -eq $p -and $_.t -le $mktFirst200 }).Count
    Check "$p probed repeatedly while the delayed path warmed ($early requests before its first 200)" ($early -ge 3) 'True'
    Check "$p max gap <= 4 s across warm-up and keep-alive" ((Get-MaxGap $reqs $p) -le 4) 'True'
  }
  $mkt = @($reqs | Where-Object { $_.path -eq '/api/market/health' } | ForEach-Object { [int64]$_.t })
  $early = @($reqs | Where-Object { $_.path -eq '/actuator/health' } | ForEach-Object { [int64]$_.t })
  $prior = @($early | Where-Object { $_ -le $mktFirst200 } | Sort-Object)[-1]
  Check ("at the delayed path's first 200, the early path's last 200 was {0:N1} s old (<= FreshSec 5)" -f (($mktFirst200 - $prior) / 1000.0)) ((($mktFirst200 - $prior) / 1000.0) -le 5) 'True'
}

Invoke-Case 'one path hangs from the start: NOT READY at the wall-clock limit' @{ '/api/market/health' = @{ hangAfter = 0 } } @('-WarmupLimitSec', '10', '-WarmupRetrySec', '1', '-IntervalSec', '1', '-ProbeTimeoutSec', '1', '-FreshSec', '5') {
  param($code, $elapsed, $reqs, $out, $exitAt)
  Check 'exit 2' $code 2
  Check 'NOT READY names the hung path' ($out -match 'NOT READY: GO was not reached within the 10 s limit \(no fresh 200 from: /api/market/health\)') 'True'
  Check 'no GO' ($out -match 'GO: all') 'False'
  Check ("elapsed {0:N1} s <= limit 10 s + 6 s (process start and job clean-up)" -f $elapsed) ($elapsed -le 16) 'True'
  Check 'no request after exit' (@($reqs | Where-Object { $_.t -gt $exitAt + 1500 }).Count) 0
}

Invoke-Case 'one path hangs during keep-alive: others keep their heartbeat; status turns NOT READY' @{ '/api/market/health' = @{ hangAfter = 1 } } @('-WarmupLimitSec', '20', '-KeepAliveMinutes', '0.5', '-IntervalSec', '2', '-ProbeTimeoutSec', '3', '-FreshSec', '6') {
  param($code, $elapsed, $reqs, $out, $exitAt)
  Check 'exit 0' $code 0
  foreach ($p in '/actuator/health', '/api/portfolio/health', '/api/insights/health') {
    $g = Get-MaxGap $reqs $p
    Check "$p max gap $g s <= 4 s (interval 2 s; unaffected by the hang)" ($g -le 4) 'True'
  }
  $g = Get-MaxGap ($reqs | Where-Object { $_.t -gt ($reqs | Where-Object { $_.path -eq '/api/market/health' } | Select-Object -First 1).t }) '/api/market/health'
  Check "hung path still re-probed: max gap $g s <= timeout 3 + interval 2 + 2 s slack" ($g -le 7) 'True'
  Check 'STATUS turns NOT READY naming the hung path' ($out -match 'STATUS: NOT READY \(no 200 within 6 s: /api/market/health\)') 'True'
}

Invoke-Case 'keep-alive deadline stops a probe in flight (exact bound)' @{ '/api/market/health' = @{ hangAfter = 1 } } @('-WarmupLimitSec', '20', '-KeepAliveMinutes', '0.2', '-IntervalSec', '1', '-ProbeTimeoutSec', '60', '-FreshSec', '5') {
  param($code, $elapsed, $reqs, $out, $exitAt)
  Check 'exit 0' $code 0
  $lines = @($out -split "`n" | Where-Object { $_ -match '^\d\d:\d\d:\d\dZ' })
  $goT = [datetime]::ParseExact((($lines | Where-Object { $_ -match 'GO: all' } | Select-Object -First 1) -split 'Z')[0], 'HH:mm:ss', $null)
  $endT = [datetime]::ParseExact((($lines | Where-Object { $_ -match 'keep-alive: finished' } | Select-Object -First 1) -split 'Z')[0], 'HH:mm:ss', $null)
  $d = ($endT - $goT).TotalSeconds
  Check ("keep-alive ended {0} s after GO (12 s configured; a 60 s probe was in flight)" -f $d) ($d -ge 11 -and $d -le 14) 'True'
  Check ("whole run {0:N1} s <= 12 s + warm-up + 10 s" -f $elapsed) ($elapsed -le 30) 'True'
  Check 'no request after exit' (@($reqs | Where-Object { $_.t -gt $exitAt + 1500 }).Count) 0
}

Invoke-Case 'window killed during keep-alive: no probe outlives it' @{} @('-WarmupLimitSec', '20', '-KeepAliveMinutes', '5', '-IntervalSec', '1', '-ProbeTimeoutSec', '1', '-FreshSec', '5') -KillAfterSec 12 {
  param($code, $elapsed, $reqs, $out, $exitAt)
  Check 'GO had been reached' ($out -match 'GO: all 4') 'True'
  Check 'no request more than 3 s after the kill' (@($reqs | Where-Object { $_.t -gt $exitAt + 3000 }).Count) 0
}
Write-Host ''
if ($script:fails -eq 0) { Write-Host 'ALL DEMO-WARMUP TESTS PASSED' } else { Write-Host "$($script:fails) DEMO-WARMUP TEST(S) FAILED"; exit 1 }
