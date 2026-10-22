@echo off
rem Starts `porter run` for one port (Windows). Used by ports\<mod>\run.bat:
rem   set PORT_DIR=<port-dir>  and then  scripts\run-port.bat [porter run options, e.g. --input original=<jar>]
rem Needs Java 21 to start porter. If there is none (JAVA_HOME, PATH), a Temurin 21 JDK is downloaded once into
rem %PORTER_HOME%\jdk-21 (about 200 MB, checksum-verified). Everything porter fetches or builds goes under
rem %PORTER_HOME% (default: .porter next to this distribution); delete that folder to remove it all.
setlocal enableextensions
if "%PORT_DIR%"=="" (echo usage: set PORT_DIR=^<port-dir^> ^& run-port.bat [porter run options] & exit /b 2)
for %%I in ("%PORT_DIR%") do set "PORT_DIR=%%~fI"
set "ROOT=%PORT_DIR%\..\.."
for %%I in ("%ROOT%") do set "ROOT=%%~fI"
if "%PORTER_HOME%"=="" set "PORTER_HOME=%ROOT%\.porter"

set "PORTER="
if exist "%ROOT%\bin\porter.bat" set "PORTER=%ROOT%\bin\porter.bat"
if "%PORTER%"=="" if exist "%ROOT%\cli\build\install\porter\bin\porter.bat" set "PORTER=%ROOT%\cli\build\install\porter\bin\porter.bat"
if "%PORTER%"=="" (
  echo porter is not built here. Download the release zip from https://github.com/Chasik8/mc-porter/releases,
  echo unpack it, and run ports\^<mod^>\run.bat from the unpacked folder.
  exit /b 2
)

rem ---- find a Java 21+ ----
set "JH="
if not "%JAVA_HOME%"=="" call :check "%JAVA_HOME%"
if "%JH%"=="" for /f "delims=" %%J in ('where java 2^>nul') do if "%JH%"=="" call :check "%%~dpJ.."
if "%JH%"=="" for /d %%D in ("%PORTER_HOME%\jdk-21\*") do if "%JH%"=="" call :check "%%~fD"
if not "%JH%"=="" goto :have

echo No Java 21 found. Downloading a JDK 21 ^(about 200 MB, once^) ...
if not exist "%PORTER_HOME%\downloads" mkdir "%PORTER_HOME%\downloads"
if not exist "%PORTER_HOME%\jdk-21" mkdir "%PORTER_HOME%\jdk-21"
set "PORTER_JDK_ZIP=%PORTER_HOME%\downloads\OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip"
set "PORTER_JDK_URL=https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip"
set "PORTER_JDK_SHA=f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e"
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -UseBasicParsing -Uri $env:PORTER_JDK_URL -OutFile ($env:PORTER_JDK_ZIP+'.part'); $h=(Get-FileHash -Algorithm SHA256 ($env:PORTER_JDK_ZIP+'.part')).Hash.ToLower(); if ($h -ne $env:PORTER_JDK_SHA) { Remove-Item ($env:PORTER_JDK_ZIP+'.part'); Write-Error ('The downloaded JDK has a wrong checksum ('+$h+'); it was not used.') }; Move-Item -Force ($env:PORTER_JDK_ZIP+'.part') $env:PORTER_JDK_ZIP; Expand-Archive -Force -Path $env:PORTER_JDK_ZIP -DestinationPath ($env:PORTER_HOME+'\jdk-21')"
if errorlevel 1 (echo Could not fetch the JDK. Install a JDK 21 and set JAVA_HOME, then run again. & exit /b 2)
for /d %%D in ("%PORTER_HOME%\jdk-21\*") do if "%JH%"=="" call :check "%%~fD"
if "%JH%"=="" (echo The downloaded JDK does not work. Install a JDK 21 and set JAVA_HOME. & exit /b 2)

:have
set "JAVA_HOME=%JH%"
set "PATH=%JH%\bin;%PATH%"
cd /d "%ROOT%"
call "%PORTER%" run "%PORT_DIR%" %*
exit /b %errorlevel%

:check
rem sets JH to %~1 when it holds a java of version 21 or newer
set "JV="
if not exist "%~1\bin\java.exe" exit /b 0
if not exist "%~1\release" exit /b 0
for /f "tokens=2 delims==" %%V in ('findstr /b "JAVA_VERSION=" "%~1\release"') do set "JV=%%~V"
for /f "delims=." %%M in ("%JV%") do if %%M GEQ 21 set "JH=%~f1"
exit /b 0
