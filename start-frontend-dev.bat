@echo off
echo Starting kigruapp Frontend...
echo.

echo Starting Frontend (Angular dev server on :4301)...
cd /d %~dp0frontend
npm start -- --proxy-config proxy.conf.json
