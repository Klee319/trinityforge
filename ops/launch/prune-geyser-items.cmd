@echo off
REM =============================================================================================
REM  Remove only the entries from GeyserExtra's custom_items.json that the pack scan rebuilds
REM  on every start.
REM
REM  custom_items.json is a PERSISTENT LEDGER, not a cache: entries that cannot be re-observed
REM  are gone for good if you delete the file. That is why this prunes selectively.
REM
REM  Arguments (passed through to prune-geyser-auto-items.ps1):
REM    -Apply              actually write; without it nothing changes
REM    -MaxDelete <n>      refuse to delete more than n entries (default: 150)
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\prune-geyser-auto-items.ps1" %*
exit /b %errorlevel%
