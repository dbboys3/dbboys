@echo off
:: ========== Core fix: completely resolve Chinese garbled text ==========
:: Set console code page to UTF-8 (must be at the very top, no extra chars)
chcp 65001 >nul 2>&1
:: Set encoding-related environment variables for this batch file itself
set "PYTHONIOENCODING=UTF-8"
set "LC_ALL=zh_CN.UTF-8"
:: Enable delayed expansion (avoid variable parsing issues)
setlocal enabledelayedexpansion

:: Define variable; JAVAFX_JMODS is the path to javafx-jmods
set JAVAFX_JMODS=D:\Programs\javafx-jmods-25.0.3

:: All build artifacts live under build\ (git-ignored); only build\dist\dbboys.zip survives
set BUILD=build
set CLASSES=%BUILD%\classes
set JREMIN=%BUILD%\jre-min
set DIST=%BUILD%\dist
if exist "%BUILD%" rd /s /q "%BUILD%"
mkdir "%CLASSES%" "%DIST%"

:: Compile all source files under subdirectories (space-separate files from different dirs)
dir /b /s src\*.java > "%BUILD%\sources.txt"
javac -encoding UTF-8 -d "%CLASSES%" -sourcepath src -cp lib\lib_modular\*;lib\lib_nonmodular\* @"%BUILD%\sources.txt"
echo Source compilation completed.

:: Copy runtime resources to classes (single tree, package paths preserved)
xcopy /e /h /y /q "resources\*" "%CLASSES%\"

:: Build jar file
jar --create --file lib/lib_nonmodular/dbboys.jar --main-class com.dbboys.app.Main -C "%CLASSES%" .
echo dbboys.jar created.

:: Create minimized JRE
jlink  --module-path "%JAVAFX_JMODS%;lib\lib_modular"  --add-modules javafx.fxml,org.json,net.sf.jsqlparser,javafx.swing,org.controlsfx.controls,org.commonmark,java.sql,java.naming,java.management,java.security.jgss,java.transaction.xa,java.xml,jdk.crypto.ec,jdk.security.auth,org.apache.lucene.queryparser,org.apache.lucene.sandbox,org.apache.lucene.core,org.apache.logging.log4j,org.apache.logging.log4j.core  --output "%JREMIN%" --strip-debug --no-man-pages  --no-header-files
echo Minimized JRE created.

:: Package exe
jpackage --type app-image --name dbboys --dest "%DIST%" --input lib\lib_nonmodular --main-jar dbboys.jar --main-class com.dbboys.app.Main --runtime-image "%JREMIN%" --icon images\dbboys.ico --java-options "-Xmx1024m" --java-options "-Dlog4j2.configurationFile=etc/log4j2.xml"
echo Packaging finished.

:: Copy other directories into the app image
xcopy /e /h /y /q "docs\*" "%DIST%\dbboys\docs\"
xcopy /e /h /y /q "extlib\*" "%DIST%\dbboys\extlib\"
xcopy /e /h /y /q "images\*" "%DIST%\dbboys\images\"
xcopy /e /h /y /q "etc\*" "%DIST%\dbboys\etc\"
echo Folders copied.

:: Compress the app image (archive keeps dbboys\ as its top-level entry)
set "EXE=%~dp0lib\lib_nonmodular\7za.exe"
if exist "%DIST%\dbboys.zip" del /f /q "%DIST%\dbboys.zip"
pushd "%DIST%"
"%EXE%" a -tzip -mx=5 -r -y dbboys.zip "dbboys\*"
popd
echo Packaged %DIST%\dbboys.zip.

:: Clean intermediates; keep only %DIST%\dbboys.zip
del /f /q "%BUILD%\sources.txt"
rd /s /q "%CLASSES%"
rd /s /q "%JREMIN%"
rd /s /q "%DIST%\dbboys"
if exist "lib\lib_nonmodular\dbboys.jar" del /f /q "lib\lib_nonmodular\dbboys.jar"
echo Done. Final artifact: %DIST%\dbboys.zip
pause
