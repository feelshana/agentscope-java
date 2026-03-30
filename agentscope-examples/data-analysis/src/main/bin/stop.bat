@echo off
REM =====================================================
REM data-analysis 停止脚本（Windows）
REM 使用方式：双击或在 cmd 中执行 bin\stop.bat
REM =====================================================

SET APP_HOME=%~dp0..
SET PID_FILE=%APP_HOME%\data-analysis.pid

IF NOT EXIST "%PID_FILE%" (
    echo data-analysis is not running
    pause
    exit /b 0
)

SET /p PID=<"%PID_FILE%"
echo Stopping data-analysis (PID=%PID%) ...
taskkill /PID %PID% /F
IF %ERRORLEVEL% EQU 0 (
    DEL "%PID_FILE%"
    echo data-analysis stopped.
) ELSE (
    echo Failed to stop process, it may have already exited.
    DEL "%PID_FILE%"
)

pause
