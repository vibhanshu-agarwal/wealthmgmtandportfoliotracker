@echo off
rem Offline stub for the verifier invocation. Runs nothing, contacts nothing.
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo python %*
rem --help is the wrapper's pre-wake "can the verifier start" probe; it must
rem succeed even when the test is simulating a failing run.
echo %*| findstr /C:"--help" >nul && exit /b 0
if "%STUB_VERIFIER_EXIT%"=="" (exit /b 0)
exit /b %STUB_VERIFIER_EXIT%
