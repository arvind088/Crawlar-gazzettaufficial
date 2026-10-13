@echo off
rem Full first build: download, import, Gazzetta check, relations. Takes hours; safe to re-run.
rem Run from anywhere: the script moves into the application folder.
setlocal
cd /d "%~dp0..\jena-git-project" || exit /b 1
call mvn -B -q compile exec:java "-Dexec.mainClass=it.legislation.source.normattiva.NormattivaCorpusRunner" "-Dexec.args=%*" || exit /b 1
call mvn -B -q compile exec:java "-Dexec.mainClass=it.legislation.ingest.AknImportRunner" || exit /b 1
call mvn -B -q compile exec:java "-Dexec.mainClass=it.legislation.ingest.GazzettaCheckRunner" || exit /b 1
call mvn -B -q compile exec:java "-Dexec.mainClass=it.legislation.ingest.RelationRunner"
exit /b %ERRORLEVEL%
