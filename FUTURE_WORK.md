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
- **Partially done:** the nested X server itself is now selectable via `SCREENSHOTTER_DISPLAY_BACKEND` (`xephyr`, the prior default, or `xvfb` for host-display-free headless/CI runs) - see `DisplayBackend` in `Main.kt`. Resolution, worker jar path, and display search range are still hardcoded.

## 11. ~~Deterministic Rendering Environment~~ **[DONE]**
- **Problem:** The sandbox reused the real user's `$HOME`, so a launched app would pick up whatever GTK theme, font, icon theme, and dconf overrides that user happened to have configured on their real desktop - meaning the exact same test could render (and lay out text) differently from one machine, or even one user account, to the next.
- **Solution:** Give the sandbox its own throwaway `HOME` (and `XDG_CONFIG_HOME`) with a `gtk-3.0/settings.ini` pinning a fixed theme/icon/cursor set and font, plus `GTK_THEME=Adwaita` and `GSETTINGS_BACKEND=memory` so no ambient dconf database can leak in either.
*(Implemented in `SandboxManager.start()`/`writeGtkSettings()`. Note this is "same on every machine that has the pinned font installed", not a byte-for-byte guarantee - true hermeticity would mean vendoring the actual font file and pointing fontconfig at only that, so text metrics can't shift even if a distro ships a different build of DejaVu Sans.)*

## 12. ~~Filesystem Isolation for `launch_app`~~ **[DONE]**
- **Problem:** Item 11's isolated `$HOME` only stops apps that respect the `HOME` env var - a launched process could still reach the real user's actual home directory (dotfiles, SSH keys, other projects) via `getpwuid()`/hardcoded paths, or any other path on the host filesystem, since nothing actually restricted what it could open.
- **Solution:** Run `launch_app`'s command inside a `bwrap` (bubblewrap) mount namespace: bind the real filesystem in read-only for libraries/binaries, `--tmpfs` over the *real* HOME directory so it's genuinely empty from the sandboxed process's point of view regardless of which path it uses to get there, and expose only whatever the caller explicitly lists via a new `mounts` parameter (`{host_path, sandbox_path?, read_only?}`).
*(Implemented via `SandboxManager.bwrapCommand()`. The server's own working directory is re-exposed read-only on top of the HOME shadow, since it's typically nested under HOME and `launch_app` commands routinely reference it via relative paths. Verified: a real file written directly under `$HOME` was unreadable ("No such file or directory") from inside a launched process, while a directory passed via `mounts` was readable. Caveat: a custom `sandbox_path` must land somewhere bwrap can create it (e.g. under `$HOME` or `/tmp`, both writable) - remapping to an arbitrary new absolute path elsewhere fails with "Read-only file system", since the rest of `/` is bound read-only.)*

## 13. ~~VNC Mirror for Headless Backends~~ **[DONE]**
- **Problem:** With `SCREENSHOTTER_DISPLAY_BACKEND=xvfb` there's no window anywhere - the sandbox display is fully headless, so a human has no way to look at (or interact with) what's on it, unlike with Xephyr, which is just a plain window on the host display and thus already visible/clickable there (whether that's actually the case is entirely up to the host environment, not something the server controls or hides).
- **Solution:** Optionally start `x11vnc` against the sandbox display, gated by `SCREENSHOTTER_VNC_PORT` (unset by default - no VNC mirror at all unless explicitly requested). Bound with `-localhost`, so it never listens beyond loopback on its own; reaching it from elsewhere requires an explicit tunnel/port-forward.
*(Implemented in `SandboxManager.start()`/`vncPortFromEnv()`; the `x11vnc` process is `startTracked()` like every other sandbox process, so it's torn down by `stop()` and the crash watchdog the same way.)*

## 14. ~~Client-Selectable Image Quality~~ **[DONE]**
- **Problem:** `get_screenshot` and `highlight_area` always returned the full-resolution screen capture as a lossless PNG, even when the calling LLM only needed a rough look - wasting vision tokens on detail nobody asked for. Vision-model token cost scales with pixel dimensions, not file size/format, so a lossy-compression knob wouldn't have actually helped here.
- **Solution:** Added an optional `max_width` parameter to both tools: downscales the *returned* image to at most that many pixels wide (aspect ratio preserved) via `ScreenshotterServer.scaleToMaxWidth()`. Applied only to the image handed back over MCP - the full-resolution capture is still what feeds `hasScreenChanged()`/`getChangedBoundingBoxes()`/`updateLastScreenshot()`, so delta comparisons stay pixel-exact no matter what resolution a given call asked to receive. Omit the parameter for the prior (full-resolution) behavior.
*(Implemented in `ScreenshotterServer.scaleToMaxWidth()`, wired into `WorkerMain.kt`'s `takeScreenshot`/`highlightArea` actions and `Main.kt`'s `get_screenshot`/`highlight_area` tool schemas. Covered by `ScreenshotterServerTest`.)*

## Rejected Ideas

### Letting a `launch_app` process outlive the server (considered, rejected)
- **Idea:** Add a `detach`/`persist` flag to `launch_app` so a launched app keeps running after the MCP server (and thus the sandbox) shuts down, instead of being torn down along with everything else.
- **Why rejected:** The app only stays alive as long as the display server (Xephyr/Xvfb) and dbus-daemon it's connected to do - killing its pgid alone wouldn't be enough, since losing the X11 connection on `stop()`/watchdog teardown would take the app down anyway. Making it truly survive would mean making the *whole* sandbox teardown conditional on "no persistent apps left" - display server, dbus, at-spi, and the writable `sandboxHome` (which `stop()` currently deletes) all included. That reintroduces exactly the class of orphan-process/resource-leak risk that item 9 was hardened against, and pushes the project away from its core premise: an ephemeral, always-cleanly-torn-down sandbox for AI-driven testing, not a general persistent app launcher. Revisit only if a concrete use case (e.g. handing off a long-running dev server the AI configured) justifies the added lifecycle complexity.
