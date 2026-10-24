#!/usr/bin/env bash
# Starts a real QuPath (a built distribution) with the QuST jar under a virtual display and runs the analysis and
# export commands on a small synthetic slide.
#
#   QUPATH_APP=/path/to/QuPath/lib/app tools/smoke.sh
#   QUST_PYTHON=/path/to/python tools/smoke.sh      (also trains and applies a model; needs python/requirements.txt)
#   STARDIST_JAR=<qupath-extension-stardist.jar> STARDIST_MODELS=<folder with a .pb model> tools/smoke.sh   (also StarDist)
#   CELLPOSE_JAR=<qupath-extension-cellpose.jar> CELLPOSE_PYTHON=<python with cellpose> tools/smoke.sh   (also Cellpose; needs its model weights)
#
# Needs: ./gradlew build (run first), JDK 25, xvfb-run.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
APP="${QUPATH_APP:?set QUPATH_APP to the lib/app folder of a QuPath distribution}"
JAVA_HOME_25="${JAVA25:-$(ls -d ~/.gradle/jdks/*25*/ | head -1)}"
JAR="$(ls "$here"/build/libs/qupath-extension-qust-*[0-9].jar | head -1)"
OUT=/tmp/qust-smoke
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/home" "$OUT/prefs"
"$JAVA_HOME_25/bin/javac" -nowarn -d "$OUT/classes" -cp "$APP/*:$JAR" "$here/tools/QustSmoke.java"
timeout 900 xvfb-run -a -s "-screen 0 1600x1000x24" "$JAVA_HOME_25/bin/java" -XX:MaxRAMPercentage=40 \
  --enable-native-access=ALL-UNNAMED -Dprism.order=sw -Duser.home="$OUT/home" -Djava.util.prefs.userRoot="$OUT/prefs" \
  ${STARDIST_MODELS:+-Dqust.stardist.models="$STARDIST_MODELS"} \
  ${CELLPOSE_PYTHON:+-Dqust.cellpose.python="$CELLPOSE_PYTHON"} \
  -cp "$OUT/classes:$JAR:${STARDIST_JAR:+$STARDIST_JAR:}${CELLPOSE_JAR:+$CELLPOSE_JAR:}$APP/*" QustSmoke "$OUT/export" "$here/../data/cci/human_lr_pair.csv" ${QUST_PYTHON:+"$QUST_PYTHON" "$here/../python"} 2>&1 | tee "$OUT/log.txt" | grep "HARNESS"
