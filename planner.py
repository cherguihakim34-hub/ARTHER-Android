import json
import re


MAX_COMPLETION_TOKENS = 700
MAX_VERIFICATION_CHARS = 7000


def _json_object(raw):
    """Extract a JSON object from the model response."""

    if isinstance(raw, dict):
        return raw

    content = str(raw or "").strip()

    content = re.sub(
        r"^```(?:json)?\s*|\s*```$",
        "",
        content,
        flags=re.IGNORECASE,
    ).strip()

    try:
        result = json.loads(content)
        if isinstance(result, dict):
            return result
    except Exception:
        pass

    # Find the first balanced JSON object.
    start = content.find("{")

    if start >= 0:
        depth = 0
        in_string = False
        escaped = False

        for index in range(start, len(content)):
            char = content[index]

            if in_string:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
                continue

            if char == '"':
                in_string = True
            elif char == "{":
                depth += 1
            elif char == "}":
                depth -= 1

                if depth == 0:
                    candidate = content[start:index + 1]

                    try:
                        result = json.loads(candidate)
                        if isinstance(result, dict):
                            return result
                    except Exception:
                        break

    raise ValueError(
        "The AI response did not contain valid JSON."
    )


# ============================================================
# AI REQUEST
# ============================================================

async def _ask(
    client,
    base_url,
    api_key,
    model,
    system,
    user,
):
    url = f"{base_url.rstrip('/')}/chat/completions"

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }

    payload = {
        "model": model,
        "messages": [
            {
                "role": "system",
                "content": system,
            },
            {
                "role": "user",
                "content": user,
            },
        ],
        "temperature": 0.2,
        "max_completion_tokens": MAX_COMPLETION_TOKENS,
        "reasoning_effort": "low",
    }

    # Try JSON mode first.
    json_payload = {
        **payload,
        "response_format": {
            "type": "json_object"
        },
    }

    response = await client.post(
        url,
        headers=headers,
        json=json_payload,
    )

    # Some compatible endpoints may not support response_format.
    if response.status_code == 400:
        response = await client.post(
            url,
            headers=headers,
            json=payload,
        )

    if response.status_code >= 400:
        raise RuntimeError(
            "Groq API request failed: "
            + response.text[:1500]
        )

    data = response.json()
    choices = data.get("choices") or []

    if not choices:
        raise ValueError(
            "Groq returned no choices."
        )

    message = choices[0].get("message") or {}
    content = message.get("content")

    if isinstance(content, list):
        content = "".join(
            item.get("text", "")
            for item in content
            if isinstance(item, dict)
        )

    if not content:
        finish_reason = choices[0].get(
            "finish_reason",
            "unknown",
        )

        raise ValueError(
            "Groq returned empty content. "
            f"Finish reason: {finish_reason}"
        )

    return _json_object(content)


# ============================================================
# PLANNER
# ============================================================

async def create_plan(
    client,
    base_url,
    api_key,
    model,
    prompt,
    language="auto",
):
    system = """
You are ARTHER's planning engine.

You are a TEXT-ONLY PLANNER.

You do not have access to tools.
Never call or simulate a function.
Never produce tool calls.
Never execute actions.

Your only task is to create a written execution plan.

Divide the user's objective into 1 to 7 practical steps.

Return ONLY a valid JSON object.

Required structure:

{
    "objective": "Main objective",
    "reasoning_summary": "Short planning approach",
    "steps": [
        {
            "id": 1,
            "description": "Step description",
            "tools": ["list_repo_files"],
            "expected_result": "Expected result"
        }
    ]
}

Available capability labels:

- list_files
- read_file
- write_file
- list_repo_files
- read_repo_file
- save_memory
- search_memories
- none

The tools field is only a text label.
It does not call or execute anything.

Rules:

1. Never invent tools.
2. Never claim that a step was executed.
3. Never invent results.
4. Never propose arbitrary shell commands.
5. Never propose destructive actions without approval.
6. Return valid JSON only.
7. Use the requested language.
8. Keep the plan concise.
"""

    request = {
        "language": str(language or "auto")[:30],
        "task": str(prompt or "")[:3000],
    }

    try:
        result = await _ask(
            client,
            base_url,
            api_key,
            model,
            system,
            json.dumps(
                request,
                ensure_ascii=False,
            ),
        )

        steps = result.get("steps")

        if not isinstance(steps, list) or not steps:
            raise ValueError(
                "Planner returned no valid steps."
            )

        allowed_tools = {
            "list_files",
            "read_file",
            "write_file",
            "list_repo_files",
            "read_repo_file",
            "save_memory",
            "search_memories",
            "none",
        }

        clean_steps = []

        for index, step in enumerate(steps[:7], 1):
            if not isinstance(step, dict):
                continue

            tools = step.get("tools", [])

            if not isinstance(tools, list):
                tools = []

            clean_tools = [
                tool
                for tool in tools
                if (
                    isinstance(tool, str)
                    and tool in allowed_tools
                )
            ]

            clean_steps.append({
                "id": index,
                "description": str(
                    step.get(
                        "description",
                        "Execute task step",
                    )
                )[:500],
                "tools": clean_tools,
                "expected_result": str(
                    step.get(
                        "expected_result",
                        "",
                    )
                )[:500],
            })

        if not clean_steps:
            raise ValueError(
                "Planner produced no usable steps."
            )

        return {
            "objective": str(
                result.get("objective", prompt)
            )[:1000],
            "reasoning_summary": str(
                result.get("reasoning_summary", "")
            )[:1000],
            "steps": clean_steps,
            "status": "planned",
        }

    except Exception as exc:
        return {
            "objective": str(prompt or "")[:1000],
            "reasoning_summary": (
                "Planning failed. "
                "A safe fallback was generated."
            ),
            "steps": [
                {
                    "id": 1,
                    "description": (
                        "Handle the request using "
                        "available agent capabilities."
                    ),
                    "tools": ["none"],
                    "expected_result": (
                        "Respond safely to the user."
                    ),
                }
            ],
            "status": "fallback",
            "planner_error": str(exc)[:1000],
        }


