#!/usr/bin/env bash

# Usage: ./build-agent.sh path/to/config.properties [output.jar]
set -euo pipefail

CONFIG="${1:?usage: build-agent.sh <config.properties> [out.jar]}"
OUT="${2:-server.jar}"
HERE="$(cd "$(dirname "$0")" && pwd)"

rm -rf "$HERE/out" && mkdir -p "$HERE/out"

javac --release 8 -d "$HERE/out" "$HERE/Agent.java"

cp "$CONFIG" "$HERE/out/config.properties"

cat > "$HERE/out/MANIFEST.MF" << 'MANIFEST'
Manifest-Version: 1.0
Main-Class: transfur.Agent
MANIFEST

( cd "$HERE/out" && jar cfm "$HERE/$OUT" MANIFEST.MF transfur/ config.properties )
echo "built $HERE/$OUT"
