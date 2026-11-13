import json
import os
from pathlib import Path

import httpx

from memory_store import (
    get_history,
    save_message,
    save_memory,
    search_memories,
    get_memory_context,
)

BASE_URL = os.getenv(
    "AI_BASE_URL", "https://api.groq.com/openai/v1"
).rstrip("/")
MODEL = os.getenv("AI_MODEL", "openai/gpt-oss-120b")
API_KEY = os.getenv("AI_API_KEY", "")

MAX_TOOL_ROUNDS = 5
MAX_FILE_BYTES = 200_000

WORKSPACE = Path(
    os.getenv("ARTHER_WORKSPACE", "/tmp/arther_workspace")
).resolve()
WORKSPACE.mkdir(parents=True, exist_ok=True)

SYSTEM_PROMPT = """
You are ARTHER, a multilingual autonomous assistant.

Think carefully, plan tasks, use tools, verify results.
Never claim an action you did not perform.
Work only inside the ARTHER workspace for file operations.
Ask before destructive actions.

LONG-TERM MEMORY:
- You have persistent memory tools.
- When the user explicitly says remember, save, or store a fact,
  you MUST call save_memory.
- When the user asks what you remember, search using search_memories.
- Use supplied memory context to maintain continuity across sessions.
- Never claim a memory was saved unless the tool confirms success.
- Do not save passwords, API keys, or sensitive secrets.

Reply in Arabic, French, or English according to the user.
"""


def safe_path(relative_path):
    candidate = (WORKSPACE / relative_path).resolve()
    if candidate != WORKSPACE and WORKSPACE not in candidate.parents:
        raise ValueError("Access outside workspace is forbidden.")
    return candidate


def list_files(path="."):
    target = safe_path(path)
    if not target.exists() or not target.is_dir():
        return {"ok": False, "error": "Directory not found"}
    return {
        "ok": True,
        "items": [
            {
                "name": p.name,
                "type": "directory" if p.is_dir() else "file",
                "size": p.stat().st_size if p.is_file() else None,
            }
            for p in sorted(target.iterdir())[:200]
        ],
    }


def read_file(path):
    target = safe_path(path)
    if not target.is_file():
        return {"ok": False, "error": "File not found"}
    if target.stat().st_size > MAX_FILE_BYTES:
        return {"ok": False, "error": "File too large"}
    try:
        return {"ok": True, "path": path, "content": target.read_text("utf-8")}
    except UnicodeDecodeError:
        return {"ok": False, "error": "Not a UTF-8 text file"}


def write_file(path, content):
    target = safe_path(path)
    data = content.encode("utf-8")
    if len(data) > MAX_FILE_BYTES:
        return {"ok": False, "error": "Content exceeds 200 KB"}
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    return {
        "ok": True,
        "path": str(target.relative_to(WORKSPACE)),
        "bytes_written": len(data),
    }


TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "list_files",
            "description": "List files in the ARTHER workspace.",
            "parameters": {
                "type": "object",
                "properties": {"path": {"type": "string"}},
                "required": [],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "Read a UTF-8 file in the workspace.",
            "parameters": {
                "type": "object",
                "properties": {"path": {"type": "string"}},
                "required": ["path"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "write_file",
            "description": "Create or replace a UTF-8 file.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string"},
                    "content": {"type": "string"},
                },
                "required": ["path", "content"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "save_memory",
            "description": "Save a fact to permanent long-term memory when the user asks you to remember it.",
            "parameters": {
                "type": "object",
                "properties": {
                    "content": {"type": "string"},
                    "category": {"type": "string"},
                },
                "required": ["content"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "search_memories",
            "description": "Search persistent memories for facts previously saved.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string"},
                    "limit": {"type": "integer"},
                },
                "required": [],
            },
        },
    },
]


async def call_tool(name, arguments):
    if name == "list_files":
        return list_files(**arguments)
    if name == "read_file":
        return read_file(**arguments)
    if name == "write_file":
        return write_file(**arguments)
    if name == "save_memory":
        return await save_memory(**arguments)
    if name == "search_memories":
        return await search_memories(**arguments)
    return {"ok": False, "error": "Unknown tool"}


async def run_agent(prompt, language="auto", session_id="default"):
    if not API_KEY:
        raise RuntimeError("AI_API_KEY is not configured.")

    history = await get_history(session_id, limit=12)
    memory_context = await get_memory_context(limit=12)

    system = SYSTEM_PROMPT
    if memory_context:
        system += "\n\nKNOWN LONG-TERM MEMORIES:\n" + memory_context

    messages = [
        {"role": "system", "content": system},
        *history,
        {"role": "user", "content": f"Language: {language}\nTask: {prompt}"},
    ]

    await save_message(session_id, "user", prompt)
    tool_log = []

    async with httpx.AsyncClient(timeout=90) as client:
        for round_number in range(MAX_TOOL_ROUNDS):
            response = await client.post(
                f"{BASE_URL}/chat/completions",
                headers={"Authorization": f"Bearer {API_KEY}"},
                json={
                    "model": MODEL,
                    "messages": messages,
                    "tools": TOOLS,
                    "tool_choice": "auto",
                    "temperature": 0.2,
                },
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
                answer = message.get("content") or ""
                await save_message(session_id, "assistant", answer)
                return {
                    "success": True,
                    "answer": answer,
                    "tool_calls": tool_log,
                    "rounds": round_number + 1,
                    "session_id": session_id,
                }

            for call in calls:
                function = call.get("function", {})
                name = function.get("name", "")
                try:
                    arguments = json.loads(function.get("arguments") or "{}")
                    result = await call_tool(name, arguments)
                except Exception as exc:
                    result = {"ok": False, "error": str(exc)}

                tool_log.append({"tool": name, "result": result})
                messages.append({
                    "role": "tool",
                    "tool_call_id": call["id"],
                    "content": json.dumps(result, ensure_ascii=False, default=str),
                })

    answer = "وصل ARTHER إلى الحد الأقصى لجولات الأدوات."
    await save_message(session_id, "assistant", answer)
    return {
        "success": True,
        "answer": answer,
        "tool_calls": tool_log,
        "rounds": MAX_TOOL_ROUNDS,
        "session_id": session_id,
    }