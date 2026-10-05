@echo off
adb logcat -c
timeout /t 2 > nul
:loop
adb logcat -v brief >> logcat_output.txt 2>&1
timeout /t 1 > nul
goto loop