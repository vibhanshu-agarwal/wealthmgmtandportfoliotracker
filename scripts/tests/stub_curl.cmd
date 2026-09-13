@echo off
rem Offline stub for `curl.exe` -- the wake request. Contacts nothing.
rem
rem It does not merely record its arguments: it ENFORCES them. An earlier stub
rem echoed a status regardless of what it was called with, and every test only
rem counted `curl` lines. Review then showed that --retry (several HTTP
rem requests from one invocation), -L (follow a redirect and report the
rem target's 200), an altered -w, and a different URL all passed the suite,
rem because nothing ever looked at the argument vector.
rem
rem The only argument vector the authorized wake may use is exactly:
rem     -q -sS -o NUL -w %{http_code} https://api.vibhanshu-ai-portfolio.dev/actuator/health
rem -q MUST be first: it makes real curl ignore any ambient config file (_curlrc /
rem .curlrc), so the one request cannot be given a retry, redirect, or alternate URL
rem by mutable machine configuration.
rem Anything else is recorded as a violation, prints NO status, and exits 99 --
rem so the wrapper sees no valid status and must stop.
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo curl %*
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo curl-env TASK8_9_ACCESS_TOKEN=[%TASK8_9_ACCESS_TOKEN%] TASK8_9_DEMO_PASSWORD=[%TASK8_9_DEMO_PASSWORD%]

rem %% is a literal percent inside a batch file, so the right-hand side below is
rem the single-percent vector curl actually receives.
if not "%*"=="-q -sS -o NUL -w %%{http_code} https://api.vibhanshu-ai-portfolio.dev/actuator/health" goto :violation

rem STUB_CURL_EMPTY: exit 0 having written no status at all. A distinct knob
rem from STUB_WAKE_STATUS, which cannot express "printed nothing" -- an empty
rem value there is indistinguishable from unset.
if defined STUB_CURL_EMPTY goto :rc
if "%STUB_WAKE_STATUS%"=="" (echo 200) else (echo %STUB_WAKE_STATUS%)
:rc
if defined STUB_CURL_EXIT exit /b %STUB_CURL_EXIT%
exit /b 0

:violation
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo curl-violation unauthorized argument vector
exit /b 99