# ============================================================
# EXECUTION VERIFIER
# ============================================================

async def verify_execution(
    client,
    base_url,
    api_key,
    model,
    prompt,
    plan,
    tool_log,
    draft,
):
    if not isinstance(plan, dict):
        plan = {}

    if not isinstance(tool_log, list):
        tool_log = []

    successful_tools = [
        item
        for item in tool_log
        if (
            isinstance(item, dict)
            and isinstance(item.get("result"), dict)
            and item["result"].get("ok") is True
        )
    ]

    failed_tools = [
        item
        for item in tool_log
        if (
            isinstance(item, dict)
            and isinstance(item.get("result"), dict)
            and item["result"].get("ok") is False
        )
    ]

    if plan.get("status") == "fallback":
        return {
            "status": (
                "partial"
                if successful_tools
                else "blocked"
            ),
            "checks": [
                "The planning engine failed; fallback was used.",
                f"Successful tool calls: {len(successful_tools)}.",
                f"Failed tool calls: {len(failed_tools)}.",
            ],
            "completed_steps": [],
            "failed_steps": ["Planning"],
            "note": (
                "Execution cannot be declared fully completed "
                "because the planning engine did not produce "
                "a valid plan."
            ),
        }

    # If no tool was called, do not spend another API request.
    if not tool_log:
        return {
            "status": "unverified",
            "checks": [
                "No tool execution was recorded."
            ],
            "completed_steps": [],
            "failed_steps": [],
            "note": (
                "The response was generated, but no external "
                "operation was available to verify."
            ),
        }

    system = """
You are ARTHER's execution verification engine.

Verify only facts supported by tool results.

Rules:

1. Never invent successful operations.
2. Never claim a file was read or written without evidence.
3. A generated answer is not proof of an external action.
4. An empty tool result is not proof of success.
5. If only some steps succeeded, return partial.
6. If nothing was executed successfully, return blocked.
7. If no tool was required, return unverified.
8. Return completed only when evidence confirms completion.

Return JSON only:

{
    "status": "completed",
    "checks": [],
    "completed_steps": [],
    "failed_steps": [],
    "note": "Verification summary"
}

Allowed statuses:
- completed
- partial
- blocked
- unverified
"""

    evidence = {
        "user_request": str(prompt or "")[:1500],
        "execution_plan": plan,
        "tool_results": tool_log,
        "agent_answer": str(draft or "")[:1500],
    }

    evidence_text = json.dumps(
        evidence,
        ensure_ascii=False,
        default=str,
    )

    # Bound the verifier input to reduce TPM usage.
    evidence_text = evidence_text[:MAX_VERIFICATION_CHARS]

    try:
        result = await _ask(
            client,
            base_url,
            api_key,
            model,
            system,
            evidence_text,
        )

        status = result.get(
            "status",
            "unverified",
        )

        allowed_statuses = {
            "completed",
            "partial",
            "blocked",
            "unverified",
        }

        if status not in allowed_statuses:
            status = "unverified"

        checks = result.get("checks", [])
        completed = result.get("completed_steps", [])
        failed = result.get("failed_steps", [])

        if not isinstance(checks, list):
            checks = []

        if not isinstance(completed, list):
            completed = []

        if not isinstance(failed, list):
            failed = []

        if status == "completed" and not successful_tools:
            status = "unverified"

        return {
            "status": status,
            "checks": [
                str(item)[:300]
                for item in checks[:10]
            ],
            "completed_steps": completed[:10],
            "failed_steps": failed[:10],
            "note": str(
                result.get("note", "")
            )[:800],
        }

    except Exception as exc:
        return {
            "status": (
                "partial"
                if successful_tools
                else "unverified"
            ),
            "checks": [],
            "completed_steps": [],
            "failed_steps": [],
            "note": (
                "Automatic verification failed: "
                + str(exc)[:800]
            ),
        }


# ============================================================
# PLAN FORMATTER
# ============================================================

def format_plan(plan):
    if not isinstance(plan, dict):
        return "No execution plan available."

    lines = [
        f"Objective: {plan.get('objective', '')}",
        f"Status: {plan.get('status', 'unknown')}",
        "",
        "Steps:",
    ]

    steps = plan.get("steps", [])

    if not isinstance(steps, list):
        steps = []

    for step in steps:
        if not isinstance(step, dict):
            continue

        lines.append(
            f"{step.get('id', '?')}. "
            f"{step.get('description', '')}"
        )

    if plan.get("planner_error"):
        lines.extend([
            "",
            "Planner error: "
            + str(plan["planner_error"]),
        ])

    return "\n".join(lines)