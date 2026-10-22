@echo off
rem Ports Torchmaster to Minecraft 1.7.10 with one command. See README.md in this folder.
set "PORT_DIR=%~dp0."
call "%~dp0..\..\scripts\run-port.bat" %*
exit /b %errorlevel%
