@echo off
REM =============================================================================================
REM  Install the economy plugin set (VaultUnlocked / Jecon / JeconCacheName / PlaceholderAPI)
REM  on every backend and write the shared-MariaDB Jecon config.yml.
REM
REM  The three backends share ONE MariaDB database (jecon), not one config file. plugins\Jecon is
REM  a real directory on every backend -- only plugins\TrinityForge is a junction -- so the same
REM  config.yml is written three times on purpose.
REM
REM  Refuses to run while any backend is up: overwriting a jar in a live JVM ends in
REM  NoClassDefFoundError and only a full stop -> start recovers.
REM
REM  Arguments (passed through to install-economy-plugins.ps1):
REM    -DryRun                     show the plan, write nothing, do not ask for the password
REM    -SourceDir <path>           where the jars are (default: Test_1.21.11\plugins)
REM    -TemplatePath <path>        Jecon config.yml master (default: ops\templates\jecon.config.yml)
REM    -PlaceholderApiJar <path>   PlaceholderAPI jar from somewhere else
REM
REM  The MariaDB password is never an argument. It comes from TF_JECON_DB_PASSWORD, or is typed in.
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md and ..\RUNBOOK.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\install-economy-plugins.ps1" %*
exit /b %errorlevel%
