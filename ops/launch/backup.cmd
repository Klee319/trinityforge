@echo off
REM =============================================================================================
REM  Daily backup of the two things that hurt to lose: the TrinityForge progression SQLite and
REM  the MariaDB husksync / luckperms databases.
REM
REM  WORLDS ARE NOT INCLUDED -- Backuper handles main and the resource world is disposable by
REM  design. reset-world.cmd parks worlds under _world-backup-<stamp> when it rebuilds them.
REM
REM  Safe to run while the servers are up: SQLite is copied as the .db / -wal / -shm trio.
REM
REM  Arguments (passed through to backup.ps1):
REM    -DryRun             show what would be copied and where
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

powershell -NoProfile -ExecutionPolicy Bypass -File "%OPS_SCRIPTS%\backup.ps1" %*
exit /b %errorlevel%
