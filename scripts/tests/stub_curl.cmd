@echo off
rem Offline stub for `curl.exe` -- the wake request. Contacts nothing.
rem Records each call so a test can assert the wake is issued exactly once.
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo curl %*
if "%STUB_WAKE_STATUS%"=="" (echo 200) else (echo %STUB_WAKE_STATUS%)
if defined STUB_CURL_EXIT exit /b %STUB_CURL_EXIT%
exit /b 0
