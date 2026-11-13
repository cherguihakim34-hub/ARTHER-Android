
import json
import os
import re
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
- Persistent memory is stored in PostgreSQL, not workspace files.
- Use save_memory for facts the user asks you to remember.
- Use search_memories to find previously saved facts.
- Never use write_file as a replacement for persistent memory.
- Never claim a memory was saved unless PostgreSQL confirms success.
- Do not save passwords, API keys, or sensitive secrets.

Reply in Arabic, French, or English according to the user.
"""


def is_memory_save_request(prompt):
    patterns = [
        r"\bremember\b",
        r"\bmemorize\b",
        r"\bsave\s+(?:this|that|it|the following)\s+(?:in\s+)?(?:your\s+)?memory\b",
        r"\bstore\s+(?:this|that|it)\s+in\s+memory\b",
        r"تذكر",
        r"تذكّر",
        r"احفظ",
        r"خزن",
        r"سجل في ذاكرتك",
        r"احتفظ بهذه المعلومة",
        r"souviens-toi",
        r"mémorise",
        r"memorise",
        r"enregistre dans ta mémoire",
    ]
    return any(re.search(p, prompt, re.IGNORECASE) for p in patterns)


def is_memory_search_request(prompt):
    patterns = [
        r"\bwhat do you remember\b",
        r"\bsearch (?:your )?(?:long.term )?memory\b",
        r"\blook in (?:your )?memory\b",
        r"\brecall\b",
        r"ماذا تتذكر",
        r"ما الذي تتذكره",
        r"ما الذي حفظته",
        r"ابحث في ذاكرتك",
        r"في الذاكرة الدائمة",
        r"استرجع من ذاكرتك",
        r"هل تتذكر",
        r"ما اسم مشروعي",
        r"ما هي المعلومات المحفوظة",
        r"qu'est-ce que tu te rappelles",
        r"que mémorises-tu",
        r"cherche dans ta mémoire",
        r"rappelle-toi",
    ]
    return any(re.search(p, prompt, re.IGNORECASE) for p in patterns)


def extract_memory_content(prompt):
    content = prompt.strip()

    # Prefer the text after a colon, e.g.:
    # "احفظ في ذاكرتك: اسم مشروعي هو ORBIT-27"
    if ":" in content:
        content = content.split(":", 1)[1].strip()

    # Remove instructions that are not part of the fact.
    content = re.split(
        r"\s*(?:استعمل أداة|ولا تكتف|لا تكتف|"
        r"use the save_memory|do not just|"
        r"please use|utilise l'outil|"
        r"ne te contente pas)\b",
        content,
        maxsplit=1,
        flags=re.IGNORECASE,
    )[0].strip()

    # Remove common command prefixes if no colon was supplied.
    content = re.sub(
        r"^(?:من فضلك\s*)?(?:تذكر|تذكّر|احفظ|خزن|"
        r"remember|memorize|memorise|"
        r"souviens-toi|mémorise)\s*",
        "",
        content,
        flags=re.IGNORECASE,
    ).strip()

    return content[:4000]


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
        return {
            "ok": True,
            "path": path,
            "content": target.read_text("utf-8"),
        }
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
                "properties": {
                    "path": {"type": "string"}
                },
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
                "properties": {
                    "path": {"type": "string"}
                },
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
            "description": "Save a fact to permanent PostgreSQL memory.",
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
            "description": "Search persistent PostgreSQL memories.",
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

    prompt = prompt.strip()
    history = await get_history(session_id, limit=12)
    tool_log = []

    await save_message(session_id, "user", prompt)

    # Deterministic memory saving: do not leave this decision to the AI model.
    if is_memory_save_request(prompt):
        content = extract_memory_content(prompt)

        if not content:
            answer = "لم أتمكن من تحديد المعلومة المطلوب حفظها."
            await save_message(session_id, "assistant", answer)
            return {
                "success": True,
                "answer": answer,
                "tool_calls": [],
                "rounds": 1,
                "session_id": session_id,
            }

        result = await save_memory(
            content=content,
            category="user_fact",
        )
        tool_log.append({
            "tool": "save_memory",
            "result": result,
        })

        if result.get("ok"):
            answer = (
                "تم حفظ المعلومة في الذاكرة الدائمة "
                "بقاعدة البيانات PostgreSQL."
            )
        else:
            answer = (
                "فشل حفظ المعلومة في الذاكرة الدائمة: "
                + result.get("error", "خطأ غير معروف")
            )

        await save_message(session_id, "assistant", answer)

        return {
            "success": True,
            "answer": answer,
            "tool_calls": tool_log,
            "rounds": 1,
            "session_id": session_id,
        }

    # Deterministic memory retrieval.
    if is_memory_search_request(prompt):
        rows = await search_memories("", limit=20)
        result = {
            "ok": True,
            "count": len(rows),
            "memories": rows,
        }
        tool_log.append({
            "tool": "search_memories",
            "result": result,
        })

        if rows:
            answer = "هذه المعلومات الموجودة في ذاكرتي الدائمة:\n"
            answer += "\n".join(
                f"- {row['content']}" for row in rows
            )
        else:
            answer = "لا توجد معلومات محفوظة حالياً في الذاكرة الدائمة."

        await save_message(session_id, "assistant", answer)

        return {
            "success": True,
            "answer": answer,
            "tool_calls": tool_log,
            "rounds": 1,
            "session_id": session_id,
        }

    memory_context = await get_memory_context(limit=12)

    system = SYSTEM_PROMPT
    if memory_context:
        system += (
            "\n\nKNOWN LONG-TERM MEMORIES:\n"
            + memory_context
        )

    messages = [
        {"role": "system", "content": system},
        *history,
        {
            "role": "user",
            "content": f"Language: {language}\nTask: {prompt}",
        },
    ]

    async with httpx.AsyncClient(timeout=90) as client:
        for round_number in range(MAX_TOOL_ROUNDS):
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
                await save_message(
                    session_id, "assistant", answer
                )

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
                    arguments = json.loads(
                        function.get("arguments") or "{}"
                    )
                    result = await call_tool(name, arguments)
                except Exception as exc:
                    result = {
                        "ok": False,
                        "error": str(exc),
                    }

                tool_log.append({
                    "tool": name,
                    "result": result,
                })

                messages.append({
                    "role": "tool",
                    "tool_call_id": call["id"],
                    "content": json.dumps(
                        result,
                        ensure_ascii=False,
                        default=str,
                    ),
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
