# MCP Screenshotter

A headless **Model Context Protocol (MCP)** server that empowers AI assistants (like Claude) to safely interact with, test, and control Linux GUI applications inside a fully isolated virtual X11 sandbox.

By utilizing **Xephyr**, **AT-SPI2** (Accessibility Toolkit), and **D-Bus**, this tool creates a secure, sterile desktop environment. The AI can launch applications, read their UI structure, perform mouse/keyboard actions, and capture visual feedback without any risk of interfering with the host's actual graphical environment.

## 🏗️ Architecture

The project is built as a multi-module Maven application to guarantee complete environment isolation:

1. **`screenshotter-server` (The Orchestrator)**:
   - Runs on the host system and speaks the standard MCP JSON-RPC protocol over Stdio (e.g., with Claude Desktop).
   - Responsible for launching the isolated environment (`Xephyr` on a dynamic display, isolated `D-Bus`, and `at-spi-bus-launcher`).
   - Ensures no host environment variables (like Wayland or host DBus addresses) leak into the sandbox.
   - Forwards AI tool calls to the worker via a lightweight local HTTP API.

2. **`screenshotter-worker` (The Isolated Worker)**:
   - Runs *exclusively* inside the Xephyr sandbox.
   - Hosts a fast local HTTP server to receive commands from the Server module.
   - Communicates with running applications using Java AWT (`Robot`) for native mouse/keyboard events and `AtSpiReader` (JNA) for traversing the accessibility tree.
   - Captures screenshots of the isolated X11 display.

## 🚀 Features

- **Strict Isolation**: GUI apps cannot see, capture, or interact with the user's host desktop.
- **Accessibility Tree Parsing**: Extracts a structured, semantic JSON representation of the GUI (buttons, text fields, checkboxes, bounding boxes) using AT-SPI2.
- **Smart Deltas**: Can compute pixel-level bounding box differences between interactions to optimize LLM visual token usage.
- **Native Interaction**: Emulates real hardware mouse clicks and keystrokes using X11 bindings.
- **Dynamic Port & Display Resolution**: Automatically finds free X11 displays and HTTP ports on startup.

## 📋 Prerequisites

To build and run this project, you need a Linux environment with the following dependencies installed:

- **Java**: JDK 11+ for compiling the server/worker (Java 8 is supported/required for running ATK wrapper legacy apps).
- **Maven**: For building the project.
- **Xephyr**: Nested X server (`xserver-xephyr` on Debian/Ubuntu).
- **AT-SPI2**: Linux Accessibility toolkit (`at-spi2-core`).

## 🛠️ Building the Project

Compile the multi-module Maven project and build fat jars:

```bash
mvn clean package -Dmaven.test.skip=true
```

This will output two executable jars:
- `server/target/screenshotter-server-0.2.0-SNAPSHOT-jar-with-dependencies.jar`
- `worker/target/screenshotter-worker-0.2.0-SNAPSHOT-jar-with-dependencies.jar`

## ⚙️ Usage & Configuration

### 1. Claude Desktop Integration

To give Claude Desktop access to the sandbox, add the server to your `claude_desktop_config.json` (typically located at `~/.config/Claude/claude_desktop_config.json`):

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

Restart Claude Desktop, and you can now ask the AI to:
> *"Launch my GUI app using `launch_app` and navigate through its menus to test the login screen."*

### 2. Testing via MCP Inspector

You can manually test the tools using the official MCP Inspector in your browser:

```bash
npx -y @modelcontextprotocol/inspector java -jar server/target/screenshotter-server-0.2.0-SNAPSHOT-jar-with-dependencies.jar
```

## 🧰 Available MCP Tools

The server exposes the following MCP tools to the AI:

- `launch_app(command: string)`:
  Executes a terminal command *inside* the Xephyr sandbox. Used to start the target application.
  
- `get_ui_tree()`:
  Reads the AT-SPI2 accessibility tree and returns a semantic JSON representation of the visible UI elements and their coordinates.

- `get_screenshot(threshold?: number)`:
  Captures the current state of the sandbox display. Returns a Base64-encoded PNG image. Can optionally calculate visual deltas.

- `mouse_action(action: string, x: number, y: number, button?: number)`:
  Moves the mouse to `(x, y)` and performs the specified action (`move`, `click`).
  
- `keyboard_action(action: string, text: string)`:
  Types a string of text into the currently focused UI element inside the sandbox.
