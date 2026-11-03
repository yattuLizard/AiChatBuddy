"""Real official-Python-SDK interoperability fixture; never used in production."""
import sys
from importlib.metadata import version

major = int(version("mcp").split(".")[0])
transport = sys.argv[1] if len(sys.argv) > 1 else "http"
json_response = len(sys.argv) > 2 and sys.argv[2] == "json"
if major >= 2:
    from mcp.server import MCPServer
    server = MCPServer("AiChatBuddy interop fixture")
else:
    from mcp.server.fastmcp import FastMCP
    server = FastMCP("AiChatBuddy interop fixture", json_response=json_response)

@server.tool()
def echo(value: str, count: int = 1) -> dict:
    """Return a read-only value and count for protocol verification."""
    return {"value": value, "count": count}

@server.tool()
def fail() -> str:
    """Intentionally fail to verify tool errors are not transport failures."""
    raise ValueError("intentional fixture failure")

if __name__ == "__main__":
    if transport == "stdio":
        server.run(transport="stdio")
    else:
        import uvicorn
        app = server.streamable_http_app(json_response=json_response) if major >= 2 else server.streamable_http_app()
        uvicorn.run(app, host="127.0.0.1", port=int(sys.argv[3]), log_level="warning")
