@echo off
rem Builds the IC2 Classic Extras addon (steam power, barrels, guide book) from your own two IC2 Classic jars.
rem Usage: run.bat IC2Classic-1.19.2-2.1.3.4.jar IC2Classic-1.7.10-1.2.6.jar   (see README.md)
set "PORT_DIR=%~dp0."
if "%~2"=="" (
  call "%~dp0..\..\scripts\run-port.bat" %*
) else (
  call "%~dp0..\..\scripts\run-port.bat" --input "ic2c-1192=%~f1" --input "ic2c-1710=%~f2"
)
exit /b %errorlevel%
