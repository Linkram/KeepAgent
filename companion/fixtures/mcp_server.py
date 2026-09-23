from mcp.server import MCPServer

server = MCPServer("keepagent-test")


@server.tool()
def add(a: int, b: int) -> int:
    """Add two fixture numbers."""
    return a + b


if __name__ == "__main__":
    server.run()
