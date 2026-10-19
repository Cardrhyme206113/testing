@echo off
setlocal
cd /d "%~dp0"
echo ==================================================
echo Minecraft LCE - local Fix113 playable build
echo ==================================================
echo The "Ruffle players" in diagnostics are Flash
echo UI/movie instances, NOT multiplayer clients.
echo.
where py >nul 2>nul
if not errorlevel 1 (
    py -3 server.py
    goto :end
)
where python >nul 2>nul
if not errorlevel 1 (
    python server.py
    goto :end
)
echo ERROR: Python 3 not found.
echo Install Python 3, then run this file again.
echo No administrator rights or remote hosting needed.
pause
:end
endlocal
