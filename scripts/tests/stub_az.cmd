@echo off
setlocal enabledelayedexpansion
rem Offline stub for the `az` CLI. Contacts nothing.
rem
rem It is a .cmd, and specifically one that expands %* INSIDE a parenthesised
rem IF block, because that is what the real shim does:
rem
rem     @IF EXIST "%~dp0\..\python.exe" (
rem        SET AZ_INSTALLER=MSI
rem        "%~dp0\..\python.exe" -IBm azure.cli %*
rem     ) ELSE (
rem
rem That block is the fault line. An unquoted ")" inside any argument closes the
rem block early, which is how `--query "length(@)"` became `length(@` and broke
rem three hand-issued commands on 2026-09-12. A stub that expanded %* on a plain
rem unparenthesised line would NOT reproduce it, and the regression test that
rem depends on it would pass vacuously.
rem
rem Every invocation appends its argv verbatim to %STUB_CAPTURE% so tests can
rem assert on exactly what survived the crossing.

IF EXIST "%~f0" (
  call :main %*
) ELSE (
  exit /b 9
)
exit /b %ERRORLEVEL%

:main
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo az %*
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo az-env TASK8_9_ACCESS_TOKEN=[%TASK8_9_ACCESS_TOKEN%] TASK8_9_DEMO_PASSWORD=[%TASK8_9_DEMO_PASSWORD%]

rem STUB_POLL_EXIT fails only the replica-list poll, so the pre-wake checks
rem still pass and the poll-error branch can actually be exercised.
if defined STUB_POLL_EXIT echo %*| findstr /C:"replica list" >nul && exit /b %STUB_POLL_EXIT%

rem STUB_AZ_FAIL_MATCH (optionally ANDed with STUB_AZ_FAIL_MATCH2) fails one
rem specific az phase, so every pre-wake call site can be exercised on its own
rem rather than through a single representative call.
if defined STUB_AZ_FAIL_MATCH (
  echo %*| findstr /C:"%STUB_AZ_FAIL_MATCH%" >nul && (
    if not defined STUB_AZ_FAIL_MATCH2 exit /b 3
    echo %*| findstr /C:"%STUB_AZ_FAIL_MATCH2%" >nul && exit /b 3
  )
)
if defined STUB_AZ_EXIT if not "%STUB_AZ_EXIT%"=="0" exit /b %STUB_AZ_EXIT%

rem STUB_AZ_EMPTY_MATCH makes one phase exit 0 with NO stdout. This is not a
rem hypothetical: `az ... --query <path> -o tsv` exits 0 and prints nothing
rem whenever the JMESPath does not resolve. The exit-code branches cannot see
rem it, so it is the only fixture that exercises the emptiness guards.
if defined STUB_AZ_EMPTY_MATCH (
  echo %*| findstr /C:"%STUB_AZ_EMPTY_MATCH%" >nul && (
    if not defined STUB_AZ_EMPTY_MATCH2 exit /b 0
    echo %*| findstr /C:"%STUB_AZ_EMPTY_MATCH2%" >nul && exit /b 0
  )
)

rem STUB_AZ_FAILAFTER_MATCH emits the NORMAL payload and then exits non-zero --
rem a command that produced a plausible value but still failed. Only the
rem $LASTEXITCODE branch can catch that; every downstream value comparison is
rem satisfied. Without this fixture the exit-code branches whose phase has a
rem value guard behind it are untestable, and branch-removal mutation showed
rem they survived.
set _RC=0
if defined STUB_AZ_FAILAFTER_MATCH (
  echo %*| findstr /C:"%STUB_AZ_FAILAFTER_MATCH%" >nul && (
    if not defined STUB_AZ_FAILAFTER_MATCH2 (set _RC=3) else (
      echo %*| findstr /C:"%STUB_AZ_FAILAFTER_MATCH2%" >nul && set _RC=3
    )
  )
)

echo %*| findstr /C:"account show" >nul && (
  echo %*| findstr /C:"--query id" >nul && (echo %STUB_SUBSCRIPTION_ID%& exit /b !_RC!)
  echo Test Subscription
  exit /b !_RC!
)
echo %*| findstr /C:"latestReadyRevisionName" >nul && (
  echo %*| findstr /C:"portfolio-service" >nul && (echo %STUB_PORTFOLIO_REVISION%& exit /b !_RC!)
  echo %STUB_SERVING_REVISION%
  exit /b !_RC!
)
echo %*| findstr /C:"containers[0].image" >nul && (
  echo %*| findstr /C:"portfolio-service" >nul && (echo %STUB_PORTFOLIO_IMAGE%& exit /b !_RC!)
  echo %STUB_SERVING_IMAGE%
  exit /b !_RC!
)
echo %*| findstr /C:"log-analytics workspace show" >nul && (echo 83a9c3a2-0000-0000-0000-000000000000& exit /b !_RC!)
echo %*| findstr /C:"replica list" >nul && (
  rem Payloads are RAW ARM, which is what `az containerapp replica list` returns:
  rem the containerapp extension's list_replicas is a passthrough and this
  rem wrapper passes no --query. An earlier stub used the flat
  rem {name,containers:[...]} shape copied from
  rem docs/evidence/b1-task-6-6/g2b-serving-proof-20260903.json -- but that file
  rem is a --query PROJECTION (its revision entries are projected too), so the
  rem flat shape does not occur in raw output. The "flat" fixture below keeps
  rem that path covered anyway, since a projected input should still work.
  rem Names are deliberately NOT alphabetical, so a Sort-Object selection
  rem diverges from first-listed.
  if "%STUB_REPLICA%"=="none" (echo []& exit /b !_RC!)
  if "%STUB_REPLICA%"=="multi" (echo [{"id":"/subscriptions/x/replicas/zeta-9f2","name":"zeta-9f2","type":"Microsoft.App/containerApps/revisions/replicas","properties":{"runningState":"Running","containers":[{"name":"api-gateway","ready":true,"runningState":"Running"}]}},{"id":"/subscriptions/x/replicas/alpha-3c1","name":"alpha-3c1","type":"Microsoft.App/containerApps/revisions/replicas","properties":{"runningState":"NotRunning","containers":[{"name":"api-gateway","ready":false,"runningState":"NotRunning"}]}}]& exit /b !_RC!)
  if "%STUB_REPLICA%"=="multi-reversed" (echo [{"id":"/subscriptions/x/replicas/zeta-9f2","name":"zeta-9f2","type":"Microsoft.App/containerApps/revisions/replicas","properties":{"runningState":"NotRunning","containers":[{"name":"api-gateway","ready":false,"runningState":"NotRunning"}]}},{"id":"/subscriptions/x/replicas/alpha-3c1","name":"alpha-3c1","type":"Microsoft.App/containerApps/revisions/replicas","properties":{"runningState":"Running","containers":[{"name":"api-gateway","ready":true,"runningState":"Running"}]}}]& exit /b !_RC!)
  if "%STUB_REPLICA%"=="stateless" (echo [{"name":"rep-nostate"}]& exit /b !_RC!)
  rem replica-level state only, no container block at all.
  if "%STUB_REPLICA%"=="armshape" (echo [{"name":"rep-arm","properties":{"runningState":"NotRunning"}}]& exit /b !_RC!)
  if "%STUB_REPLICA%"=="flat" (echo [{"name":"zeta-9f2","containers":[{"ready":true,"runningState":"Running"}]}]& exit /b !_RC!)
  rem Each not-ready signal on its own, so deleting either predicate is caught.
  if "%STUB_REPLICA%"=="notready-only" (echo [{"id":"/subscriptions/x/replicas/zeta-9f2","name":"zeta-9f2","type":"Microsoft.App/containerApps/revisions/replicas","properties":{"runningState":"Running","containers":[{"name":"api-gateway","ready":false,"runningState":"Running"}]}}]& exit /b !_RC!)
  if "%STUB_REPLICA%"=="badstate-only" (echo [{"id":"/subscriptions/x/replicas/zeta-9f2","name":"zeta-9f2","type":"Microsoft.App/containerApps/revisions/replicas","properties":{"runningState":"Running","containers":[{"name":"api-gateway","ready":true,"runningState":"Waiting"}]}}]& exit /b !_RC!)
  if "%STUB_REPLICA%"=="ripening" (
    if not defined STUB_STATE_FILE (echo []& exit /b !_RC!)
    if not exist "%STUB_STATE_FILE%" (>"%STUB_STATE_FILE%" echo 1& echo []& exit /b !_RC!)
    set /p _n=<"%STUB_STATE_FILE%"
    if "!_n!"=="1" (>"%STUB_STATE_FILE%" echo 2& echo [{"id":"/subscriptions/x/replicas/zeta-9f2","name":"zeta-9f2","type":"Microsoft.App/containerApps/revisions/replicas","properties":{"runningState":"NotRunning","containers":[{"name":"api-gateway","ready":false,"runningState":"NotRunning"}]}}]& exit /b !_RC!)
    echo [{"id":"/subscriptions/x/replicas/zeta-9f2","name":"zeta-9f2","type":"Microsoft.App/containerApps/revisions/replicas","properties":{"runningState":"Running","containers":[{"name":"api-gateway","ready":true,"runningState":"Running"}]}}]
    exit /b !_RC!
  )
  if "%STUB_REPLICA_STATE%"=="" (
    echo [{"id":"/subscriptions/x/replicas/zeta-9f2","name":"zeta-9f2","type":"Microsoft.App/containerApps/revisions/replicas","properties":{"runningState":"Running","containers":[{"name":"api-gateway","ready":true,"runningState":"Running"}]}}]
  ) else (
    echo [{"name":"zeta-9f2","properties":{"runningState":"%STUB_REPLICA_STATE%","containers":[{"name":"api-gateway","ready":false,"runningState":"%STUB_REPLICA_STATE%"}]}}]
  )
  exit /b !_RC!
)
echo %*| findstr /C:"revision list" >nul && (
  echo api-gateway--0000079
  echo api-gateway--0000081
  if defined STUB_EXTRA_REVISION echo %STUB_EXTRA_REVISION%
  exit /b !_RC!
)
exit /b !_RC!
