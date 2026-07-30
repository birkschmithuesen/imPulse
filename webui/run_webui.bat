@echo off
REM Starter fuer das imPulse-Web-UI, gedacht fuer den Windows-Scheduled-Task
REM "WebUiRun" (siehe README.md). Startet den Flask-Server aus der venv neben
REM dieser Datei und schreibt alle Ausgaben nach webui_run.log.
setlocal
cd /d "%~dp0"

if not exist ".venv\Scripts\python.exe" (
  echo [run_webui] .venv fehlt - bitte einmal einrichten: > webui_run.log
  echo   python -m venv .venv >> webui_run.log
  echo   .venv\Scripts\pip install -r requirements.txt >> webui_run.log
  exit /b 1
)

call ".venv\Scripts\activate.bat"
python server.py > webui_run.log 2>&1
