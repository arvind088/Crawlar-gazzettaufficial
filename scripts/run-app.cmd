@echo off
rem Starts the web application on http://localhost:8080.
rem Run from anywhere: the script moves into the application folder.
setlocal
cd /d "%~dp0..\jena-git-project" || exit /b 1
call mvn -B spring-boot:run
exit /b %ERRORLEVEL%
