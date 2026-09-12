@echo off
rem Offline stub for `curl.exe` -- the wake request. Contacts nothing.
rem Records each call so a test can assert the wake is issued exactly once.
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo curl %*
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo curl-env TASK8_9_ACCESS_TOKEN=[%TASK8_9_ACCESS_TOKEN%] TASK8_9_DEMO_PASSWORD=[%TASK8_9_DEMO_PASSWORD%]
rem STUB_CURL_EMPTY: exit 0 having written no status at all. A distinct knob
rem from STUB_WAKE_STATUS, which cannot express "printed nothing" -- an empty
rem value there is indistinguishable from unset.
if defined STUB_CURL_EMPTY goto :rc
if "%STUB_WAKE_STATUS%"=="" (echo 200) else (echo %STUB_WAKE_STATUS%)
:rc
if defined STUB_CURL_EXIT exit /b %STUB_CURL_EXIT%
exit /b 0
