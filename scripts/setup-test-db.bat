@echo off
setlocal enabledelayedexpansion

if not defined DB_HOST set DB_HOST=localhost
if not defined DB_PORT set DB_PORT=5432
if not defined DB_USER set DB_USER=dcos
if not defined DB_PASSWORD set DB_PASSWORD=changeme
set PGPASSWORD=%DB_PASSWORD%
set DB_NAME=cert_api_test

echo Setting up test database...
echo Database Host: %DB_HOST%
echo Database Port: %DB_PORT%
echo Database User: %DB_USER%
echo Database Name: %DB_NAME%
echo.

REM Check if PostgreSQL is running using PowerShell
powershell -NoProfile -Command "try { [System.Net.Sockets.TcpClient]::new().Connect('%DB_HOST%', %DB_PORT%) -gt $null; $? } catch { $false }" >nul 2>&1

if errorlevel 1 (
    echo ERROR: Could not connect to PostgreSQL at %DB_HOST%:%DB_PORT%
    echo.
    echo To fix this issue, ensure PostgreSQL is running. You can start it with:
    echo   - On Windows: net start postgresql-x64-15 (or use PostgreSQL services)
    echo   - The dcos-infra stack: cd ..\dcos-infra, then docker compose up -d
    echo   - Check PostgreSQL status at Windows Services
    echo.
    exit /b 1
)

REM Create the database if it doesn't exist
for /f %%i in ('psql -h %DB_HOST% -p %DB_PORT% -U %DB_USER% -d postgres -tc "SELECT 1 FROM pg_database WHERE datname = '%DB_NAME%'" 2^>nul') do set DB_EXISTS=%%i

if not defined DB_EXISTS (
    psql -h %DB_HOST% -p %DB_PORT% -U %DB_USER% -d postgres -c "CREATE DATABASE %DB_NAME%;"
)

echo Test database '%DB_NAME%' is ready.
echo.
echo Database connection details:
echo   URL: jdbc:postgresql://%DB_HOST%:%DB_PORT%/%DB_NAME%
echo   User: %DB_USER%
echo.
echo To run tests with this database:
echo   set DB_HOST, DB_PORT, DB_USER and DB_PASSWORD as needed
echo   mvnw.cmd clean verify

endlocal
