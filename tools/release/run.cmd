@echo off
setlocal
cd /d "%~dp0"
set "JAVA_COMMAND=java"
if defined JAVA_HOME set "JAVA_COMMAND=%JAVA_HOME%\bin\java.exe"
set /p FLOCI_VERSION=<version.txt
if not defined FLOCI_TLS_ENABLED set "FLOCI_TLS_ENABLED=false"
if not defined FLOCI_SERVICES_UI_ENABLED set "FLOCI_SERVICES_UI_ENABLED=false"
if not defined FLOCI_SERVICES_LAMBDA_HOT_RELOAD_ENABLED set "FLOCI_SERVICES_LAMBDA_HOT_RELOAD_ENABLED=false"
"%JAVA_COMMAND%" --enable-native-access=ALL-UNNAMED -jar quarkus-app/quarkus-run.jar %*
