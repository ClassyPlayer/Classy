@echo off
setlocal
call gradlew.bat assembleY2Debug testY2DebugUnitTest
if errorlevel 1 (
  echo.
  echo Build or unit tests failed. See the Gradle output above.
  exit /b 1
)
echo.
echo Y2 debug APK:
echo app\build\outputs\apk\y2\debug\app-y2-debug.apk
endlocal
