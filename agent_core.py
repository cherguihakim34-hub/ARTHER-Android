
import os
import json
import re
import base64
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

API_KEY = os.getenv("AI_API_KEY", "")

MAX_TOOL_ROUNDS = 7
MAX_FILE_BYTES = 200_000

WORKSPACE = Path(
    os.getenv(
        "ARTHER_WORKSPACE",
        "/tmp/arther_workspace"
    )
).resolve()

WORKSPACE.mkdir(parents=True, exist_ok=True)

GITHUB_REPOSITORY = os.getenv(
    "GITHUB_REPOSITORY",
    "cherguihakim34-hub/ARTHER-Android"
).strip()

GITHUB_BRANCH = os.getenv(
    "GITHUB_BRANCH",
    "main"
).strip()

GITHUB_API = "https://api.github.com"


# ============================================================
# SYSTEM PROMPT
# ============================================================

SYSTEM_PROMPT = """
You are ARTHER, an advanced multilingual AI assistant.

Your capabilities:
- Understand complex requests.
- Plan tasks.
- Use available tools.
- Execute multi-step operations.
- Analyze results.
- Verify actions.
- Maintain long-term memory.
- Read the configured GitHub repository.

RULES:

1. Never invent execution results.
2. Never claim an action succeeded without evidence.
3. Ask before destructive actions.
4. Never execute arbitrary shell commands.
5. Never expose API keys or credentials.
6. Local files are restricted to the ARTHER workspace.
7. GitHub access is read-only.
8. GitHub tools access only the configured repository.
9. Distinguish workspace files from repository files.
10. Never claim to have modified GitHub files.

MEMORY:
- Use save_memory for requested facts.
- Use search_memories for saved information.
- Never claim a memory was saved without confirmation.

LANGUAGES:
Arabic, French and English.

Reply in the user's language.
Be direct, accurate and practical.
"""


# ============================================================
# MEMORY REQUEST DETECTION
# ============================================================

def is_memory_save_request(prompt):

    patterns = [
        r"\bremember\b",
        r"\bmemorize\b",
        r"\bsave\s+(?:this|that|it)\s+in\s+(?:your\s+)?memory\b",
        r"تذكر",
        r"تذكّر",
        r"احفظ",
        r"خزن",
        r"سجل في ذاكرتك",
        r"احتفظ بهذه المعلومة",
        r"souviens-toi",
        r"mémorise",
        r"memorise",
    ]

    return any(
        re.search(pattern, prompt, re.IGNORECASE)
        for pattern in patterns
    )


def is_memory_search_request(prompt):

    patterns = [
        r"\bwhat do you remember\b",
        r"\bsearch (?:your )?memory\b",
        r"\brecall\b",
        r"ماذا تتذكر",
        r"ما الذي تتذكره",
        r"ما الذي حفظته",
        r"ابحث في ذاكرتك",
        r"هل تتذكر",
        r"ما اسم مشروعي",
        r"ما هي المعلومات المحفوظة",
        r"cherche dans ta mémoire",
        r"rappelle-toi",
    ]

    return any(
        re.search(pattern, prompt, re.IGNORECASE)
        for pattern in patterns
    )


def extract_memory_content(prompt):

    content = prompt.strip()

    if ":" in content:
        content = content.split(":", 1)[1].strip()

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
# LOCAL WORKSPACE SECURITY
# ============================================================

def safe_path(relative_path):

    candidate = (
        WORKSPACE / str(relative_path)
    ).resolve()

    if (
        candidate != WORKSPACE
        and WORKSPACE not in candidate.parents
    ):
        raise ValueError(
            "Access outside workspace is forbidden."
        )

    return candidate


def list_files(path="."):

    target = safe_path(path)

    if not target.exists() or not target.is_dir():
        return {
            "ok": False,
            "error": "Directory not found"
        }

    items = []

    for item in sorted(target.iterdir())[:200]:

        items.append({
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
        })

    return {
        "ok": True,
        "workspace": str(WORKSPACE),
        "items": items,
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
            "path": str(path),
            "content": target.read_text("utf-8"),
        }

    except UnicodeDecodeError:
        return {
            "ok": False,
            "error": "Not a UTF-8 text file"
        }


def write_file(path, content):

    target = safe_path(path)

    data = str(content).encode("utf-8")

    if len(data) > MAX_FILE_BYTES:
        return {
            "ok": False,
            "error": "Content exceeds 200 KB"
        }

    # Do not silently overwrite existing files.
    if target.exists():
        return {
            "ok": False,
            "error": (
                "File already exists. "
                "Overwriting is not allowed by this tool."
            )
        }

    target.parent.mkdir(
        parents=True,
        exist_ok=True
    )

    target.write_bytes(data)

    return {
        "ok": True,
        "path": str(target.relative_to(WORKSPACE)),
        "bytes_written": len(data),
    }


