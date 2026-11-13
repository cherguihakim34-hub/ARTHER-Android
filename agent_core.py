
import json
import os
from pathlib import Path
from typing import Any

import httpx

BASE_URL = os.getenv(
    "AI_BASE_URL",
    "https://api.groq.com/openai/v1"
).rstrip("/")
MODEL = os.getenv("AI_MODEL", "openai/gpt-oss-120b")
API_KEY = os.getenv("AI_API_KEY", "")
MAX_TOOL_ROUNDS = 5
MAX_FILE_BYTES = 200_000

# مساحة عمل معزولة على الخادم، وليست ملفات الهاتف.
WORKSPACE = Path(
    os.getenv("ARTHER_WORKSPACE", "/tmp/arther_workspace")
).resolve()
WORKSPACE.mkdir(parents=True, exist_ok=True)

SYSTEM_PROMPT = """
You are ARTHER, a multilingual research and engineering agent.
Plan before acting. Use available tools when useful.
Work only inside the ARTHER workspace.
Never claim access to the user's phone, GitHub or internet
unless a connected tool actually provides that access.
Explain actions and results honestly.
Ask for confirmation before destructive actions.
Reply in Arabic, French or English according to the user.
"""


def safe_path(relative_path: str) -> Path:
    candidate = (WORKSPACE / relative_path).resolve()

    if candidate != WORKSPACE and WORKSPACE not in candidate.parents:
        raise ValueError("Access outside workspace is forbidden.")

    return candidate


def list_files(path: str = ".") -> dict:
    target = safe_path(path)

    if not target.exists() or not target.is_dir():
        return {"ok": False, "error": "Directory not found."}

    items = []

    for item in sorted(target.iterdir())[:200]:
        items.append({
            "name": item.name,
            "type": "directory" if item.is_dir() else "file",
            "size": item.stat().st_size if item.is_file() else None
        })

    return {"ok": True, "items": items}


def read_file(path: str) -> dict:
    target = safe_path(path)

    if not target.is_file():
        return {"ok": False, "error": "File not found."}

    if target.stat().st_size > MAX_FILE_BYTES:
        return {"ok": False, "error": "File exceeds size limit."}

    try:
        return {
            "ok": True,
            "path": path,
            "content": target.read_text(encoding="utf-8")
        }
    except UnicodeDecodeError:
        return {"ok": False, "error": "Not a UTF-8 text file."}


def write_file(path: str, content: str) -> dict:
    target = safe_path(path)
    data = content.encode("utf-8")

    if len(data) > MAX_FILE_BYTES:
        return {"ok": False, "error": "Content exceeds 200 KB."}

    if not target.name:
        return {"ok": False, "error": "Filename required."}

    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)

    return {
        "ok": True,
        "path": str(target.relative_to(WORKSPACE)),
        "bytes_written": len(data)
    }


TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "list_files",
            "description": "List files in the ARTHER workspace.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string"}
                },
                "required": []
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "Read a UTF-8 file in the workspace.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string"}
                },
                "required": ["path"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "write_file",
            "description": "Create or replace a UTF-8 file in the workspace.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string"},
                    "content": {"type": "string"}
                },
                "required": ["path", "content"]
            }
        }
    }
]


def call_tool(name: str, arguments: dict) -> dict:
    if name == "list_files":
        return list_files(**arguments)

    if name == "read_file":
        return read_file(**arguments)

    if name == "write_file":
        return write_file(**arguments)

    return {"ok": False, "error": "Unknown tool."}


async def run_agent(prompt: str, language: str = "auto") -> dict:
    if not API_KEY:
        raise RuntimeError("AI_API_KEY is not configured.")

    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {
            "role": "user",
            "content": f"Language: {language}\nTask: {prompt}"
        }
    ]

    tool_log = []

    async with httpx.AsyncClient(timeout=90) as client:
        for _ in range(MAX_TOOL_ROUNDS):
            response = await client.post(
                f"{BASE_URL}/chat/completions",
                headers={
                    "Authorization": f"Bearer {API_KEY}"
                },
                json={
                    "model": MODEL,
                    "messages": messages,
                    "tools": TOOLS,
                    "tool_choice": "auto",
                    "temperature": 0.2
                }
            )

            if response.status_code >= 400:
                raise RuntimeError(
                    f"AI provider error: {response.text[:800]}"
                )

            data = response.json()
            message = data["choices"][0]["message"]
            messages.append(message)

            calls = message.get("tool_calls") or []

            if not calls:
                return {
                    "success": True,
                    "answer": message.get("content") or "",
                    "tool_calls": tool_log,
                    "rounds": len(tool_log)
                }

            for call in calls:
                function = call.get("function", {})
                name = function.get("name", "")

                try:
                    arguments = json.loads(
                        function.get("arguments") or "{}"
                    )
                    result = call_tool(name, arguments)
                except Exception as exc:
                    result = {
                        "ok": False,
                        "error": str(exc)
                    }

                tool_log.append({
                    "tool": name,
                    "result": result
                })

                messages.append({
                    "role": "tool",
                    "tool_call_id": call["id"],
                    "content": json.dumps(
                        result,
                        ensure_ascii=False
                    )
                })

    return {
        "success": True,
        "answer": "وصل الوكيل إلى حد جولات الأدوات.",
        "tool_calls": tool_log
    }
