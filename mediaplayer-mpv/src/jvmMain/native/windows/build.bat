@echo off
setlocal

set "VS_CMAKE=%ProgramFiles(x86)%\Microsoft Visual Studio\2022\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin"
if exist "%VS_CMAKE%\cmake.exe" set "PATH=%VS_CMAKE%;%PATH%"

cmake -S . -B build-x64 -A x64
if errorlevel 1 exit /b %errorlevel%
cmake --build build-x64 --config Release
if errorlevel 1 exit /b %errorlevel%

cmake -S . -B build-arm64 -A ARM64
if errorlevel 1 exit /b %errorlevel%
cmake --build build-arm64 --config Release
if errorlevel 1 exit /b %errorlevel%

endlocal
