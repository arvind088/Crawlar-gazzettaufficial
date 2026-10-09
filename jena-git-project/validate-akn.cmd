@echo off
setlocal
if "%~1"=="" (
  echo Usage: validate-akn.cmd "XML file or folder" [report.json]
  exit /b 2
)
set "REPORT=%~2"
if "%REPORT%"=="" set "REPORT=akn-validation-report.json"
call mvn -q -DskipTests compile exec:java -Dexec.mainClass=it.legislation.validation.AknOpenDataValidator "-Dakn.input=%~1" "-Dakn.output=%REPORT%"
exit /b %ERRORLEVEL%
