@echo off
rem Precision check by hand: 'sample' draws a reference set, 'evaluate' (default) computes precision.
rem Run from anywhere: the script moves into the application folder.
setlocal
cd /d "%~dp0..\jena-git-project" || exit /b 1
set MODE=%~1
if "%MODE%"=="" set MODE=evaluate
call mvn -B -q compile exec:java "-Dexec.mainClass=it.legislation.ingest.ReferenceSetRunner" "-Dexec.args=%MODE%"
exit /b %ERRORLEVEL%
