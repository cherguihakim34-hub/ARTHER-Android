
import json
import os
import re
from pathlib import Path

import httpx

from planner import create_plan, verify_execution

from memory_store import (
    get_history,
    save_message,
    save_memory,
    search_memories,
    get_memory_context,
)


# ============================================================
# CONFIGURATION
# ============================================================

BASE_URL = os.getenv(
    "AI_BASE_URL",
    "https://api.groq.com/openai/v1"
).rstrip("/")

MODEL = os.getenv(
    "AI_MODEL",
    "openai/gpt-oss-120b"
)

API_KEY = os.getenv(
    "AI_API_KEY",
    ""
)

MAX_TOOL_ROUNDS = 7
MAX_FILE_BYTES = 200_000

WORKSPACE = Path(
    os.getenv(
        "ARTHER_WORKSPACE",
        "/tmp/arther_workspace"
    )
).resolve()

WORKSPACE.mkdir(
    parents=True,
    exist_ok=True
)


# ============================================================
# SYSTEM PROMPT
# ============================================================

SYSTEM_PROMPT = """
You are ARTHER, an advanced multilingual AI assistant.

Your capabilities include:
- Understanding complex requests.
- Planning tasks.
- Using available tools.
- Executing multi-step operations.
- Analyzing tool results.
- Checking execution results.
- Maintaining long-term memory.
- Explaining errors honestly.

OPERATING RULES:

1. Understand the user's complete objective.
2. Follow the execution plan when appropriate.
3. Use available tools when necessary.
4. Analyze tool results before continuing.
5. Never invent successful operations.
6. Never claim that an action was completed unless evidence supports it.
7. Ask before destructive actions.
8. Work only inside the ARTHER workspace for file operations.
9. Never execute arbitrary shell commands.
10. Never expose API keys or credentials.

LONG-TERM MEMORY:

- Persistent memory is stored in PostgreSQL.
- Use save_memory for facts the user asks you to remember.
- Use search_memories to retrieve previously saved information.
- Never use write_file as a replacement for persistent memory.
- Never claim memory was saved unless PostgreSQL confirms success.
- Never save passwords, API keys, or sensitive secrets.

LANGUAGES:

Support Arabic, French, and English.
Reply in the language requested by the user.
If no language is specified, follow the user's language.

You are ARTHER.
Be accurate, practical, analytical, and honest.
"""


# ============================================================
# MEMORY REQUEST DETECTION
# ============================================================

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

    return any(
        re.search(
            pattern,
            prompt,
            re.IGNORECASE
        )
        for pattern in patterns
    )


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

    return any(
        re.search(
            pattern,
            prompt,
            re.IGNORECASE
        )
        for pattern in patterns
    )


def extract_memory_content(prompt):

    content = prompt.strip()

    if ":" in content:
        content = content.split(
            ":",
            1
        )[1].strip()

    content = re.split(
        r"\s*(?:استعمل أداة|ولا تكتف|لا تكتف|"
        r"use the save_memory|do not just|"
        r"please use|utilise l'outil|"
        r"ne te contente pas)\b",
        content,
        maxsplit=1,
        flags=re.IGNORECASE,
    )[0].strip()

    content = re.sub(
        r"^(?:من فضلك\s*)?(?:تذكر|تذكّر|احفظ|خزن|"
        r"remember|memorize|memorise|"
        r"souviens-toi|mémorise)\s*",
        "",
        content,
        flags=re.IGNORECASE,
    ).strip()

    return content[:4000]


# ============================================================
# FILE SECURITY
# ============================================================

def safe_path(relative_path):

    candidate = (
        WORKSPACE / relative_path
    ).resolve()

    if (
        candidate != WORKSPACE
        and WORKSPACE not in candidate.parents
    ):
        raise ValueError(
            "Access outside workspace is forbidden."
        )

    return candidate


# ============================================================
# FILE TOOLS
# ============================================================

def list_files(path="."):

    target = safe_path(path)

    if not target.exists() or not target.is_dir():

        return {
            "ok": False,
            "error": "Directory not found"
        }

    return {
        "ok": True,

        "items": [
            {
                "name": item.name,

                "type": (
                    "directory"
                    if item.is_dir()
                    else "file"
                ),

                "size": (
                    item.stat().st_size
                    if item.is_file()
                    else None
                ),
            }

            for item in sorted(
                target.iterdir()
            )[:200]
        ],
    }


