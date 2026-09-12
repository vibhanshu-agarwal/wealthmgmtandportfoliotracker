@echo off
rem Offline stub for the verifier invocation. Runs nothing, contacts nothing.
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo python %*
rem Report the inherited environment, not just argv. Asserting a sentinel is
rem absent from argv proves nothing about what the child process received.
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo python-env TASK8_9_ACCESS_TOKEN=[%TASK8_9_ACCESS_TOKEN%] TASK8_9_DEMO_PASSWORD=[%TASK8_9_DEMO_PASSWORD%]
rem --help is the wrapper's pre-wake "can the verifier start" probe; it must
rem succeed even when the test is simulating a failing run.
echo %*| findstr /C:"--help" >nul && exit /b 0
if "%STUB_VERIFIER_EXIT%"=="" (exit /b 0)
exit /b %STUB_VERIFIER_EXIT%
