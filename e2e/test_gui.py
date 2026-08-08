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

def find_button_coords(node, target_name):
    if "desktops" in node:
        for desktop in node["desktops"]:
            res = find_button_coords(desktop, target_name)
            if res:
                return res
        return None
        
    if node.get("name") == target_name:
        return node
    for child in node.get("children", []):
        res = find_button_coords(child, target_name)
        if res:
            return res
    return None

def test_gui_scenario():
    print("=== Starting MCP Screenshotter GUI E2E Test ===")
    
    print("\n1. Verifying Python Sample App exists...")
    if not os.path.exists("e2e/sample_app.py"):
        raise Exception("e2e/sample_app.py not found!")

    jar_path = "server/target/screenshotter-server-0.1.0-SNAPSHOT-jar-with-dependencies.jar"
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
    
    try:
        # Give sandbox some time to spin up
        time.sleep(3)

        # Initialize
        send_request(mcp_proc, "initialize", req_id=1)
        mcp_proc.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "initialized"}) + "\n")
        mcp_proc.stdin.flush()
        
        # Use system python3 to launch the GTK3 script (so it has access to python3-gi)
        cmd = f"/usr/bin/python3 e2e/sample_app.py"
        res = send_request(mcp_proc, "tools/call", {"name": "launch_app", "arguments": {"command": cmd}}, req_id=2)
        print("Launch Response:", res)

        # Wait for the UI to render properly
        time.sleep(3)
        
        print("\n4. Calling 'get_ui_tree' to locate the button...")
        tree_res = send_request(mcp_proc, "tools/call", {"name": "get_ui_tree"}, req_id=3)
        
        raw_tree_json = tree_res["result"]["content"][0]["text"]
        try:
            ui_tree = json.loads(raw_tree_json)
        except Exception as e:
            print(f"Failed to parse ui_tree json. Raw tree json: {raw_tree_json!r}")
            raise e
        
        button_node = find_button_coords(ui_tree, "ClickMeButton")
        
        if button_node is not None:
            rect = button_node.get("rect", {})
            x = rect.get("x", 0)
            y = rect.get("y", 0)
            w = rect.get("width", 0)
            h = rect.get("height", 0)
            print(f"   Found button via AT-SPI2 at: X={x}, Y={y}, W={w}, H={h}")
        else:
            print("   AT-SPI2 failed. Falling back to OpenCV Computer Vision!")
            cv_res = send_request(mcp_proc, "tools/call", {"name": "detect_ui_elements"}, req_id=99)
            raw_text = cv_res["result"]["content"][0]["text"]
            cv_elements = json.loads(raw_text).get("detected_elements", [])
            print(f"   OpenCV found {len(cv_elements)} bounding boxes.")
            
            button_rect = None
            for rect in cv_elements:
                if 30 < rect["width"] < 300 and 10 < rect["height"] < 100:
                    button_rect = rect
                    break
            
            assert button_rect is not None, "OpenCV fallback also failed to find the button!"
            x = button_rect["x"]
            y = button_rect["y"]
            w = button_rect["width"]
            h = button_rect["height"]
            print(f"   Found button via OpenCV at: X={x}, Y={y}, W={w}, H={h}")
        
        center_x = x + (w // 2)
        center_y = y + (h // 2)
        
        print(f"\n5. Calling 'mouse_action' to click at ({center_x}, {center_y})...")
        send_request(mcp_proc, "tools/call", {
            "name": "mouse_action",
            "arguments": {"action": "click", "x": center_x, "y": center_y}
        }, req_id=4)
        
        time.sleep(1)
        
        print("\n6. Calling 'get_screenshot' to verify the result...")
        shot_res = send_request(mcp_proc, "tools/call", {"name": "get_screenshot", "arguments": {}}, req_id=5)
        print("Screenshot Response:", shot_res)
        
        b64_data = shot_res["result"]["content"][0]["data"]
        shot_path = "e2e/output/final_screenshot.png"
        os.makedirs(os.path.dirname(shot_path), exist_ok=True)
        with open(shot_path, "wb") as f:
            f.write(base64.b64decode(b64_data))
            
        print(f"\nSUCCESS! The E2E test finished.")
        print(f"The final screenshot has been saved to '{shot_path}'.")
        
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
