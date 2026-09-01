@echo off
setlocal enabledelayedexpansion

set DB_HOST=%DB_HOST:localhost=localhost%
set DB_PORT=%DB_PORT:5432=5432%
set DB_USER=%DB_USER:postgres=postgres%
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
    echo   - On Docker: docker run -d -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:15
    echo   - Check PostgreSQL status at Windows Services
    echo.
    exit /b 1
)

REM Create the database if it doesn't exist
for /f %%i in ('psql -h %DB_HOST% -p %DB_PORT% -U %DB_USER% -tc "SELECT 1 FROM pg_database WHERE datname = '%DB_NAME%'" 2^>nul') do set DB_EXISTS=%%i

if not defined DB_EXISTS (
    psql -h %DB_HOST% -p %DB_PORT% -U %DB_USER% -c "CREATE DATABASE %DB_NAME%;"
)

echo Test database '%DB_NAME%' is ready.
echo.
echo Database connection details:
echo   URL: jdbc:postgresql://%DB_HOST%:%DB_PORT%/%DB_NAME%
echo   User: %DB_USER%
echo.
echo To run tests with this database:
echo   set DB_HOST=%DB_HOST% DB_PORT=%DB_PORT% DB_USER=%DB_USER%
echo   mvn clean test

endlocal
