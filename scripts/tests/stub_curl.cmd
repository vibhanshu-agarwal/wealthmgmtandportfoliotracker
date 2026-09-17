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
rem     -q --noproxy * -sS -o <body> -w t89:%{http_code}:%{time_total}:%{content_type} --max-time 90 https://api.vibhanshu-ai-portfolio.dev/actuator/health
rem Two separate controls lead it, and each is checked on its own:
rem   -q MUST be first: real curl then skips its configuration files (_curlrc /
rem   .curlrc), so a config file cannot add a retry, redirect or alternate URL.
rem   --noproxy * MUST come immediately after: -q does not touch proxy
rem   environment variables (https_proxy, HTTPS_PROXY, ALL_PROXY), and * is
rem   curl's wildcard for every host, so no environment proxy is used.
rem --max-time 90 bounds each probe.
rem
rem <body> is the ONLY position allowed to vary, and only to a newly generated
rem local temporary file: directly in the temp directory, named t89-wake-*.body,
rem and not already present. That refuses `-o -` (body to stdout, where it would
rem reach the transcript), `-o NUL` (no capture), a path elsewhere, and a reused
rem file. A path containing a space arrives quoted and cannot satisfy the vector
rem comparison below, so it is refused too -- fail-closed.
rem
rem Anything else is recorded as a violation, prints NO metadata, and exits 99 --
rem so the wrapper sees no valid record and must stop. Proxy arguments and a
rem missing, weakened or moved --noproxy * get their own recorded reasons,
rem checked before the whole-vector comparison, so the tests can tell which rule
rem refused them.
rem
rem No argument is ever iterated with `for %%a in (%*)`: that form expands * and
rem ? against the current directory's file names. Arguments are only echoed,
rem compared as text, or read positionally, none of which globs. The
rem curl-noproxy-arg capture line records what positions 2, 3 and 4 actually
rem received, read positionally -- never from a loop over %*.
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
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo curl-env TASK8_9_ACCESS_TOKEN=[%TASK8_9_ACCESS_TOKEN%] TASK8_9_DEMO_PASSWORD=[%TASK8_9_DEMO_PASSWORD%] WAVE9_STEP_A_PASSWORD=[%WAVE9_STEP_A_PASSWORD%]
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo curl-noproxy-arg [%2] [%3] [%4]

rem Proxy and pre-proxy arguments, refused by name: -x / --proxy and every
rem --proxy-* option (including -U / --proxy-user), --preproxy, and every
rem --socks* option. -x and -U are matched in a short-option cluster too
rem (e.g. -sSx), since curl accepts that. Case-sensitive: -X and -u are other
rem options, which the whole-vector comparison refuses anyway.
echo %*| findstr /R /C:"^-[a-zA-Z]*x" /C:" -[a-zA-Z]*x" /C:"^-[a-zA-Z]*U" /C:" -[a-zA-Z]*U" /C:"^--proxy" /C:" --proxy" /C:"^--preproxy" /C:" --preproxy" /C:"^--socks" /C:" --socks" >nul && goto :proxyviolation

rem --noproxy * immediately after -q, exactly: not removed, not narrowed to a
rem host list or an empty list, not moved later in the vector.
echo %*| findstr /B /L /C:"-q --noproxy * -sS " >nul || goto :noproxyviolation
if not "%~2"=="--noproxy" goto :noproxyviolation
if not "%~3"=="*" goto :noproxyviolation

rem %% is a literal percent inside a batch file, so the right-hand side below is
rem the single-percent vector curl actually receives. %6 is the body path, the
rem one position allowed to vary; it is constrained separately below.
if not "%*"=="-q --noproxy * -sS -o %6 -w t89:%%{http_code}:%%{time_total}:%%{content_type} --max-time 90 https://api.vibhanshu-ai-portfolio.dev/actuator/health" goto :violation
rem GetTempPath resolves TMP first, then TEMP, and always returns exactly one
rem trailing backslash -- so TMP=T:\probe\ gives T:\probe\ (one backslash, not two).
rem Strip one trailing backslash from the variable before appending ours, or a
rem host whose TMP ends in \ would refuse the authorized vector.
set "_tmpdir=%TMP%"
if not defined _tmpdir set "_tmpdir=%TEMP%"
if not defined _tmpdir goto :violation
if "%_tmpdir:~-1%"=="\" set "_tmpdir=%_tmpdir:~0,-1%"
rem Compare the 8.3 short form of both directories, not their spelling. GitHub's
rem windows-latest runners publish TMP as an 8.3 alias (C:\Users\RUNNER~1\...)
rem while Windows PowerShell's [IO.Path]::GetTempPath() expands it to the long
rem form, so a textual comparison refused the authorized vector there. %%~fs of
rem an existing directory yields its short form on both sides; a directory that
rem does not exist is left as spelled and still fails closed. (%% is a literal
rem percent here: cmd expands %%~ even inside a rem line.)
for %%I in ("%_tmpdir%\.") do set "_tmpshort=%%~fsI"
for %%I in ("%~dp6.") do set "_bodyshort=%%~fsI"
if /I not "%_bodyshort%"=="%_tmpshort%" goto :violation
set "_name=%~n6"
if not "%_name:~0,9%"=="t89-wake-" goto :violation
if not "%~x6"==".body" goto :violation
if exist "%~6" goto :violation

set "_n=1"
if not defined STUB_STATE_FILE goto :counted
if exist "%STUB_STATE_FILE%.curl" set /p _n=<"%STUB_STATE_FILE%.curl"
set /a _next=_n+1
>"%STUB_STATE_FILE%.curl" echo %_next%
:counted
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo curl-call %_n%
if %_n% GTR 6 if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo curl-over-budget %_n%

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
>"%~6" echo {"status":"UP"}
goto :stderr
:copybody
copy /b /y "%_BODY%" "%~6" >nul
:stderr
if defined _STDERR >&2 echo Warning: Failed to create the file %~6: stub-injected

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

:proxyviolation
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo curl-violation proxy argument
exit /b 99

:noproxyviolation
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo curl-violation noproxy must be exactly * immediately after -q
exit /b 99
