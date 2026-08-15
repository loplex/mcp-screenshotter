#!/usr/bin/env python3
"""
E2E test for the OpenCV-backed 'detect_ui_elements' tool: launches the GTK3 sample app
(whose default tab already has a frame, sliders, radio buttons, and a button - plenty of
rectangular edges for Canny/contour detection to latch onto), waits for it to actually
render via AT-SPI2, then calls 'detect_ui_elements' through the real server -> HTTP ->
worker -> VisionFallback pipeline.

This is deliberately not a re-test of VisionFallbackTest's Canny/contour logic (that's
already covered in-process, including the blank-image-finds-nothing case) - what this adds
is confidence that the native OpenCV load survives the sandboxed worker process's actual
runtime environment, and that the tool wiring end to end (server dispatch -> worker HTTP
command -> screenshot -> detectElements -> JSON back to the client) doesn't break.
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

def get_ui_tree(proc, req_id):
    res = call_tool(proc, "get_ui_tree", req_id=req_id)
    return json.loads(res["result"]["content"][0]["text"])

def walk(node):
    """Depth-first iterator over every node in the AT-SPI2 tree (desktops -> children -> ...)."""
    if "desktops" in node:
        for desktop in node["desktops"]:
            yield from walk(desktop)
        return
    yield node
    for child in node.get("children", []):
        yield from walk(child)

def find_first(tree, predicate):
    for node in walk(tree):
        if predicate(node):
            return node
    return None

def wait_for_node(proc, req_id, predicate, description, timeout=15.0, interval=0.5):
    """Polls get_ui_tree until a node matching `predicate` shows up via AT-SPI2."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        tree = get_ui_tree(proc, req_id); req_id += 1
        node = find_first(tree, predicate)
        if node is not None:
            return node, req_id
        time.sleep(interval)
    raise Exception(f"Timed out after {timeout}s waiting for {description} via AT-SPI2!")

def test_vision_scenario():
    print("=== Starting OpenCV 'detect_ui_elements' E2E Test ===")

    jar_path = "packaging/target/mcp-screenshotter/mcp-screenshotter-server.jar"
    if not os.path.exists(jar_path):
        raise FileNotFoundError(f"{jar_path} not found. Run 'mvn package' first.")

    print("\n1. Starting MCP Server (which will manage the display server, DBus, and Worker via HTTP)...")
    mcp_proc = subprocess.Popen(
        ["java", "-jar", jar_path],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
    )

    req_id = 1
    try:
        time.sleep(3)
        send_request(mcp_proc, "initialize", req_id=req_id); req_id += 1
        mcp_proc.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "initialized"}) + "\n")
        mcp_proc.stdin.flush()

        print("\n2. Launching the GTK3 sample app...")
        cmd = "/usr/bin/python3 e2e/sample_app.py"
        call_tool(mcp_proc, "launch_app", {"command": cmd}, req_id); req_id += 1

        print("\n3. Waiting for the app to actually render (via AT-SPI2) before detecting...")
        _, req_id = wait_for_node(
            mcp_proc, req_id,
            lambda n: n.get("name") == "ClickMeButton",
            "the sample app's 'ClickMeButton' button",
        )

        print("\n4. Calling 'detect_ui_elements'...")
        res = call_tool(mcp_proc, "detect_ui_elements", req_id=req_id); req_id += 1
        payload = json.loads(res["result"]["content"][0]["text"])
        elements = payload["detected_elements"]
        print(f"   OpenCV reported {len(elements)} detected element(s).")

        assert elements, (
            "expected at least one detected UI element - the default tab has a frame, sliders, "
            "radio buttons and a button, all of which give Canny edge detection plenty to find"
        )
        for el in elements:
            assert set(el.keys()) >= {"x", "y", "width", "height"}, f"malformed element: {el}"
            assert el["x"] >= 0 and el["y"] >= 0, f"element has negative origin: {el}"
            # Mirrors VisionFallback's own noise filter (width > 15, height > 10) - a value
            # outside it here would mean the filter isn't actually being applied end to end.
            assert el["width"] > 15 and el["height"] > 10, f"element below the size filter slipped through: {el}"
            assert el["width"] < 4000 and el["height"] < 4000, f"element has implausible bounds: {el}"

        print("   Success: OpenCV detected plausible UI element bounds through the full pipeline.")
        print("\nSUCCESS! OpenCV 'detect_ui_elements' E2E test finished.")

    except Exception as e:
        print(f"\nTEST FAILED: {e}")
        print("\nServer STDERR Output:")
        print(mcp_proc.stderr.read())
        raise
    finally:
        mcp_proc.terminate()
        mcp_proc.wait()

if __name__ == "__main__":
    test_vision_scenario()
