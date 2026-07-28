#!/bin/bash
set -e

# ========== DBboys Linux Build Script ==========
# Requires: JDK 25, JavaFX jmods 25, zip tool
# Equivalent to build.bat on Windows

# ---- Config ----
# JavaFX jmods path
#   override: JAVAFX_JMODS=/custom/path ./build.sh
#   default:  /opt/javafx-jmods-25.0.3
JAVAFX_JMODS="${JAVAFX_JMODS:-/opt/javafx-jmods-25.0.3}"
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

# All build artifacts live under build/ (git-ignored); only build/dist/dbboys.zip survives
BUILD_DIR="$PROJECT_DIR/build"
CLASSES="$BUILD_DIR/classes"
JRE_MIN="$BUILD_DIR/jre-min"
DIST_DIR="$BUILD_DIR/dist"

echo "=== DBboys Linux Build ==="
echo "Project dir : $PROJECT_DIR"
echo "JavaFX jmods: $JAVAFX_JMODS"
echo ""

rm -rf "$BUILD_DIR"
mkdir -p "$CLASSES" "$DIST_DIR"

# ---- Step 1: Compile Java sources ----
echo "[1/7] Compiling Java sources..."
find "$PROJECT_DIR/src" -name "*.java" > "$BUILD_DIR/sources.txt"
javac -encoding UTF-8 \
  -d "$CLASSES" \
  -sourcepath "$PROJECT_DIR/src" \
  -cp "$PROJECT_DIR/lib/lib_modular/*:$PROJECT_DIR/lib/lib_nonmodular/*" \
  @"$BUILD_DIR/sources.txt"
echo "  Source compilation completed."

# ---- Step 2: Copy runtime resources (single tree, package paths preserved) ----
echo "[2/7] Copying runtime resources..."
cp -r "$PROJECT_DIR/resources/." "$CLASSES/"
echo "  Resources copied."

# ---- Step 3: Build JAR ----
echo "[3/7] Creating dbboys.jar..."
jar --create \
  --file "$PROJECT_DIR/lib/lib_nonmodular/dbboys.jar" \
  --main-class com.dbboys.app.Main \
  -C "$CLASSES" .
echo "  dbboys.jar created."

# ---- Step 4: Create minimized JRE ----
echo "[4/7] Creating minimized JRE (jlink)..."
jlink \
  --module-path "$JAVAFX_JMODS:$PROJECT_DIR/lib/lib_modular" \
  --add-modules javafx.fxml,org.json,net.sf.jsqlparser,javafx.swing,org.controlsfx.controls,org.commonmark,java.sql,java.naming,java.management,java.security.jgss,java.transaction.xa,java.xml,jdk.crypto.ec,jdk.security.auth,org.apache.lucene.queryparser,org.apache.lucene.sandbox,org.apache.lucene.core,org.apache.logging.log4j,org.apache.logging.log4j.core \
  --output "$JRE_MIN" \
  --strip-debug \
  --no-man-pages \
  --no-header-files
echo "  Minimized JRE created."

# ---- Step 5: Package app-image ----
echo "[5/7] Packaging app-image (jpackage)..."
jpackage --type app-image \
  --name dbboys \
  --dest "$DIST_DIR" \
  --input "$PROJECT_DIR/lib/lib_nonmodular" \
  --main-jar dbboys.jar \
  --main-class com.dbboys.app.Main \
  --runtime-image "$JRE_MIN" \
  --icon "$PROJECT_DIR/images/logo.png" \
  --java-options "-Xmx1024m" \
  --java-options "-Dlog4j2.configurationFile=etc/log4j2.xml"
echo "  Packaging finished."

# ---- Step 6: Assemble distribution ----
echo "[6/7] Assembling distribution..."
cp -r "$PROJECT_DIR/docs"   "$DIST_DIR/dbboys/docs/"
cp -r "$PROJECT_DIR/extlib" "$DIST_DIR/dbboys/extlib/"
cp -r "$PROJECT_DIR/images" "$DIST_DIR/dbboys/images/"
cp -r "$PROJECT_DIR/etc"    "$DIST_DIR/dbboys/etc/"

cat > "$DIST_DIR/dbboys/start.sh" << 'STARTEOF'
#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
"$DIR/bin/dbboys"
STARTEOF
chmod +x "$DIST_DIR/dbboys/start.sh"

echo "  Folders copied + start.sh created."

# ---- Step 7: Zip & clean intermediates (keep only build/dist/dbboys.zip) ----
echo "[7/7] Zipping..."
rm -f "$DIST_DIR/dbboys.zip"
(cd "$DIST_DIR" && zip -r -5 dbboys.zip "dbboys/")
echo "  Packaged $DIST_DIR/dbboys.zip."

rm -f "$BUILD_DIR/sources.txt"
rm -rf "$CLASSES"
rm -rf "$JRE_MIN"
rm -rf "$DIST_DIR/dbboys"
rm -f "$PROJECT_DIR/lib/lib_nonmodular/dbboys.jar"

echo ""
echo "=== Build complete: $DIST_DIR/dbboys.zip ==="
echo ""
echo "Usage on target Linux machine:"
echo "  unzip dbboys.zip"
echo "  cd dbboys"
echo "  chmod +x start.sh && ./start.sh"
