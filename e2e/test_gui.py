#!/usr/bin/env python3
import subprocess
import json
import sys
import time
import os
import base64

def send_request(proc, method, params=None, req_id=1):
    req = {
        "jsonrpc": "2.0",
        "id": req_id,
        "method": method
    }
    if params is not None:
        req["params"] = params

    msg = json.dumps(req)
    proc.stdin.write(msg + "\n")
    proc.stdin.flush()

    line = proc.stdout.readline()
    if not line:
        raise Exception("Server closed unexpectedly")
    try:
        return json.loads(line)
    except Exception as e:
        print(f"Failed to parse JSON. Raw line: {line!r}")
        raise e

def call_tool(proc, name, arguments=None, req_id=1):
    res = send_request(proc, "tools/call", {"name": name, "arguments": arguments or {}}, req_id)
    if res.get("result", {}).get("isError"):
        raise Exception(f"Tool '{name}' returned an error: {res['result']}")
    return res

def get_ui_tree(proc, req_id):
    res = call_tool(proc, "get_ui_tree", req_id=req_id)
    raw = res["result"]["content"][0]["text"]
    try:
        return json.loads(raw)
    except Exception as e:
        print(f"Failed to parse ui_tree json. Raw tree json: {raw!r}")
        raise e

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

def find_all(tree, predicate):
    return [node for node in walk(tree) if predicate(node)]

