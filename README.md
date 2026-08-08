# MCP Screenshotter

A headless Model Context Protocol (MCP) server that provides a completely isolated, sandboxed X11 graphical environment for AI agents to launch and interact with GUI applications without affecting the user's host environment.

## Overview

MCP Screenshotter allows an AI (like Claude) to safely run, observe, and interact with graphical Linux applications within an invisible sandbox. The architecture is split into two components to ensure absolute isolation:

1. **Headless Orchestrator (`server`)**: Communicates with the AI via standard MCP standard I/O streams. It runs on the host and spins up the sandbox environment (`Xephyr`, `dbus`, `at-spi2`).
2. **Sandboxed Worker (`worker`)**: Runs inside the isolated `Xephyr` display and communicates back to the Orchestrator via a local HTTP API. It performs all the heavy lifting: capturing screenshots, simulating mouse/keyboard input, and extracting UI trees.

## Features

- **Isolated GUI Sandbox**: Uses `Xephyr` to create a virtual X11 display that is invisible and completely disconnected from the user's desktop.
- **Visual Interaction**: Takes screenshots (including optional delta-encoding for changes) to let the AI "see" the application.
- **Accessibility Tree Extraction**: Hooks into `AT-SPI2` over D-Bus to extract a structured UI element tree (buttons, inputs, coordinates), allowing the AI to click elements precisely.
- **Computer Vision Fallback**: Uses OpenCV edge detection to identify clickable bounding boxes when an application (e.g., Python Tkinter) does not support the accessibility API.
- **Input Simulation**: Uses `java.awt.Robot` to simulate physical mouse clicks, movements, and typing inside the sandbox.
- **Clipboard Management**: Can read and write to the isolated sandbox clipboard.

## Architecture

```
[ AI Assistant (Claude) ]
       | (MCP stdio)
       v
[ Headless Orchestrator (Main.kt) ]
       | 1. Starts Xephyr (e.g. :99)
       | 2. Starts isolated DBus & AT-SPI2
       | 3. Starts Worker in the sandbox
       |
       | (HTTP POST /execute)
       v
[ Sandboxed Worker (WorkerMain.kt) ] --> java.awt.Robot (Input)
       |                             --> OpenCV (Vision Fallback)
       |                             --> AT-SPI2 (UI Tree)
       v
[ Target GUI Application ]
```

## Requirements

- **Java 17** or higher
- **Maven** 3.8+
- **Xephyr** (`xserver-xephyr`)
- **D-Bus** (`dbus-x11`)
- **AT-SPI2** (`at-spi2-core`)
- **Python 3**, **PyGObject** (`python3-gi`) & **GTK 3** (`gir1.2-gtk-3.0`) - for running the E2E test app's sample GTK application

On Debian/Ubuntu:
```bash
sudo apt update
sudo apt install xserver-xephyr dbus-x11 at-spi2-core python3-gi gir1.2-gtk-3.0
```

## Build and Run

1. **Compile and Package:**
   ```bash
   mvn clean package -DskipTests
   ```
   This produces `screenshotter-server-0.2.0-SNAPSHOT-jar-with-dependencies.jar` and the corresponding worker jar.

2. **Run E2E Tests:**
   A full GUI end-to-end test is provided, which spins up the MCP server, launches a Python test application, finds a button using OpenCV/AT-SPI2, clicks it, and verifies the screenshot.
   ```bash
   python3 e2e/test_gui.py
   ```
   The resulting screenshot will be saved to `e2e/output/final_screenshot.png`.

## Available MCP Tools

- `launch_app`: Execute a bash command inside the sandbox to start a GUI application.
- `get_screenshot`: Returns a base64 encoded PNG. Can return only bounding boxes of changed areas if `threshold` and `include_deltas` are used.
- `get_ui_tree`: Returns a structured JSON representation of the active window's UI elements via AT-SPI2.
- `detect_ui_elements`: Fallback tool that uses OpenCV edge detection to find rectangular UI components.
- `mouse_action`: Move, click, double-click, or drag at specific X, Y coordinates.
- `get_clipboard` / `set_clipboard`: Interact with the sandbox clipboard.
- `resize_window`: Resizes all top-level windows in the sandbox display.
- `highlight_area`: Returns a screenshot with a red bounding box drawn over a specified area.

## Usage with Claude Desktop

Add the following configuration to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "screenshotter": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/mcp-screenshotter/server/target/screenshotter-server-0.2.0-SNAPSHOT-jar-with-dependencies.jar"
      ]
    }
  }
}
```

## Troubleshooting

- **"Cannot connect to X server"**: Ensure `Xephyr` is installed. The orchestrator automatically scans for a free display from `:1` to `:99`.
- **"AT-SPI2 failed"**: If your test application does not export an accessibility tree (like standard Python Tkinter), the server will smoothly fallback to OpenCV for visual bounding boxes. GTK and Qt applications usually support AT-SPI2 natively.
