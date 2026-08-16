#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TYCHO_DIR="$SCRIPT_DIR/com.ibm.mcp.jdtls"

PLUGIN_TARGET="$TYCHO_DIR/com.ibm.mcp.jdtls.plugin/target"
DEST_DIR="$SCRIPT_DIR/extensions/java/src/main/resources/lsp/mcp-jdtls/plugins"

echo "=== Building MCP JDT.LS plugin (Tycho) ==="
cd "$TYCHO_DIR"
"$SCRIPT_DIR/mvnw" clean package
echo "Tycho build OK"

mkdir -p "$DEST_DIR"
JAR=$(find "$PLUGIN_TARGET" -maxdepth 1 -name "com.ibm.mcp.jdtls.plugin-*.jar" ! -name "*-sources.jar" | head -1)
if [ -z "$JAR" ]; then
  echo "ERROR: Plugin JAR not found in $PLUGIN_TARGET" >&2
  exit 1
fi
cp "$JAR" "$DEST_DIR/com.ibm.mcp.jdtls.plugin.jar"
echo "Copied plugin JAR to $DEST_DIR"

echo ""
echo "=== Building MCP Language Tools (Maven) ==="
cd "$SCRIPT_DIR"
./mvnw clean install "$@"
