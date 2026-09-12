@echo off
rem Offline stub for `docker`. Contacts nothing.
if defined STUB_CAPTURE >>"%STUB_CAPTURE%" echo docker %*
if "%STUB_DOCKER_OS%"=="" (echo linux) else (echo %STUB_DOCKER_OS%)
exit /b 0
