@echo off
REM =============================================================================================
REM  Write the resource pack URL and its SHA-1 into a backend's server.properties.
REM
REM  The SHA-1 is not decoration: with a wrong or missing hash the client re-downloads the pack
REM  on every join, and a stale cached pack is served silently when it does match by accident.
REM
REM  Arguments (REQUIRED, passed through to set-resource-pack.ps1):
REM    -Url <url>          where the pack zip is published
REM    -Sha1 <hash>        SHA-1 of that exact zip
REM    -Server <dir>       backend directory name (default: Main_Server)
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\set-resource-pack.ps1" %*
exit /b %errorlevel%