def read_file(path):

    target = safe_path(path)

    if not target.is_file():

        return {
            "ok": False,
            "error": "File not found"
        }

    if target.stat().st_size > MAX_FILE_BYTES:

        return {
            "ok": False,
            "error": "File too large"
        }

    try:

        return {
            "ok": True,
            "path": path,
            "content": target.read_text("utf-8"),
        }

    except UnicodeDecodeError:

        return {
            "ok": False,
            "error": "Not a UTF-8 text file"
        }


def write_file(path, content):

    target = safe_path(path)

    data = content.encode("utf-8")

    if len(data) > MAX_FILE_BYTES:

        return {
            "ok": False,
            "error": "Content exceeds 200 KB"
        }

    target.parent.mkdir(
        parents=True,
        exist_ok=True
    )

    target.write_bytes(data)

    return {
        "ok": True,
        "path": str(
            target.relative_to(WORKSPACE)
        ),
        "bytes_written": len(data),
    }


# ============================================================
# AI TOOLS
# ============================================================

TOOLS = [

    {
        "type": "function",

        "function": {

            "name": "list_files",

            "description": (
                "List files in the ARTHER workspace."
            ),

            "parameters": {

                "type": "object",

                "properties": {

                    "path": {
                        "type": "string"
                    }

                },

                "required": [],

            },

        },
    },

    {
        "type": "function",

        "function": {

            "name": "read_file",

            "description": (
                "Read a UTF-8 file in the workspace."
            ),

            "parameters": {

                "type": "object",

                "properties": {

                    "path": {
                        "type": "string"
                    }

                },

                "required": ["path"],

            },

        },
    },

    {
        "type": "function",

        "function": {

            "name": "write_file",

            "description": (
                "Create or replace a UTF-8 file."
            ),

            "parameters": {

                "type": "object",

                "properties": {

                    "path": {
                        "type": "string"
                    },

                    "content": {
                        "type": "string"
                    },

                },

                "required": [
                    "path",
                    "content"
                ],

            },

        },
    },

    {
        "type": "function",

        "function": {

            "name": "save_memory",

            "description": (
                "Save a fact to permanent PostgreSQL memory."
            ),

            "parameters": {

                "type": "object",

                "properties": {

                    "content": {
                        "type": "string"
                    },

                    "category": {
                        "type": "string"
                    },

                },

                "required": ["content"],

            },

        },
    },

    {
        "type": "function",

        "function": {

            "name": "search_memories",

            "description": (
                "Search persistent PostgreSQL memories."
            ),

            "parameters": {

                "type": "object",

                "properties": {

                    "query": {
                        "type": "string"
                    },

                    "limit": {
                        "type": "integer"
                    },

                },

                "required": [],

            },

        },
    },

]


# ============================================================
# TOOL EXECUTOR
# ============================================================

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

    return {
        "ok": False,
        "error": "Unknown tool"
    }


# ============================================================
# MAIN AGENT
# ============================================================

