@echo off
REM =============================================================================================
REM  List what is currently up. Use it before and after starting or stopping.
REM  All the actual output lives in show-status.ps1 (PowerShell handles UTF-8 properly).
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\show-status.ps1" %*
exit /b %errorlevel%
