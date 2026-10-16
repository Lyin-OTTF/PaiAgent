@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ============================================
echo   PaiAgent Frontend - Vite Dev Launcher
echo   URL: http://localhost:5173
echo ============================================
echo.

where npm >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] npm / Node.js not found on PATH.
    echo [ERROR] Please install Node.js LTS first: https://nodejs.org/
    pause
    exit /b 1
)

if not exist "node_modules\" (
    echo [INFO] node_modules not found - installing dependencies...
    call npm install
    if !errorlevel! neq 0 (
        echo [ERROR] npm install failed. See the messages above.
        pause
        exit /b 1
    )
) else (
    echo [INFO] Dependencies found - skipping install.
)

echo.
echo [INFO] Starting Vite dev server...
echo [INFO] Open http://localhost:5173 in your browser.
echo [INFO] Press Ctrl+C to stop the server.
echo.
call npm run dev
set "DEV_EXIT=!errorlevel!"

echo.
if !DEV_EXIT! neq 0 (
    echo [ERROR] Dev server exited with code !DEV_EXIT!.
) else (
    echo [INFO] Dev server stopped.
)
pause
