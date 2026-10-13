@echo off
rem Daily update from Normattiva. Options are passed on, e.g.  scripts\daily-update.cmd --dry-run true
rem Run from anywhere: the script moves into the application folder.
setlocal
cd /d "%~dp0..\jena-git-project" || exit /b 1
call mvn -B -q compile exec:java "-Dexec.mainClass=it.legislation.ingest.DailyUpdateRunner" "-Dexec.args=%*"
exit /b %ERRORLEVEL%
