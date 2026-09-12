@echo off
rem Offline stub for `docker`. Contacts nothing.
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo docker %*
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo docker-env TASK8_9_ACCESS_TOKEN=[%TASK8_9_ACCESS_TOKEN%] TASK8_9_DEMO_PASSWORD=[%TASK8_9_DEMO_PASSWORD%]
if defined STUB_DOCKER_EXIT (>&2 echo cannot connect to the Docker daemon& exit /b %STUB_DOCKER_EXIT%)
rem STUB_DOCKER_EMPTY: exit 0 having printed no server OS. STUB_DOCKER_OS
rem cannot express this -- an empty value there is indistinguishable from unset.
if defined STUB_DOCKER_EMPTY exit /b 0
rem STUB_DOCKER_FAILAFTER: report a perfectly good server OS and THEN fail.
rem Only the exit-code branch can catch this -- the mode check below is
rem satisfied by the payload. Without it, removing the docker classification
rem changed no behaviour and no behavioural test noticed.
if defined STUB_DOCKER_FAILAFTER (echo linux& exit /b 3)
if "%STUB_DOCKER_OS%"=="" (echo linux) else (echo %STUB_DOCKER_OS%)
exit /b 0