# ============================================================
# GITHUB REPOSITORY SECURITY
# ============================================================

def safe_repo_path(path=""):

    value = str(path or "").strip().replace("\\", "/")

    if value.startswith("/") or "\x00" in value:
        raise ValueError("Invalid repository path.")

    parts = [
        part
        for part in value.split("/")
        if part
    ]

    if any(part in {".", ".."} for part in parts):
        raise ValueError("Invalid repository path.")

    return "/".join(parts)


async def github_request(path=""):

    if (
        not GITHUB_REPOSITORY
        or GITHUB_REPOSITORY.count("/") != 1
    ):
        return {
            "ok": False,
            "error": "Invalid GITHUB_REPOSITORY setting."
        }

    safe_path = safe_repo_path(path)

    url = (
        f"{GITHUB_API}/repos/"
        f"{GITHUB_REPOSITORY}/contents/"
        f"{safe_path}"
    )

    headers = {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "ARTHER-Assistant",
    }

    async with httpx.AsyncClient(timeout=30) as client:

        response = await client.get(
            url,
            headers=headers,
            params={
                "ref": GITHUB_BRANCH
            },
        )

    if response.status_code == 404:
        return {
            "ok": False,
            "error": "Repository path not found."
        }

    if response.status_code == 403:
        return {
            "ok": False,
            "error": (
                "GitHub rate limit or access restriction."
            )
        }

    if response.status_code >= 400:
        return {
            "ok": False,
            "error": (
                f"GitHub API error {response.status_code}: "
                f"{response.text[:500]}"
            )
        }

    return {
        "ok": True,
        "data": response.json(),
    }


# ============================================================
# GITHUB DIRECTORY LISTING
# ============================================================

async def list_repo_files(path=""):

    try:

        safe_path = safe_repo_path(path)

        result = await github_request(safe_path)

        if not result.get("ok"):
            return result

        data = result["data"]

        if not isinstance(data, list):
            return {
                "ok": False,
                "error": (
                    "The requested path is a file, not a directory."
                )
            }

        items = []

        for item in data[:500]:

            items.append({
                "name": item.get("name"),
                "path": item.get("path"),
                "type": item.get("type"),
                "size": item.get("size"),
            })

        return {
            "ok": True,
            "repository": GITHUB_REPOSITORY,
            "branch": GITHUB_BRANCH,
            "path": safe_path or ".",
            "count": len(items),
            "items": items,
        }

    except Exception as exc:

        return {
            "ok": False,
            "error": str(exc)[:500],
        }


# ============================================================
# GITHUB FILE READING
# ============================================================

async def read_repo_file(path):

    try:

        safe_path = safe_repo_path(path)

        if not safe_path:
            return {
                "ok": False,
                "error": "A file path is required."
            }

        result = await github_request(safe_path)

        if not result.get("ok"):
            return result

        data = result["data"]

        if (
            isinstance(data, list)
            or data.get("type") != "file"
        ):
            return {
                "ok": False,
                "error": "The requested path is not a file."
            }

        encoded = data.get("content", "")

        if encoded.strip():

            content = base64.b64decode(
                encoded
            ).decode("utf-8")

        else:

            download_url = data.get("download_url")

            if not download_url:
                return {
                    "ok": False,
                    "error": "File content is unavailable."
                }

            # Only download from GitHub's official raw host.
            if not download_url.startswith(
                "https://raw.githubusercontent.com/"
            ):
                return {
                    "ok": False,
                    "error": "Untrusted download URL rejected."
                }

            async with httpx.AsyncClient(timeout=30) as client:
                response = await client.get(download_url)

            if response.status_code >= 400:
                return {
                    "ok": False,
                    "error": "Could not download repository file."
                }

            content = response.text

        if len(content.encode("utf-8")) > MAX_FILE_BYTES:
            return {
                "ok": False,
                "error": "File exceeds the 200 KB read limit."
            }

        return {
            "ok": True,
            "repository": GITHUB_REPOSITORY,
            "branch": GITHUB_BRANCH,
            "path": safe_path,
            "content": content,
        }

    except UnicodeDecodeError:

        return {
            "ok": False,
            "error": "File is not UTF-8 text."
        }

    except Exception as exc:

        return {
            "ok": False,
            "error": str(exc)[:500],
        }


# ============================================================
# TOOL DEFINITIONS
# ============================================================

def tool_definition(name, description, properties, required=None):

    return {
        "type": "function",
        "function": {
            "name": name,
            "description": description,
            "parameters": {
                "type": "object",
                "properties": properties,
                "required": required or [],
            },
        },
    }


