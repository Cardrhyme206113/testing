@echo off
setlocal
cd /d "%~dp0"
echo =====================================================
echo Minecraft LCE - headed LOCAL browser profiler
echo =====================================================
echo Requirements: Python 3 + Node.js 18+ + Edge or Chrome.
echo This tests the REAL game, with manual UI transitions.
echo No fake menus are created and no GitHub runner needed.
echo.
where node >nul 2>nul
if errorlevel 1 (
    echo ERROR: Node.js was not found. Install Node.js LTS.
    pause
    exit /b 1
)
where npm >nul 2>nul
if errorlevel 1 (
    echo ERROR: npm was not found. Install Node.js LTS.
    pause
    exit /b 1
)
if not exist "tests\node_modules\playwright-core" (
    echo Installing a small, local browser-control library...
    call npm --prefix tests install --no-audit --no-fund
    if errorlevel 1 (
        echo ERROR: npm package setup failed.
        pause
        exit /b 1
    )
)
node tests\profile.cjs
echo.
echo Test complete. Reports are under reports.
pause
endlocal
