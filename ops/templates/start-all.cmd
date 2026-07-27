@echo off
REM =============================================================================================
REM  SUPERSEDED -- do not use. Kept only so an already-registered scheduled task fails loudly
REM  instead of running a stale, broken copy.
REM
REM  The real thing is ops\launch\, deployed to
REM    D:\game\minecraft\PaperServer\Velocity_for_TF\launch\
REM  by running ops\launch\deploy.cmd from the repository. See ops\launch\README.md
REM  and ops\RUNBOOK.md step 13.
REM
REM  Why this file was retired:
REM    - it duplicated launch-config.cmd's paths and heap sizes in a second place
REM    - its start lines used \" escapes, which batch does not honour, so every backend
REM      was launched with a mangled command line
REM    - it carried Japanese comments; cmd.exe mis-parses UTF-8 batch files (multi-byte
REM      characters make it seek to the wrong byte offset and execute the middle of a line)
REM =============================================================================================

echo [ERROR] This script is superseded. Use the launch folder instead:
echo           D:\game\minecraft\PaperServer\Velocity_for_TF\launch\start-all.cmd
echo         If that folder is missing, run ops\launch\deploy.cmd from the repository.
echo.
echo         If a scheduled task points here, re-register it:
echo           schtasks /create /tn "TF Network" /f /sc onstart /ru SYSTEM /rl HIGHEST ^
echo             /tr "D:\game\minecraft\PaperServer\Velocity_for_TF\launch\start-all.cmd"
exit /b 1
