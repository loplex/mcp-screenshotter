# MCP Screenshotter

A headless Model Context Protocol (MCP) server that provides a completely isolated, sandboxed X11 graphical environment for AI agents to launch and interact with GUI applications without affecting the user's host environment.

## Overview

MCP Screenshotter allows an AI (like Claude) to safely run, observe, and interact with graphical Linux applications within an isolated sandbox. Whether that sandbox is visible to a human or not is up to whoever runs the server, not something the code enforces - see [Display Backend](#display-backend) and [VNC Mirror](#vnc-mirror-optional). The architecture is split into two components to ensure absolute isolation:

1. **Headless Orchestrator (`server`)**: Communicates with the AI via standard MCP standard I/O streams. It runs on the host and spins up the sandbox environment (`Xephyr`/`Xvfb`, `dbus`, `at-spi2`).
2. **Sandboxed Worker (`worker`)**: Runs inside the isolated X11 display and communicates back to the Orchestrator via a local HTTP API. It performs all the heavy lifting: capturing screenshots, simulating mouse/keyboard input, and extracting UI trees.

## Features

- **Isolated GUI Sandbox**: Uses `Xephyr` (a plain window on a host X server) or `Xvfb` (a fully virtual, headless framebuffer) to create an X11 display disconnected from the user's real desktop - see [Display Backend](#display-backend) for how visible that ends up being.
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
       | 1. Starts Xephyr or Xvfb (e.g. :99)
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
- **Xephyr** (`xserver-xephyr`) and/or **Xvfb** (`xvfb`) - at least one of the two, see [Display Backend](#display-backend)
- **D-Bus** (`dbus-x11`)
- **AT-SPI2** (`at-spi2-core`)
- **Bubblewrap** (`bubblewrap`) - used by `launch_app` to sandbox the launched command's mount namespace
- **x11vnc** (`x11vnc`) - optional, only needed for the [VNC mirror](#vnc-mirror-optional)
- **Python 3**, **PyGObject** (`python3-gi`) & **GTK 3** (`gir1.2-gtk-3.0`) - for running the E2E test app's sample GTK application

On Debian/Ubuntu:
```bash
sudo apt update
sudo apt install xserver-xephyr xvfb dbus-x11 at-spi2-core bubblewrap x11vnc python3-gi gir1.2-gtk-3.0
```

## Display Backend

The nested X server that backs the sandbox is chosen via the `SCREENSHOTTER_DISPLAY_BACKEND`
environment variable, read once when the orchestrator starts:

- `xephyr` (default) - renders into a **plain, ordinary window** on whatever host X server the
  orchestrator process inherits `$DISPLAY` from. Nothing in the code hides or repositions that
  window - if the machine has a graphical session, the sandbox is as visible and clickable as any
  other window on it. Handy for watching (or even manually poking at) a test run live while
  developing locally, but it needs a host X session to attach to in the first place.
- `xvfb` - a fully virtual framebuffer with no host display requirement at all, and nothing to
  look at either - there's no window anywhere. Use this on headless CI runners or bare SSH
  sessions, where Xephyr has nothing to open a window on. Pair it with the [VNC
  mirror](#vnc-mirror-optional) below if you still want to see or interact with it.

```bash
SCREENSHOTTER_DISPLAY_BACKEND=xvfb java -jar server/target/screenshotter-server-0.2.0-SNAPSHOT-jar-with-dependencies.jar
```

Or, in `claude_desktop_config.json`, add it under the server's `"env"` key (see
[Usage with Claude Desktop](#usage-with-claude-desktop)).

## VNC Mirror (optional)

Set `SCREENSHOTTER_VNC_PORT` to also mirror the sandbox display over VNC via `x11vnc` - most
useful with the `xvfb` backend, which otherwise has no window anywhere for a human to look at.
Unset (the default), no VNC mirror is started at all.

```bash
SCREENSHOTTER_DISPLAY_BACKEND=xvfb SCREENSHOTTER_VNC_PORT=5900 java -jar server/target/screenshotter-server-0.2.0-SNAPSHOT-jar-with-dependencies.jar
```

Connect any VNC viewer to `127.0.0.1:5900` while the server is running. It's started with
`-localhost`, so it only ever listens on loopback - reaching it from another machine is a
deliberate extra step (e.g. `ssh -L 5900:localhost:5900 host`), not something exposed by default.
Requires `x11vnc` to be installed (see [Requirements](#requirements)).

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
      ],
      "env": {
        "SCREENSHOTTER_DISPLAY_BACKEND": "xephyr",
        "SCREENSHOTTER_VNC_PORT": "5900"
      }
    }
  }
}
```

## Troubleshooting

- **"Cannot connect to X server"**: Ensure `Xephyr` (or `Xvfb`, depending on `SCREENSHOTTER_DISPLAY_BACKEND`) is installed. The orchestrator automatically scans for a free display from `:1` to `:99`.
- **"Xephyr fails to start on a headless machine"**: Xephyr needs a host X server to open its window on; it has nothing to attach to over a bare SSH session or on a CI runner. Set `SCREENSHOTTER_DISPLAY_BACKEND=xvfb` instead, which needs no host display at all.
- **"AT-SPI2 failed"**: If your test application does not export an accessibility tree (like standard Python Tkinter), the server will smoothly fallback to OpenCV for visual bounding boxes. GTK and Qt applications usually support AT-SPI2 natively.
- **VNC viewer can't connect**: `x11vnc` is started with `-localhost`, so it only listens on `127.0.0.1` on the machine running the server - connect from that same machine, or tunnel to it (e.g. `ssh -L 5900:localhost:5900 host`) if you need it from elsewhere. Also make sure `SCREENSHOTTER_VNC_PORT` is actually set - it's unset (mirror disabled) by default.
