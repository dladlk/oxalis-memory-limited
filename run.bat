@echo off
setlocal
setlocal enabledelayedexpansion

set OXALIS_HOME=src\main\resources\oxalis_home

SET RUN_MODE=MANUAL
SET DOC_SIZE=1
SET XMX=4000
SET XMX_INCREMENT=1
SET MAX_DOC_SIZE=600
SET MAX_XMX=6000

if "%RUN_MODE%"=="AUTO" (call) else goto :non_auto_mode
if "%~1"=="" (call) else set DOC_SIZE=%~1
if "%~2"=="" (call) else set XMX=%~2
if "%~3"=="" (call) else set XMX_INCREMENT=%~3
if "%~4"=="" (call) else set MAX_DOC_SIZE=%~4
goto new_doc_size

:non_auto_mode
if "%~1"=="" (call) else SET DOC_SIZE=%*

:new_doc_size
echo Send/receive a document with %DOC_SIZE% MB attachment and start max memory %XMX%, increment by %XMX_INCREMENT% MB until success

:call_send
echo Run send/receive with -Xmx%XMX%m and %DOC_SIZE% mb attachment
call java21 -Xmx%XMX%m -cp target/oxalis-memory-limited-6.4.0.jar;target/lib/* network.oxalis.rd.memory.Main %DOC_SIZE%
@REM set ERRORLEVEL=0
echo Result ERRORLEVEL: %ERRORLEVEL%

IF %ERRORLEVEL% NEQ 0 goto increase_memory

echo Test passed successfully with -Xmx%XMX%m and %DOC_SIZE% mb attachment
goto increment_doc_size

:increase_memory
set /A XMX=XMX+1
if %XMX% gtr %MAX_XMX% goto real_exit
goto call_send

:increment_doc_size
if "%RUN_MODE%"=="AUTO" (call) else goto :real_exit
set /A DOC_SIZE=DOC_SIZE*2
if %DOC_SIZE% gtr %MAX_DOC_SIZE% goto real_exit
goto new_doc_size

:real_exit