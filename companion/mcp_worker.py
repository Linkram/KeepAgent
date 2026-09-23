"""Bounded MCP discovery/call through owner-configured servers; SDK handles version negotiation."""
import asyncio
import json
from pathlib import Path
import sys


async def main():
    from mcp import Client
    from mcp.client.stdio import StdioServerParameters
    config = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    task = json.loads(sys.argv[2])
    server = config[task["server"]]
    if "url" in server:
        target = server["url"]
    else:
        target = StdioServerParameters(command=server["command"], args=server.get("args", []), cwd=server.get("cwd"))
    async with Client(target, read_timeout_seconds=60) as client:
        if "tool" in task:
            result = await client.call_tool(task["tool"], task.get("arguments", {}))
        else:
            result = await client.list_tools(cursor=task.get("cursor"))
        print(result.model_dump_json(by_alias=True))
        if getattr(result, "is_error", False):
            raise SystemExit(1)


if __name__ == "__main__":
    asyncio.run(main())
