#!/bin/bash
set -e

echo "Refactoring to multi-module maven..."

# 1. Create directory structure
mkdir -p server/src/main/kotlin/cz/loplex/mcp/screenshotter/server
mkdir -p worker/src/main/kotlin/cz/loplex/mcp/screenshotter/worker

# 2. Move source files
# Server files
mv src/main/kotlin/cz/loplex/mcp/screenshotter/Main.kt server/src/main/kotlin/cz/loplex/mcp/screenshotter/server/

# Worker files
mv src/main/kotlin/cz/loplex/mcp/screenshotter/ScreenshotterServer.kt worker/src/main/kotlin/cz/loplex/mcp/screenshotter/worker/
mv src/main/kotlin/cz/loplex/mcp/screenshotter/AtSpiReader.kt worker/src/main/kotlin/cz/loplex/mcp/screenshotter/worker/
mv src/main/kotlin/cz/loplex/mcp/screenshotter/ClipboardManager.kt worker/src/main/kotlin/cz/loplex/mcp/screenshotter/worker/
mv src/main/kotlin/cz/loplex/mcp/screenshotter/VisionFallback.kt worker/src/main/kotlin/cz/loplex/mcp/screenshotter/worker/

# 3. Move test files
mkdir -p worker/src/test/kotlin/cz/loplex/mcp/screenshotter/worker
mv src/test/kotlin/cz/loplex/mcp/screenshotter/* worker/src/test/kotlin/cz/loplex/mcp/screenshotter/worker/ 2>/dev/null || true

# 4. Remove old src directory and sandbox script
rm -rf src
rm -f mcp-sandbox.py

echo "Done moving files."
