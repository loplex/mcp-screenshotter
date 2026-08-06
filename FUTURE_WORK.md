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

## 7. Isolated App Launcher (Self-Managed Environment)
- **Feature:** Allow the MCP server to directly spawn target applications within a controlled, isolated environment (e.g., automatically starting Xephyr and its own `dbus-run-session` for AT-SPI2).
- **Usage:** Instead of the user or testing script setting up the X11 display, the LLM agent could call a `launch_app` tool (e.g., `launch_app("java SampleApp")`), and the server would fully containerize the GUI session.
