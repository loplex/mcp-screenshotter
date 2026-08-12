# Future Work for MCP Screenshotter

This document serves to track ideas and future improvements for the MCP server that facilitates the automation and documentation of graphical applications (Desktop and Web).

## 1. ~~Accessibility API Integration (UI Tree)~~ **[DONE]**
- **Problem:** Currently, the LLM (agent) only sees a cluster of pixels and has to "guess" or visually analyze the image to find panels, buttons, and text fields.
- **Solution:** Add a tool (e.g., `get_ui_tree`) that utilizes Linux **AT-SPI2**.
- **Expected Behavior:** The server connects to the window running inside Xephyr and returns a structured JSON containing UI element types (panel, button, text), their names, and precise `bounding box` coordinates (x, y, width, height). This allows the agent to know exactly where to "drag" if it wants to resize a panel.
*(Implemented via JNA in AtSpiReader)*

## 2. ~~Highlighting UI Elements for Documentation~~ **[DONE]**
- **Concept:** When the agent clicks or interacts with an element (e.g., opening a specific menu), the server should inject (or draw via image processing) visual indicators into the final screenshot.
- **Usage:** Drawing a semi-transparent red border around a button for final documentation purposes, right before or as the screenshot is saved.
*(Implemented via `highlight_area` MCP tool and AWT Graphics2D)*

## 3. ~~Computer Vision (OpenCV) Fallback for Non-Standard Apps~~ **[DONE]**
- **Problem:** Not all applications support AT-SPI (e.g., old Java UIs without access bridges, custom game engines, apps running via Wine without a bridge).
- **Solution:** Add an image analysis tool using OpenCV (edge detection, Hough transform). By analyzing pixels, the server could identify straight lines acting as "panel splitters" and return their coordinates as an alternative to AT-SPI.
*(Implemented via `detect_ui_elements` MCP tool and OpenCV Canny edge detection)*

## 4. ~~Differential Images (Deltas) - Advanced Memory Management~~ **[DONE]**
- **Current State:** Initial comparison and cropping of changes are implemented.
- **Improvement:** Better compression and handling of minor visual changes (e.g., blinking cursors, ticking clocks) so these don't constantly trigger new large screen updates. Implementation of "thresholding" (ignoring changes below X percent/pixels).
*(Implemented via the `threshold` parameter in `get_screenshot` which returns a textual JSON response if the visual difference is below the percentage)*

## 5. ~~Clipboard Integration in Xephyr~~ **[DONE]**
- **Feature:** Tools for syncing, reading, or writing clipboard content directly to/from the isolated X11 server (Xephyr), allowing the agent to perform efficient `Copy` and `Paste` actions instead of typing long text character by character.
*(Implemented via AWT Clipboard in ClipboardManager)*

## 6. ~~Native Window Management (Resizing)~~ **[DONE]**
- **Feature:** Natively resize windows in X11 using JNA without relying on external processes like `xdotool` or a heavy Window Manager.
*(Implemented via JNA `XResizeWindow`)*

## 7. ~~Isolated App Launcher (Self-Managed Environment)~~ **[DONE]**
- **Feature:** Allow the MCP server to directly spawn target applications within a controlled, isolated environment (e.g., automatically starting Xephyr and its own `dbus-run-session` for AT-SPI2).
- **Usage:** Instead of the user or testing script setting up the X11 display, the LLM agent could call a `launch_app` tool (e.g., `launch_app("java SampleApp")`), and the server would fully containerize the GUI session.
*(Implemented via `SandboxManager` in `Main.kt`, which auto-starts Xephyr, an isolated `dbus-daemon` session, and `at-spi-bus-launcher` before exposing the `launch_app` MCP tool; verified end-to-end via `e2e/test_gui.py`)*

## 8. Multi-App / Multi-Window Session Management
- **Problem:** `launch_app` fires processes into the sandbox but the server has no notion of "which window belongs to which launched app." With multiple apps open, tools like `get_ui_tree`, `resize_window`, and `get_screenshot` implicitly operate on "the active window" or the whole display, which gets ambiguous fast.
- **Solution:** Track PIDs returned by `launch_app` and let tools optionally scope by window/PID (e.g., via `_NET_WM_PID` lookup through JNA/X11), plus add a `list_windows` tool and a `close_app`/`kill_app` tool for session cleanup.

## 9. ~~Sandbox Lifecycle & Resource Cleanup Hardening~~ **[DONE]**
- **Problem:** `SandboxManager.stop()` destroys `workerProc`, `atSpiProc`, and `xephyrProc`, but not the `dbus-daemon` process (only its address is captured, not its handle) and not any processes started via `launch_app`. Orphaned Xephyr/D-Bus/app processes can accumulate across repeated runs or crashes.
- **Solution:** Track the `dbus-daemon` PID (it's printed via `--print-pid=1`) and terminate it on `stop()`; track PIDs from `launchApp()` and terminate them (or their process group) on shutdown too. Consider wrapping the whole sandbox in a cgroup or process group for atomic teardown.
*(Implemented via `startTracked()`: every sandbox process - Xephyr, dbus-daemon (now `--nofork` instead of `--fork`-and-detach), at-spi-bus-launcher, the worker, and anything started via `launch_app` - runs under `setsid`, so each becomes its own process group that `killProcessGroup()` can `kill -TERM` as one unit, catching whatever children they spawn too. Beyond a graceful `stop()`, a small `sh` watchdog is spawned at startup that blocks reading its own stdin - a pipe whose write end the JVM holds open for its entire life without ever touching it - so the moment the JVM dies for *any* reason (`kill -9`, an OOM-kill, a native JNA/X11 crash that never reaches the shutdown hook) the kernel closes that pipe, the watchdog wakes up on EOF, and reaps every tracked group and temp file on its own. Verified by launching the server standalone and `kill -9`-ing it directly: Xephyr, dbus-daemon, at-spi-bus-launcher, and the worker were all gone within 3 seconds, no orphans left.)*

## 10. Configurable Sandbox Parameters
- **Problem:** Screen resolution (`1024x768`), worker jar path, and display search range are currently hardcoded in `Main.kt`.
- **Solution:** Expose these via environment variables or MCP server startup arguments (e.g., `SCREENSHOTTER_RESOLUTION`, `SCREENSHOTTER_WORKER_JAR`), so the same server binary can be reused across differently-sized target apps/CI environments without a rebuild.

## 11. ~~Deterministic Rendering Environment~~ **[DONE]**
- **Problem:** The sandbox reused the real user's `$HOME`, so a launched app would pick up whatever GTK theme, font, icon theme, and dconf overrides that user happened to have configured on their real desktop - meaning the exact same test could render (and lay out text) differently from one machine, or even one user account, to the next.
- **Solution:** Give the sandbox its own throwaway `HOME` (and `XDG_CONFIG_HOME`) with a `gtk-3.0/settings.ini` pinning a fixed theme/icon/cursor set and font, plus `GTK_THEME=Adwaita` and `GSETTINGS_BACKEND=memory` so no ambient dconf database can leak in either.
*(Implemented in `SandboxManager.start()`/`writeGtkSettings()`. Note this is "same on every machine that has the pinned font installed", not a byte-for-byte guarantee - true hermeticity would mean vendoring the actual font file and pointing fontconfig at only that, so text metrics can't shift even if a distro ships a different build of DejaVu Sans.)*
