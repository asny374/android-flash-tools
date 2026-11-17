@rem 精简版 Gradle wrapper 启动脚本（Windows）
@echo off
setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.

if defined JAVA_HOME (
  set "JAVACMD=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVACMD=java"
)

set "CLASSPATH=%DIRNAME%gradle\wrapper\gradle-wrapper.jar"

"%JAVACMD%" -Dorg.gradle.appname=gradlew -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

set "EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %EXIT_CODE%
