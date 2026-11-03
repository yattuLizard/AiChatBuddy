"""Run Java's MCP client against both released Python SDK generations.

Usage: python tests/run-sdk-interop.py /path/to/python-v1 /path/to/python-v2
Each Python environment must already have mcp and uvicorn installed.
"""
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.request

root = Path(__file__).resolve().parents[1]
classpath = (root / "build/interop-classpath.txt").read_text().strip()
probe = ["java", "-cp", classpath, "io.github.mrgucci1.aiChatBuddy.mcp.McpInteropProbe"]
server = str(root / "tests/sdk_server.py")


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def run(*args):
    subprocess.run(probe + list(args), check=True, cwd=root, timeout=60)


for generation, python in enumerate(sys.argv[1:], 1):
    if generation > 2:
        raise SystemExit("Exactly two Python environments expected")
    subprocess.run([python, "-c", "from importlib.metadata import version; print('Official Python MCP SDK:',version('mcp'))"], check=True)
    for response_mode in ("json", "sse"):
        port = free_port()
        proc = subprocess.Popen([python, server, "http", response_mode, str(port)], cwd=root)
        try:
            for _ in range(100):
                if proc.poll() is not None:
                    raise RuntimeError("SDK server exited before startup")
                try:
                    with socket.create_connection(("127.0.0.1", port), timeout=.2):
                        break
                except OSError:
                    time.sleep(.1)
            else:
                raise RuntimeError("SDK server startup timeout")
            url = f"http://127.0.0.1:{port}/mcp"
            print(f"=== SDK generation={generation} response={response_mode} ===", flush=True)
            run("streamable-http", url, "auto")
            if generation == 2:
                run("streamable-http", url, "2025-11-25")
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=5)
    run("stdio", python, "auto", server)
print("PASS: all 8 official SDK interoperability scenarios", flush=True)
