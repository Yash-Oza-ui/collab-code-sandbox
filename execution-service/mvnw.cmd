@echo off
@REM ----------------------------------------------------------------------------
@REM Maven wrapper batch script for Windows.
@REM ----------------------------------------------------------------------------
setlocal

where mvn >nul 2>nul
if %ERRORLEVEL% equ 0 (
    mvn %*
    exit /b %ERRORLEVEL%
)

set SCRIPT_DIR=%~dp0
set MAVEN_WRAPPER_PROPERTIES=%SCRIPT_DIR%.mvn\wrapper\maven-wrapper.properties

if exist "%MAVEN_WRAPPER_PROPERTIES%" (
    for /f "usebackq tokens=2 delims==" %%A in (`findstr /b "distributionUrl" "%MAVEN_WRAPPER_PROPERTIES%"`) do set DIST_URL=%%A
) else (
    set DIST_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip
)

set MAVEN_USER_HOME=%USERPROFILE%\.m2
set MAVEN_HOME=%MAVEN_USER_HOME%\wrapper\dists\apache-maven-3.9.9

if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
    if not exist "%MAVEN_USER_HOME%\wrapper\dists" mkdir "%MAVEN_USER_HOME%\wrapper\dists"
    echo Downloading %DIST_URL%
    powershell -Command "Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '%MAVEN_USER_HOME%\wrapper\dists\mvn-dist.zip'"
    powershell -Command "Expand-Archive -Path '%MAVEN_USER_HOME%\wrapper\dists\mvn-dist.zip' -DestinationPath '%MAVEN_USER_HOME%\wrapper\dists\tmp-extract' -Force"
    del "%MAVEN_USER_HOME%\wrapper\dists\mvn-dist.zip"
    for /d %%D in ("%MAVEN_USER_HOME%\wrapper\dists\tmp-extract\apache-maven-*") do (
        if exist "%MAVEN_HOME%" rmdir /s /q "%MAVEN_HOME%"
        move "%%D" "%MAVEN_HOME%" >nul
    )
    if exist "%MAVEN_USER_HOME%\wrapper\dists\tmp-extract" rmdir /s /q "%MAVEN_USER_HOME%\wrapper\dists\tmp-extract"
)

"%MAVEN_HOME%\bin\mvn.cmd" %*
endlocal
