@echo off
setlocal

if not exist bin mkdir bin

javac -encoding UTF-8 -d bin src\com\dbboys\demo\OracleConnectProbe.java
if errorlevel 1 exit /b %errorlevel%

if "%~1"=="" (
    java -cp "bin" com.dbboys.demo.OracleConnectProbe
) else (
    java -cp "bin" com.dbboys.demo.OracleConnectProbe %*
)