TOOLS = [

    tool_definition(
        "list_files",
        "List files in the temporary ARTHER workspace.",
        {
            "path": {"type": "string"}
        },
    ),

    tool_definition(
        "read_file",
        "Read a UTF-8 file from the ARTHER workspace.",
        {
            "path": {"type": "string"}
        },
        ["path"],
    ),

    tool_definition(
        "write_file",
        "Create a new UTF-8 file in the workspace. Existing files cannot be overwritten.",
        {
            "path": {"type": "string"},
            "content": {"type": "string"},
        },
        ["path", "content"],
    ),

    tool_definition(
        "list_repo_files",
        "List files and directories in the configured GitHub repository. Read-only.",
        {
            "path": {"type": "string"}
        },
    ),

    tool_definition(
        "read_repo_file",
        "Read a UTF-8 file from the configured GitHub repository. Read-only.",
        {
            "path": {"type": "string"}
        },
        ["path"],
    ),

    tool_definition(
        "save_memory",
        "Save a fact to permanent PostgreSQL memory.",
        {
            "content": {"type": "string"},
            "category": {"type": "string"},
        },
        ["content"],
    ),

    tool_definition(
        "search_memories",
        "Search persistent PostgreSQL memories.",
        {
            "query": {"type": "string"},
            "limit": {"type": "integer"},
        },
    ),

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

    if name == "list_repo_files":
        return await list_repo_files(**arguments)

    if name == "read_repo_file":
        return await read_repo_file(**arguments)

    if name == "save_memory":
        return await save_memory(**arguments)

    if name == "search_memories":
        return await search_memories(**arguments)

    return {
        "ok": False,
        "error": "Unknown tool"
    }


# ============================================================
# PROVIDER REQUEST
# ============================================================

async def ask_agent(client, messages):

    response = await client.post(
        f"{BASE_URL}/chat/completions",
        headers={
            "Authorization": f"Bearer {API_KEY}",
            "Content-Type": "application/json",
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
            + response.text[:1200]
        )

    data = response.json()

    choices = data.get("choices") or []

    if not choices:
        raise RuntimeError(
            "AI provider returned no choices."
        )

    return choices[0]["message"]


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
    # SAVE MEMORY
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
                "تم حفظ المعلومة في الذاكرة الدائمة."
            )
        else:
            answer = (
                "فشل حفظ المعلومة: "
                + str(result.get("error", "Unknown error"))
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
    # SEARCH MEMORY
    # --------------------------------------------------------

    if is_memory_search_request(prompt):

        rows = await search_memories(
            "",
            limit=20
        )

        rows = rows or []

        tool_log.append({
            "tool": "search_memories",
            "result": {
                "ok": True,
                "count": len(rows),
                "memories": rows,
            },
        })

        if rows:
            answer = (
                "هذه المعلومات الموجودة في ذاكرتي:\n"
                + "\n".join(
                    f"- {row['content']}"
                    for row in rows
                )
            )
        else:
            answer = (
                "لا توجد معلومات محفوظة حالياً."
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
    # LOAD MEMORY CONTEXT
    # --------------------------------------------------------

    memory_context = await get_memory_context(
        limit=12
    )

    system = SYSTEM_PROMPT

    if memory_context:
        system += (
            "\n\nKNOWN LONG-TERM MEMORIES:\n"
            + str(memory_context)
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
                "EXECUTION PLAN. Follow it safely. "
                "Use tools when necessary. "
                "Never claim an operation succeeded without its result.\n"
                + json.dumps(
                    plan,
                    ensure_ascii=False
                )
            ),
        })

        for round_number in range(MAX_TOOL_ROUNDS):

            message = await ask_agent(
                client,
                messages
            )

            calls = message.get("tool_calls") or []

            if not calls:

                answer = message.get("content") or ""

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
                        "unknown"
                    ),
                    "verification": verification,
                    "tool_calls": tool_log,
                    "rounds": round_number + 1,
                    "session_id": session_id,
                }

            messages.append(message)

            for call in calls:

                function = call.get("function", {})
                name = function.get("name", "")

                try:

                    arguments = json.loads(
                        function.get("arguments") or "{}"
                    )

                    if not isinstance(arguments, dict):
                        raise ValueError(
                            "Tool arguments must be a JSON object."
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
    # MAXIMUM ROUNDS
    # --------------------------------------------------------

    answer = (
        "وصل ARTHER إلى الحد الأقصى لجولات التنفيذ. "
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
        "planning_status": plan.get("status", "unknown"),
        "verification": {
            "status": "partial",
            "checks": [],
            "completed_steps": [],
            "failed_steps": [],
            "note": "Maximum tool rounds reached.",
        },
        "tool_calls": tool_log,
        "rounds": MAX_TOOL_ROUNDS,
        "session_id": session_id,
    }