async def run_agent(
    prompt,
    language="auto",
    session_id="default"
):

    if not API_KEY:

        raise RuntimeError(
            "AI_API_KEY is not configured."
        )

    prompt = prompt.strip()

    history = await get_history(
        session_id,
        limit=12
    )

    tool_log = []

    await save_message(
        session_id,
        "user",
        prompt
    )

    # --------------------------------------------------------
    # DETERMINISTIC MEMORY SAVE
    # --------------------------------------------------------

    if is_memory_save_request(prompt):

        content = extract_memory_content(prompt)

        if not content:

            answer = (
                "لم أتمكن من تحديد المعلومة المطلوب حفظها."
            )

            await save_message(
                session_id,
                "assistant",
                answer
            )

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
                + result.get(
                    "error",
                    "خطأ غير معروف"
                )
            )

        await save_message(
            session_id,
            "assistant",
            answer
        )

        return {

            "success": True,
            "answer": answer,
            "tool_calls": tool_log,
            "rounds": 1,
            "session_id": session_id,

        }

    # --------------------------------------------------------
    # DETERMINISTIC MEMORY RETRIEVAL
    # --------------------------------------------------------

    if is_memory_search_request(prompt):

        rows = await search_memories(
            "",
            limit=20
        )

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

            answer = (
                "هذه المعلومات الموجودة في ذاكرتي الدائمة:\n"
            )

            answer += "\n".join(
                f"- {row['content']}"
                for row in rows
            )

        else:

            answer = (
                "لا توجد معلومات محفوظة حالياً "
                "في الذاكرة الدائمة."
            )

        await save_message(
            session_id,
            "assistant",
            answer
        )

        return {

            "success": True,
            "answer": answer,
            "tool_calls": tool_log,
            "rounds": 1,
            "session_id": session_id,

        }

    # --------------------------------------------------------
    # LOAD LONG-TERM MEMORY CONTEXT
    # --------------------------------------------------------

    memory_context = await get_memory_context(
        limit=12
    )

    system = SYSTEM_PROMPT

    if memory_context:

        system += (
            "\n\nKNOWN LONG-TERM MEMORIES:\n"
            + memory_context
        )

    messages = [

        {
            "role": "system",
            "content": system,
        },

        *history,

        {
            "role": "user",
            "content": (
                f"Language: {language}\n"
                f"Task: {prompt}"
            ),
        },

    ]

    # --------------------------------------------------------
    # PLANNER + EXECUTOR + VERIFIER
    # --------------------------------------------------------

    async with httpx.AsyncClient(
        timeout=90
    ) as client:

        # CREATE EXECUTION PLAN

        plan = await create_plan(
            client,
            BASE_URL,
            API_KEY,
            MODEL,
            prompt,
            language,
        )

        messages.append({

            "role": "system",

            "content": (
                "EXECUTION PLAN. "
                "Follow this plan safely. "
                "Use available tools when necessary.\n"
                + json.dumps(
                    plan,
                    ensure_ascii=False
                )
            ),

        })

        # EXECUTION LOOP

        for round_number in range(
            MAX_TOOL_ROUNDS
        ):

            response = await client.post(

                f"{BASE_URL}/chat/completions",

                headers={

                    "Authorization": (
                        f"Bearer {API_KEY}"
                    )

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
                    "AI provider error: "
                    + response.text[:800]
                )

            data = response.json()

            message = data["choices"][0]["message"]

            messages.append(message)

            calls = message.get(
                "tool_calls"
            ) or []

            # ------------------------------------------------
            # NO MORE TOOL CALLS
            # ------------------------------------------------

            if not calls:

                answer = (
                    message.get("content") or ""
                )

                # VERIFY EXECUTION

                verification = await verify_execution(

                    client,

                    BASE_URL,

                    API_KEY,

                    MODEL,

                    prompt,

                    plan,

                    tool_log,

                    answer,

                )

                await save_message(
                    session_id,
                    "assistant",
                    answer
                )

                return {

                    "success": True,

                    "answer": answer,

                    "plan": plan,

                    "planning_status": plan.get(
                        "status",
                        "planned"
                    ),

                    "verification": verification,

                    "tool_calls": tool_log,

                    "rounds": round_number + 1,

                    "session_id": session_id,

                }

            # ------------------------------------------------
            # EXECUTE REQUESTED TOOLS
            # ------------------------------------------------

            for call in calls:

                function = call.get(
                    "function",
                    {}
                )

                name = function.get(
                    "name",
                    ""
                )

                try:

                    arguments = json.loads(
                        function.get("arguments") or "{}"
                    )

                    if not isinstance(arguments, dict):
                        raise ValueError(
                            "Tool arguments must be a JSON object"
                        )

                    result = await call_tool(
                        name,
                        arguments
                    )

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

    # --------------------------------------------------------
    # MAXIMUM TOOL ROUNDS REACHED
    # --------------------------------------------------------

    answer = (
        "وصل ARTHER إلى الحد الأقصى لجولات الأدوات. "
        "لم يتم التأكد من اكتمال جميع الخطوات."
    )

    await save_message(
        session_id,
        "assistant",
        answer
    )

    return {

        "success": True,

        "answer": answer,

        "plan": plan,

        "planning_status": plan.get(
            "status",
            "planned"
        ),

        "verification": {

            "status": "partial",

            "checks": [],

            "note": (
                "تم الوصول إلى الحد الأقصى لجولات التنفيذ."
            ),

        },

        "tool_calls": tool_log,

        "rounds": MAX_TOOL_ROUNDS,

        "session_id": session_id,

    }
