@echo off
REM =============================================================================================
REM  Proxy: Velocity
REM
REM  START THIS LAST. Bring it up first and players hit a "connects but cannot travel" state.
REM  Velocity itself is meant to stay up: while a backend restarts, FallbackRouter moves the
REM  players on it to the other backend, so the network never fully goes down.
REM
REM  ASCII ONLY -- see launch-config.cmd. Japanese notes are in README.md.
REM =============================================================================================
call "%~dp0launch-config.cmd" || exit /b 1

if not exist "%VELOCITY_ROOT%\%VELOCITY_JAR%" (
    echo [ERROR] Velocity jar not found: %VELOCITY_ROOT%\%VELOCITY_JAR%
    echo         Set VELOCITY_JAR in launch-config.cmd to the actual file name.
    exit /b 1
)

start "velocity" /D "%VELOCITY_ROOT%" java -Xms%HEAP_VELOCITY% -Xmx%HEAP_VELOCITY% ^
  -XX:+UseG1GC -XX:+ParallelRefProcEnabled ^
  -XX:MaxGCPauseMillis=200 -XX:+AlwaysPreTouch ^
  -jar "%VELOCITY_JAR%"
exit /b 0
