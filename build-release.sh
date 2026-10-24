#!/usr/bin/env bash
# Builds everything that goes into a QuST release and collects it in dist/:
#   qupath-extension-qust-<version>.jar   the QuPath extension
#   ST2WSI_Registration-<version>.jar     the Fiji plugin (DAPI to H&E registration)
#   python/ and data/cci/                 the scripts and the ligand-receptor table, as one zip
# Needs JDK 25 (extension), JDK 17+ and Maven (Fiji plugin), zip.
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
dist="$root/dist"
rm -rf "$dist"; mkdir -p "$dist"

(cd "$root/qupath-extension-qust" && ./gradlew clean build --console=plain -q)
cp "$root"/qupath-extension-qust/build/libs/qupath-extension-qust-*[0-9].jar "$dist/"

(cd "$root/registration" && mvn -B -q -DskipTests clean package)
cp "$root"/registration/target/ST2WSI_Registration-*[0-9].jar "$dist/"

version="$(basename "$dist"/qupath-extension-qust-*.jar .jar | sed 's/qupath-extension-qust-//')"
(cd "$root" && zip -qr "$dist/qust-python-and-data-$version.zip" python data -x '*/__pycache__/*')

ls -la "$dist"
