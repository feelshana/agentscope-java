@echo off
REM =====================================================
REM data-analysis 启动脚本（Windows）
REM 使用方式：双击或在 cmd 中执行 bin\start.bat
REM =====================================================

SET APP_HOME=%~dp0..
SET JAR_FILE=%APP_HOME%\lib\data-analysis.jar
SET DEPS_DIR=%APP_HOME%\lib\deps\*
SET CONF_DIR=%APP_HOME%\conf
SET LOG_DIR=%APP_HOME%\logs
SET PROFILE=dev

IF NOT EXIST "%LOG_DIR%" MKDIR "%LOG_DIR%"

echo Starting data-analysis with profile: %PROFILE% ...

java -Xms512m -Xmx1024m ^
  -cp "%DEPS_DIR%;%JAR_FILE%" ^
  io.agentscope.examples.dataanalysis.DataAnalysisApplication ^
  --spring.profiles.active=%PROFILE% ^
  --spring.config.location=classpath:/,file:%CONF_DIR%/

pause
