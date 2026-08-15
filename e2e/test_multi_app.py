#!/usr/bin/env python3
"""
E2E test for multi-app/multi-window session management (FUTURE_WORK #8):
launches two GUI apps side by side, uses 'list_windows' to tell them apart by
their launch_app PID, resizes just one of them via 'resize_window'+window_id,
uses 'close_app' to tear down one without touching the other, and finally
confirms that resizing a stale (already-closed) window_id doesn't crash the
worker (code review #2 - BadWindow used to take down the whole process).
"""
import subprocess
import json
import time
import os

def send_request(proc, method, params=None, req_id=1):
    req = {"jsonrpc": "2.0", "id": req_id, "method": method}
    if params is not None:
        req["params"] = params
    proc.stdin.write(json.dumps(req) + "\n")
    proc.stdin.flush()
    line = proc.stdout.readline()
    if not line:
        raise Exception("Server closed unexpectedly")
    return json.loads(line)

def call_tool(proc, name, arguments=None, req_id=1):
    res = send_request(proc, "tools/call", {"name": name, "arguments": arguments or {}}, req_id)
    if res.get("result", {}).get("isError"):
        raise Exception(f"Tool '{name}' returned an error: {res['result']}")
    return res

def list_windows(proc, req_id):
    res = call_tool(proc, "list_windows", req_id=req_id)
    return json.loads(res["result"]["content"][0]["text"])["windows"]

def wait_for_windows(proc, req_id, predicate, description, timeout=15.0, interval=0.5):
    """
    Polls list_windows until `predicate(windows)` is truthy, instead of guessing a fixed sleep
    duration for "both apps have created their windows" - same fix as test_gui.py's
    wait_for_node() for AT-SPI2 (which flaked in CI on a blind sleep), just against
    list_windows instead of get_ui_tree. Returns (windows, next_req_id).
    """
    deadline = time.time() + timeout
    windows = []
    while time.time() < deadline:
        windows = list_windows(proc, req_id); req_id += 1
        if predicate(windows):
            return windows, req_id
        time.sleep(interval)
    raise Exception(f"Timed out after {timeout}s waiting for {description} (last saw: {windows})")

def test_multi_app_scenario():
    print("=== Starting Multi-App Session Management E2E Test ===")

    jar_path = "packaging/target/mcp-screenshotter/mcp-screenshotter-server.jar"
    if not os.path.exists(jar_path):
        print(f"ERROR: {jar_path} not found. Run 'mvn package' first.")
        return

    print("\n1. Starting MCP Server (which will manage the display server, DBus, and Worker via HTTP)...")
    mcp_proc = subprocess.Popen(
        ["java", "-Xmx256m", "-jar", jar_path],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
    )

    req_id = 1
    try:
        time.sleep(3)
        send_request(mcp_proc, "initialize", req_id=req_id); req_id += 1
        mcp_proc.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "initialized"}) + "\n")
        mcp_proc.stdin.flush()

        print("\n2. Launching two independent app instances...")
        cmd = "/usr/bin/python3 e2e/sample_app.py"
        res1 = call_tool(mcp_proc, "launch_app", {"command": cmd}, req_id); req_id += 1
        pid1 = int(res1["result"]["content"][0]["text"].split("PID ")[1].split(" ")[0])
        res2 = call_tool(mcp_proc, "launch_app", {"command": cmd}, req_id); req_id += 1
        pid2 = int(res2["result"]["content"][0]["text"].split("PID ")[1].split(" ")[0])
        print(f"   launch_app PIDs: {pid1}, {pid2}")
        assert pid1 != pid2, "Each launch_app call should get its own PID"

        print("\n3. Calling 'list_windows' and correlating windows back to launch_app PIDs...")
        windows, req_id = wait_for_windows(
            mcp_proc, req_id,
            lambda ws: len(ws) == 2,
            "both apps' windows to appear",
        )
        print(f"   {len(windows)} window(s) reported: {windows}")
        launched_pids = {w["launched_pid"] for w in windows}
        assert launched_pids == {pid1, pid2}, (
            f"Expected windows' launched_pid to be {{{pid1}, {pid2}}}, got {launched_pids}"
        )
        window1 = next(w for w in windows if w["launched_pid"] == pid1)
        window2 = next(w for w in windows if w["launched_pid"] == pid2)
        assert window1["command"] == cmd and window2["command"] == cmd

        print("\n4. Resizing only window 1 via resize_window+window_id...")
        call_tool(mcp_proc, "resize_window", {
            "window_id": window1["window_id"], "width": 300, "height": 250
        }, req_id); req_id += 1
        time.sleep(1)

        windows = list_windows(mcp_proc, req_id); req_id += 1
        w1_after = next(w for w in windows if w["launched_pid"] == pid1)
        w2_after = next(w for w in windows if w["launched_pid"] == pid2)
        # GTK renegotiates its own minimum size on a ConfigureNotify, so the exact end size isn't
        # guaranteed to be 300x250 - what matters here is that only the *targeted* window moved
        # off its original 800x600, and the other one was left completely alone.
        assert (w1_after["width"], w1_after["height"]) != (window1["width"], window1["height"]), (
            "Window 1 should have changed size after a resize_window call scoped to its window_id"
        )
        assert (w2_after["width"], w2_after["height"]) == (window2["width"], window2["height"]), (
            "Window 2's size should be untouched by a resize scoped to window 1"
        )
        print(f"   Success: only the targeted window was resized (now {w1_after['width']}x{w1_after['height']}).")

        print("\n5. Closing app 1 via 'close_app' and verifying app 2 survives...")
        call_tool(mcp_proc, "close_app", {"pid": pid1}, req_id); req_id += 1
        time.sleep(1)

        windows = list_windows(mcp_proc, req_id); req_id += 1
        remaining_pids = {w["launched_pid"] for w in windows}
        assert remaining_pids == {pid2}, f"Expected only app 2 (PID {pid2}) left, saw {remaining_pids}"
        print("   Success: app 1 is gone, app 2 is still running.")

        print("\n6. Closing an already-closed PID should fail cleanly...")
        retry = send_request(mcp_proc, "tools/call", {"name": "close_app", "arguments": {"pid": pid1}}, req_id); req_id += 1
        assert retry["result"].get("isError"), "Re-closing an already-closed PID should report an error"
        print("   Success: re-closing PID {} was correctly rejected.".format(pid1))

        print("\n7. Resizing a stale window_id (app 1's, already closed) should not crash the worker...")
        stale_window_id = window1["window_id"]
        call_tool(mcp_proc, "resize_window", {
            "window_id": stale_window_id, "width": 400, "height": 300
        }, req_id); req_id += 1
        # A BadWindow X11 error from resizing a since-closed (or made-up) window_id used to hit
        # Xlib's default error handler, which calls exit() at the native level and takes down the
        # whole worker process - not just this one tool call (code review #2). Confirm the worker
        # is still alive and responsive by checking it still reports app 2's window afterward.
        windows = list_windows(mcp_proc, req_id); req_id += 1
        assert {w["launched_pid"] for w in windows} == {pid2}, (
            "worker should still be alive and reporting app 2's window after resizing a stale window_id"
        )
        print("   Success: stale window_id was ignored, worker kept running.")

        print("\nSUCCESS! Multi-app session management E2E test finished.")

    except Exception as e:
        print(f"\nTEST FAILED: {e}")
        print("\nServer STDERR Output:")
        print(mcp_proc.stderr.read())
        raise
    finally:
        mcp_proc.terminate()
        mcp_proc.wait()

if __name__ == "__main__":
    test_multi_app_scenario()
