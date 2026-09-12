@echo off
rem Offline stub for the verifier invocation. Runs nothing, contacts nothing.
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo python %*
rem Report the inherited environment, not just argv. Asserting a sentinel is
rem absent from argv proves nothing about what the child process received.
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo python-env TASK8_9_ACCESS_TOKEN=[%TASK8_9_ACCESS_TOKEN%] TASK8_9_DEMO_PASSWORD=[%TASK8_9_DEMO_PASSWORD%]

rem Dispatch without parenthesised blocks: %VAR% inside a `&& ( ... )` block is
rem expanded when cmd parses the whole block, which did not pick up
rem STUB_PYTHON_HELP_EXIT reliably and silently made the --help failure fixture
rem inert. goto avoids the block entirely.
echo %*| findstr /C:"--help" >nul || goto :run

rem --help is the wrapper's pre-wake "can the verifier start" probe. It must
rem succeed even when the test is simulating a failing RUN, so the two are
rem controlled by separate knobs.
if defined STUB_PYTHON_HELP_EXIT exit /b %STUB_PYTHON_HELP_EXIT%
exit /b 0

:run
if not defined STUB_VERIFIER_EXIT exit /b 0
exit /b %STUB_VERIFIER_EXIT%
