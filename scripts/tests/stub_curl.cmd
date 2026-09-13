@echo off
setlocal
rem Offline stub for `curl.exe` -- the activation probes. Contacts nothing.
rem
rem It does not merely record its arguments: it ENFORCES them. An earlier stub
rem echoed a status regardless of what it was called with, and every test only
rem counted `curl` lines. Review then showed that --retry (several HTTP
rem requests from one invocation), -L (follow a redirect and report the
rem target's 200), an altered -w, and a different URL all passed the suite,
rem because nothing ever looked at the argument vector.
rem
rem The only argument vector an activation probe may use is exactly:
rem     -q -sS -o <body> -w t89:%{http_code}:%{time_total}:%{content_type} --max-time 30 https://api.vibhanshu-ai-portfolio.dev/actuator/health
rem -q MUST be first: it makes real curl ignore any ambient config file (_curlrc /
rem .curlrc), so a probe cannot be given a retry, redirect, or alternate URL by
rem mutable machine configuration. --max-time 30 bounds each probe.
rem
rem <body> is the ONLY position allowed to vary, and only to a newly generated
rem local temporary file: directly in the temp directory, named t89-wake-*.body,
rem and not already present. That refuses `-o -` (body to stdout, where it would
rem reach the transcript), `-o NUL` (no capture), a path elsewhere, and a reused
rem file. A path containing a space arrives quoted and cannot satisfy the vector
rem comparison below, so it is refused too -- fail-closed.
rem
rem Anything else is recorded as a violation, prints NO metadata, and exits 99 --
rem so the wrapper sees no valid record and must stop.
rem
rem Per-call behaviour. Each probe in one wrapper run is numbered 1, 2, 3, ...
rem from a counter kept next to STUB_STATE_FILE (the harness allocates one per
rem wrapper invocation). For every field below, STUB_CURL_<n>_<FIELD> applies to
rem call <n> only, and STUB_CURL_<FIELD> to every call that has no per-call value:
rem   EXIT      curl exit code                       (default 0)
rem   STATUS    %{http_code}                         (default 200)
rem   TIME      %{time_total}                        (default 0.012345)
rem   CT        %{content_type}; `none` prints empty (default application/json)
rem   BODY      a local file copied byte-for-byte into <body>; `none` creates no
rem             body file, as a DNS or connect failure leaves it
rem             (default: writes {"status":"UP"})
rem   META      replaces the whole metadata line; `none` prints no metadata
rem   META2     a second metadata line (a multi-record response)
rem   STDERR    writes a curl-style warning naming <body> to stderr
rem   SENTINEL  records `curl-sentinel-consumed`: the call must never be made
rem Values are plain tokens; none of the fixtures needs & | < > ^ or %.
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo curl %*
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo curl-env TASK8_9_ACCESS_TOKEN=[%TASK8_9_ACCESS_TOKEN%] TASK8_9_DEMO_PASSWORD=[%TASK8_9_DEMO_PASSWORD%]

rem %% is a literal percent inside a batch file, so the right-hand side below is
rem the single-percent vector curl actually receives. %4 is the body path, the
rem one position allowed to vary; it is constrained separately below.
if not "%*"=="-q -sS -o %4 -w t89:%%{http_code}:%%{time_total}:%%{content_type} --max-time 30 https://api.vibhanshu-ai-portfolio.dev/actuator/health" goto :violation
rem GetTempPath resolves TMP first, then TEMP.
set "_tmpdir=%TMP%"
if not defined _tmpdir set "_tmpdir=%TEMP%"
if /I not "%~dp4"=="%_tmpdir%\" goto :violation
set "_name=%~n4"
if not "%_name:~0,9%"=="t89-wake-" goto :violation
if not "%~x4"==".body" goto :violation
if exist "%~4" goto :violation

set "_n=1"
if not defined STUB_STATE_FILE goto :counted
if exist "%STUB_STATE_FILE%.curl" set /p _n=<"%STUB_STATE_FILE%.curl"
set /a _next=_n+1
>"%STUB_STATE_FILE%.curl" echo %_next%
:counted
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo curl-call %_n%
if %_n% GTR 5 if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo curl-over-budget %_n%

call :pick EXIT
call :pick STATUS
call :pick TIME
call :pick CT
call :pick BODY
call :pick META
call :pick META2
call :pick STDERR
call :pick SENTINEL
if not defined _EXIT set "_EXIT=0"
if not defined _STATUS set "_STATUS=200"
if not defined _TIME set "_TIME=0.012345"
if not defined _CT set "_CT=application/json"
if "%_CT%"=="none" set "_CT="
if defined _SENTINEL if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo curl-sentinel-consumed %_n%

if "%_BODY%"=="none" goto :stderr
if defined _BODY goto :copybody
>"%~4" echo {"status":"UP"}
goto :stderr
:copybody
copy /b /y "%_BODY%" "%~4" >nul
:stderr
if defined _STDERR >&2 echo Warning: Failed to create the file %~4: stub-injected

if "%_META%"=="none" goto :rc
if defined _META goto :rawmeta
echo t89:%_STATUS%:%_TIME%:%_CT%
goto :meta2
:rawmeta
echo %_META%
:meta2
if defined _META2 echo %_META2%
:rc
exit /b %_EXIT%

:pick
rem _<FIELD> = STUB_CURL_<n>_<FIELD>, else STUB_CURL_<FIELD>, else undefined.
set "_%1="
call set "_%1=%%STUB_CURL_%_n%_%1%%"
if not defined _%1 call set "_%1=%%STUB_CURL_%1%%"
exit /b 0

:violation
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo curl-violation unauthorized argument vector
exit /b 99
