@echo off
cd /d "%~dp0"
set CSC=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe
if not exist "%CSC%" set CSC=%WINDIR%\Microsoft.NET\Framework\v4.0.30319\csc.exe
echo using %CSC% > build.log
"%CSC%" /nologo /target:winexe /optimize+ /out:KrankmiliServer.exe /win32icon:icon.ico /resource:config.json,config.json /resource:voice.html,voice.html /reference:System.dll,System.Core.dll,System.Drawing.dll,System.Windows.Forms.dll,System.Web.Extensions.dll,System.IO.Compression.dll,System.IO.Compression.FileSystem.dll KrankmiliServer.cs >> build.log 2>&1
echo exitcode %ERRORLEVEL% >> build.log
exit /b 0
