@echo off
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

if defined STUB_AZ_EXIT if not "%STUB_AZ_EXIT%"=="0" exit /b %STUB_AZ_EXIT%

echo %*| findstr /C:"account show" >nul && (
  echo %*| findstr /C:"--query id" >nul && (echo %STUB_SUBSCRIPTION_ID%& exit /b 0)
  echo Test Subscription
  exit /b 0
)
echo %*| findstr /C:"latestReadyRevisionName" >nul && (
  echo %*| findstr /C:"portfolio-service" >nul && (echo %STUB_PORTFOLIO_REVISION%& exit /b 0)
  echo %STUB_SERVING_REVISION%
  exit /b 0
)
echo %*| findstr /C:"containers[0].image" >nul && (
  echo %*| findstr /C:"portfolio-service" >nul && (echo %STUB_PORTFOLIO_IMAGE%& exit /b 0)
  echo %STUB_SERVING_IMAGE%
  exit /b 0
)
echo %*| findstr /C:"log-analytics workspace show" >nul && (echo 83a9c3a2-0000-0000-0000-000000000000& exit /b 0)
echo %*| findstr /C:"replica list" >nul && (
  if "%STUB_REPLICA%"=="none" (echo []& exit /b 0)
  if "%STUB_REPLICA_STATE%"=="" (
    echo [{"name":"api-gateway--0000081-stubreplica","properties":{"runningState":"Running"}}]
  ) else (
    echo [{"name":"api-gateway--0000081-stubreplica","properties":{"runningState":"%STUB_REPLICA_STATE%"}}]
  )
  exit /b 0
)
echo %*| findstr /C:"revision list" >nul && (
  echo api-gateway--0000079
  echo api-gateway--0000081
  if defined STUB_EXTRA_REVISION echo %STUB_EXTRA_REVISION%
  exit /b 0
)
exit /b 0
