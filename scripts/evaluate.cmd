@echo off
rem Checks by hand. Precision: 'sample' then 'evaluate' (default). Recall: 'recall-sample' then 'recall'.
rem Run from anywhere: the script moves into the application folder.
setlocal
cd /d "%~dp0..\jena-git-project" || exit /b 1
set MODE=%~1
if "%MODE%"=="" set MODE=evaluate
set RUNNER=ReferenceSetRunner
if /i "%MODE:~0,6%"=="recall" set RUNNER=RecallSetRunner
call mvn -B -q compile exec:java "-Dexec.mainClass=it.legislation.ingest.%RUNNER%" "-Dexec.args=%MODE%"
exit /b %ERRORLEVEL%
