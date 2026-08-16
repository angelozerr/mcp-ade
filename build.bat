@echo off
setlocal

set SCRIPT_DIR=%~dp0
set TYCHO_DIR=%SCRIPT_DIR%com.ibm.mcp.jdtls
set PLUGIN_TARGET=%TYCHO_DIR%\com.ibm.mcp.jdtls.plugin\target
set DEST_DIR=%SCRIPT_DIR%extensions\java\src\main\resources\lsp\mcp-jdtls\plugins
echo === Building MCP JDT.LS plugin (Tycho) ===
cd /d "%TYCHO_DIR%"
call "%SCRIPT_DIR%mvnw.cmd" clean package
if errorlevel 1 (
    echo ERROR: Tycho build failed
    exit /b 1
)
echo Tycho build OK

if not exist "%DEST_DIR%" mkdir "%DEST_DIR%"

set JAR=
for %%f in ("%PLUGIN_TARGET%\com.ibm.mcp.jdtls.plugin-*.jar") do (
    echo %%~nf | findstr /i "sources" >nul || set JAR=%%f
)

if "%JAR%"=="" (
    echo ERROR: Plugin JAR not found in %PLUGIN_TARGET%
    exit /b 1
)

copy /y "%JAR%" "%DEST_DIR%\com.ibm.mcp.jdtls.plugin.jar" >nul
echo Copied plugin JAR to %DEST_DIR%

echo.
echo === Building MCP Language Tools (Maven) ===
cd /d "%SCRIPT_DIR%"
call "%SCRIPT_DIR%mvnw.cmd" clean install %*
