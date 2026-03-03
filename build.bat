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
set JAVAFX_JMODS=D:\Programs\javafx-jmods-25.0.1

:: Compile all source files under subdirectories (space-separate files from different dirs)
dir /b /s src\*.java > sources.txt
javac -encoding UTF-8 -d bin -sourcepath src -cp lib\lib_modular\*;lib\lib_nonmodular\* @sources.txt
echo Source compilation completed.

:: Copy fxml and css folders to bin
xcopy /e /h /y /q "src\com\dbboys\fxml\*" "bin\com\dbboys\fxml\"
xcopy /e /h /y /q "src\com\dbboys\css\*" "bin\com\dbboys\css\"

:: Build jar file
jar --create --file lib/lib_nonmodular/dbboys.jar --main-class com.dbboys.app.Main -C bin .
echo dbboys.jar created.

:: Create minimized JRE
jlink  --module-path "%JAVAFX_JMODS%;lib\lib_modular"  --add-modules javafx.fxml,org.json,net.sf.jsqlparser,javafx.swing,org.controlsfx.controls,org.commonmark,java.sql,org.apache.lucene.queryparser,org.apache.lucene.sandbox,org.apache.lucene.analysis.smartcn,org.apache.lucene.core,org.apache.logging.log4j,org.apache.logging.log4j.core  --output jre-min --strip-debug --no-man-pages  --no-header-files
echo Minimized JRE created.

:: Package exe
jpackage --type app-image --name dbboys --input lib\lib_nonmodular --main-jar dbboys.jar --main-class com.dbboys.app.Main --runtime-image jre-min --icon images\dbboys.ico --java-options "-Xmx512m" --java-options "-Dlog4j2.configurationFile=etc/log4j2.xml"
echo Packaging finished.

:: Delete temp file sources.txt generated during compile
if exist "sources.txt" (
    del /f /q "sources.txt"
    echo Deleted temp file: sources.txt
)

:: Remove build output dir bin (contains old class files)
if exist "bin" (
    rd /s /q "bin"
    echo Deleted old build dir: bin
)

:: Delete old JAR (avoid packaging stale jar)
if exist "lib\lib_nonmodular\dbboys.jar" (
    del /f /q "lib\lib_nonmodular\dbboys.jar"
    echo Deleted old JAR: lib\lib_nonmodular\dbboys.jar
)

:: Delete minimized JRE dir (avoid old JRE interference)
if exist "jre-min" (
    rd /s /q "jre-min"
    echo Deleted old minimized JRE dir: jre-min
)

:: Copy other directories into dbboys
xcopy /e /h /y /q "docs\*" "dbboys\docs\"
xcopy /e /h /y /q "extlib\*" "dbboys\extlib\"
xcopy /e /h /y /q "images\*" "dbboys\images\"
xcopy /e /h /y /q "etc\*" "dbboys\etc\"
echo Folders copied.

:: Compress dbboys directory
set "EXE=%~dp0lib\lib_nonmodular\7za.exe"
set "ZIP_FILE=%~dp0dbboys.zip"
if exist "%ZIP_FILE%" (
    del /f /q "%ZIP_FILE%"
    echo Deleted old dbboys.zip archive.
)

"%EXE%" a -tzip -mx=5 -r -y "%ZIP_FILE%" "dbboys\*"

echo Packaged dbboys.zip.

:: Delete dbboys directory
if exist "dbboys" (
    rd /s /q "dbboys"
    echo Deleted dbboys directory.
)
pause
