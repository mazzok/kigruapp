@echo off
echo Starting kigruapp dev environment...
echo.

echo [1/3] Starting MongoDB + Keycloak (Docker)...
docker-compose up -d mongodb keycloak
echo Waiting for services to initialize...
timeout /t 5 /nobreak >nul

echo [2/3] Starting Backend (Quarkus dev mode on :8080)...
start "kigruapp-backend" cmd /c "cd /d %~dp0backend && mvnw quarkus:dev"

echo [3/3] Starting Frontend (Angular dev server on :4200)...
start "kigruapp-frontend" cmd /c "cd /d %~dp0frontend && npm start -- --proxy-config proxy.conf.json"

echo.
echo All services starting:
echo   MongoDB:  localhost:27017 (Docker)
echo   Keycloak: localhost:8080/auth (Docker)
echo   Backend:  http://localhost:8080
echo   Frontend: http://localhost:4200
echo.
echo To stop Docker services: docker-compose down
echo Close this window or press any key to exit.
pause >nul
