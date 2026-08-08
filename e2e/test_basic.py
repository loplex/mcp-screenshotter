#!/usr/bin/env python3
import subprocess
import json
import os

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
    
    # Read response
    line = proc.stdout.readline()
    if not line:
        raise Exception("Server closed unexpectedly")
    return json.loads(line)

def test_mcp_server():
    print("Starting E2E Test Client for MCP Screenshotter...")
    
    jar_path = "server/target/screenshotter-server-0.1.0-SNAPSHOT-jar-with-dependencies.jar"
    if not os.path.exists(jar_path):
        print(f"ERROR: {jar_path} not found. Run 'mvn package' first.")
        return
        
    # Start the server (inherits DISPLAY from current environment)
    proc = subprocess.Popen(
        ["java", "-jar", jar_path],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )
    
    try:
        # 1. Initialize Protocol
        print("-> Sending 'initialize'...")
        init_res = send_request(proc, "initialize", req_id=1)
        assert init_res["result"]["protocolVersion"] == "2024-11-05"
        
        # Send initialized notification (no id)
        proc.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "initialized"}) + "\n")
        proc.stdin.flush()
        
        # 2. List Tools
        print("-> Sending 'tools/list'...")
        tools_res = send_request(proc, "tools/list", req_id=2)
        tools = tools_res["result"]["tools"]
        tool_names = [t["name"] for t in tools]
        
        assert "get_screenshot" in tool_names
        assert "get_ui_tree" in tool_names
        assert "detect_ui_elements" in tool_names
        assert "get_clipboard" in tool_names
        print(f"   Success: Found {len(tools)} tools exposed by the server.")
        
        # 3. Test Clipboard (Stateful Native interaction)
        print("-> Testing 'set_clipboard'...")
        set_res = send_request(proc, "tools/call", {
            "name": "set_clipboard", 
            "arguments": {"text": "E2E_Test_Payload_123"}
        }, req_id=3)
        assert not set_res["result"].get("isError", False)
        
        print("-> Testing 'get_clipboard'...")
        get_res = send_request(proc, "tools/call", {
            "name": "get_clipboard"
        }, req_id=4)
        clip_content = get_res["result"]["content"][0]["text"]
        
        assert clip_content == "E2E_Test_Payload_123", f"Expected 'E2E_Test_Payload_123' but got '{clip_content}'"
        print("   Success: Clipboard native interaction is working!")
        
        print("\nAll E2E scenarios PASSED successfully!")
        
    except Exception as e:
        print(f"\nE2E TEST FAILED: {e}")
        # Print stderr from Java server for debugging
        print("\nServer STDERR Output:")
        print(proc.stderr.read())
    finally:
        proc.terminate()
        proc.wait()

if __name__ == "__main__":
    test_mcp_server()