def wait_for_node(proc, req_id, predicate, description, timeout=15.0, interval=0.5):
    """
    Polls get_ui_tree until a node matching `predicate` shows up via AT-SPI2, instead of
    guessing a fixed sleep duration for "the app has rendered and registered itself" - the
    same class of bug as the blind sleep the AT-SPI2 bus startup itself used to have (see
    da32570), just one layer up at the launched-app level. Returns (node, next_req_id) so
    callers can keep threading req_id through like every other call site here.
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        tree = get_ui_tree(proc, req_id); req_id += 1
        node = find_first(tree, predicate)
        if node is not None:
            return node, req_id
        time.sleep(interval)
    raise Exception(f"Timed out after {timeout}s waiting for {description} via AT-SPI2!")

def center_of(rect):
    return rect["x"] + rect["width"] // 2, rect["y"] + rect["height"] // 2

def find_split_pane_handle(tree, exclude_rect=None):
    """
    Finds a GtkPaned ("split pane" in AT-SPI2 terms) with exactly two visible
    children and returns (handle_x, handle_y, orientation, child0_rect, child1_rect).
    orientation is "horizontal" (children side-by-side) or "vertical" (stacked).
    `exclude_rect` skips a pane whose rect matches (used to tell an outer pane
    apart from a nested one occupying the same area).
    """
    candidates = find_all(tree, lambda n: n.get("role") == "split pane")
    for pane in candidates:
        children = [c for c in pane.get("children", []) if "rect" in c]
        if len(children) != 2:
            continue
        if exclude_rect is not None and pane.get("rect") == exclude_rect:
            continue
        r0, r1 = children[0]["rect"], children[1]["rect"]
        if r0["x"] != r1["x"]:
            # side-by-side panes -> horizontal splitter sits between them
            handle_x = (r0["x"] + r0["width"] + r1["x"]) // 2
            handle_y = r0["y"] + r0["height"] // 2
            return handle_x, handle_y, "horizontal", pane, r0, r1
        else:
            # stacked panes -> vertical (horizontal-looking) splitter between them
            handle_x = r0["x"] + r0["width"] // 2
            handle_y = (r0["y"] + r0["height"] + r1["y"]) // 2
            return handle_x, handle_y, "vertical", pane, r0, r1
    return None

def drag(proc, req_id_start, x1, y1, x2, y2):
    """Press at (x1,y1), move to (x2,y2) while the button stays down, then release."""
    req_id = req_id_start
    call_tool(proc, "mouse_action", {"action": "press", "x": x1, "y": y1}, req_id); req_id += 1
    call_tool(proc, "mouse_action", {"action": "move", "x": x2, "y": y2}, req_id); req_id += 1
    call_tool(proc, "mouse_action", {"action": "release", "x": x2, "y": y2}, req_id); req_id += 1
    return req_id

def test_gui_scenario():
    print("=== Starting MCP Screenshotter GUI E2E Test ===")

    print("\n1. Verifying Python Sample App exists...")
    if not os.path.exists("e2e/sample_app.py"):
        raise Exception("e2e/sample_app.py not found!")

    jar_path = "server/target/mcp-screenshotter-server.jar"
    if not os.path.exists(jar_path):
        print(f"ERROR: {jar_path} not found. Run 'mvn package' first.")
        return

    print("\n2. Starting MCP Server (which will manage Xephyr, DBus, and Worker via HTTP)...")
    mcp_proc = subprocess.Popen(
        ["java", "-jar", jar_path],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )

    req_id = 1
    try:
        # Give sandbox some time to spin up
        time.sleep(3)

        # Initialize
        send_request(mcp_proc, "initialize", req_id=req_id); req_id += 1
        mcp_proc.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "initialized"}) + "\n")
        mcp_proc.stdin.flush()

        # Use system python3 to launch the GTK3 script (so it has access to python3-gi)
        cmd = "/usr/bin/python3 e2e/sample_app.py"
        res = call_tool(mcp_proc, "launch_app", {"command": cmd}, req_id); req_id += 1
        print("Launch Response:", res)

        print("\n3. Switching to the 'Resizable Panes' tab...")
        tab_node, req_id = wait_for_node(
            mcp_proc, req_id,
            lambda n: n.get("role") == "page tab" and n.get("name") == "Resizable Panes",
            "the 'Resizable Panes' tab",
        )
        tab_x, tab_y = center_of(tab_node["rect"])
        call_tool(mcp_proc, "mouse_action", {"action": "click", "x": tab_x, "y": tab_y}, req_id); req_id += 1
        time.sleep(1)

        print("\n4. Widening the left pane (horizontal splitter) to reveal the hidden code...")
        ui_tree = get_ui_tree(mcp_proc, req_id); req_id += 1
        hpane = find_split_pane_handle(ui_tree)
        assert hpane is not None, "Could not locate the horizontal split pane via AT-SPI2!"
        hx, hy, orientation, _, r0, r1 = hpane
        assert orientation == "horizontal", f"Expected the first split pane to be horizontal, got {orientation}"
        print(f"   Horizontal splitter handle at ({hx}, {hy}); dragging it 460px to the right.")
        req_id = drag(mcp_proc, req_id, hx, hy, hx + 460, hy)
        time.sleep(1)

        print("\n5. Pulling the vertical splitter up to enlarge the bottom-right pane...")
        ui_tree = get_ui_tree(mcp_proc, req_id); req_id += 1
        outer_hpane_rect = find_first(ui_tree, lambda n: n.get("role") == "split pane")["rect"]
        vpane = find_split_pane_handle(ui_tree, exclude_rect=outer_hpane_rect)
        assert vpane is not None, "Could not locate the vertical split pane via AT-SPI2!"
        vx, vy, orientation, _, top_rect, bottom_rect = vpane
        assert orientation == "vertical", f"Expected the nested split pane to be vertical, got {orientation}"
        print(f"   Vertical splitter handle at ({vx}, {vy}); dragging it 100px up.")
        req_id = drag(mcp_proc, req_id, vx, vy, vx, vy - 100)
        time.sleep(1)

        print("\n6. Scrolling down inside the bottom-right pane to reveal the secret phrase...")
        ui_tree = get_ui_tree(mcp_proc, req_id); req_id += 1
        vpane_again = find_split_pane_handle(ui_tree, exclude_rect=outer_hpane_rect)
        _, _, _, _, _, bottom_rect = vpane_again
        scroll_x, scroll_y = center_of(bottom_rect)
        # A generous notch count: the exact content height (and thus lines-per-notch) shifts
        # with pane width, so scroll well past the end rather than tune this to a fragile exact value.
        call_tool(mcp_proc, "mouse_action", {"action": "scroll", "x": scroll_x, "y": scroll_y, "amount": 40}, req_id); req_id += 1
        time.sleep(1)

        print("\n7. Calling 'get_screenshot' to capture the final state...")
        shot_res = call_tool(mcp_proc, "get_screenshot", {}, req_id); req_id += 1

        b64_data = shot_res["result"]["content"][0]["data"]
        shot_path = "e2e/output/final_screenshot.png"
        os.makedirs(os.path.dirname(shot_path), exist_ok=True)
        with open(shot_path, "wb") as f:
            f.write(base64.b64decode(b64_data))

        print(f"\nSUCCESS! The E2E test finished.")
        print(f"The final screenshot has been saved to '{shot_path}'.")
        print("It should show: the tab switched to 'Resizable Panes', the left pane widened")
        print("enough to reveal 'Code: Alpha-77X', and the bottom-right pane enlarged and")
        print("scrolled down enough to reveal the 'Secret Phrase: Omega Protocol'.")

    except Exception as e:
        print(f"\nTEST FAILED: {e}")
        sys.exit(1)
    finally:
        mcp_proc.terminate()
        try:
            err_output = mcp_proc.stderr.read()
            if err_output:
                print("\nServer STDERR Output:")
                print(err_output)
        except:
            pass
        mcp_proc.wait()

if __name__ == "__main__":
    test_gui_scenario()
