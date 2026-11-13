
import json
import re
import httpx


def _json_object(raw):
    if isinstance(raw, dict):
        return raw

    content = str(raw or "").strip()
    content = re.sub(
        r"^```(?:json)?\s*|\s*```$",
        "",
        content,
        flags=re.IGNORECASE,
    )

    try:
        result = json.loads(content)
        if isinstance(result, dict):
            return result
    except Exception:
        pass

    match = re.search(r"\{.*\}", content, re.DOTALL)
    if match:
        try:
            result = json.loads(match.group(0))
            if isinstance(result, dict):
                return result
        except Exception:
            pass

    raise ValueError("The AI response did not contain valid JSON.")


async def _ask(client, base_url, api_key, model, system, user):
    url = f"{base_url.rstrip('/')}/chat/completions"

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }

    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        "temperature": 0.1,
    }

    # المحاولة الأولى: طلب JSON mode
    first_payload = {
        **payload,
        "response_format": {"type": "json_object"},
    }

    response = await client.post(
        url,
        headers=headers,
        json=first_payload,
    )

    # إذا رفض المزود JSON mode، نعيد الطلب بدونه
    if response.status_code == 400:
        first_error = response.text[:1500]

        response = await client.post(
            url,
            headers=headers,
            json=payload,
        )

        if response.status_code >= 400:
            raise RuntimeError(
                "Groq rejected both planner requests. "
                f"JSON-mode error: {first_error} | "
                f"Retry error: {response.text[:1500]}"
            )

    elif response.status_code >= 400:
        raise RuntimeError(
            f"Groq HTTP {response.status_code}: "
            f"{response.text[:1500]}"
        )

    data = response.json()
    choices = data.get("choices") or []

    if not choices:
        raise ValueError("Groq returned no choices.")

    message = choices[0].get("message") or {}
    content = message.get("content")

    if not content:
        raise ValueError("Groq returned an empty planner response.")

    return _json_object(content)


async def create_plan(
    client,
    base_url,
    api_key,
    model,
    prompt,
    language="auto",
):
    system = """
You are ARTHER, an advanced AI planning engine.

Analyze the user's complete objective and create a practical plan.
Use 1 to 7 steps.
Never claim that a step has already been executed.
Never invent tools.
Never propose destructive actions without approval.
Never propose arbitrary shell execution.

Available tools:
- list_files
- read_file
- write_file
- save_memory
- search_memories
- none

Return a valid JSON object only, with this structure:
{
  "objective": "Main objective",
  "reasoning_summary": "Short approach",
  "steps": [
    {
      "id": 1,
      "description": "Step description",
      "tools": ["list_files"],
      "expected_result": "Expected result"
    }
  ]
}
"""

    request = {
        "language": language,
        "task": prompt,
    }

    try:
        result = await _ask(
            client,
            base_url,
            api_key,
            model,
            system,
            json.dumps(request, ensure_ascii=False),
        )

        steps = result.get("steps")
        if not isinstance(steps, list) or not steps:
            raise ValueError("Planner returned no valid steps.")

        allowed_tools = {
            "list_files",
            "read_file",
            "write_file",
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
                tool for tool in tools
                if isinstance(tool, str) and tool in allowed_tools
            ]

            clean_steps.append({
                "id": index,
                "description": str(
                    step.get("description", "Execute task step")
                )[:500],
                "tools": clean_tools,
                "expected_result": str(
                    step.get("expected_result", "")
                )[:500],
            })

        if not clean_steps:
            raise ValueError("Planner produced no usable steps.")

        return {
            "objective": str(result.get("objective", prompt))[:1000],
            "reasoning_summary": str(
                result.get("reasoning_summary", "")
            )[:1000],
            "steps": clean_steps,
            "status": "planned",
        }

    except Exception as exc:
        return {
            "objective": prompt[:1000],
            "reasoning_summary": (
                "Planning failed. A safe fallback was generated."
            ),
            "steps": [{
                "id": 1,
                "description": (
                    "Handle the request using available agent capabilities."
                ),
                "tools": ["none"],
                "expected_result": "Respond safely to the user.",
            }],
            "status": "fallback",
            "planner_error": str(exc)[:2000],
        }


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
    # لا نسمح لخطة احتياطية بأن تحصل على حالة completed
    if plan.get("status") == "fallback":
        successful_tools = [
            item for item in tool_log
            if isinstance(item, dict)
            and isinstance(item.get("result"), dict)
            and item["result"].get("ok") is True
        ]

        failed_tools = [
            item for item in tool_log
            if isinstance(item, dict)
            and isinstance(item.get("result"), dict)
            and item["result"].get("ok") is False
        ]

        status = "partial" if successful_tools else "blocked"

        return {
            "status": status,
            "checks": [
                "The planning engine failed; fallback was used.",
                f"Successful tool calls: {len(successful_tools)}.",
                f"Failed tool calls: {len(failed_tools)}.",
            ],
            "completed_steps": [],
            "failed_steps": ["Planning"],
            "note": (
                "Execution cannot be declared fully completed "
                "because the planning engine did not produce a valid plan."
            ),
        }

    system = """
You are ARTHER's execution verification engine.

Verify only facts supported by tool results.
Never invent successful operations.
A generated answer is not proof of an external action.
If no tool was required, use unverified.
If execution is incomplete, use partial or blocked.

Return JSON only:
{
  "status": "completed",
  "checks": [],
  "completed_steps": [],
  "failed_steps": [],
  "note": "Verification summary"
}

Allowed statuses: completed, partial, blocked, unverified.
"""

    evidence = {
        "user_request": prompt,
        "execution_plan": plan,
        "tool_results": tool_log,
        "agent_answer": draft,
    }

    try:
        result = await _ask(
            client,
            base_url,
            api_key,
            model,
            system,
            json.dumps(
                evidence,
                ensure_ascii=False,
                default=str,
            )[:12000],
        )

        status = result.get("status", "unverified")
        allowed = {"completed", "partial", "blocked", "unverified"}

        if status not in allowed:
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

        return {
            "status": status,
            "checks": [str(x)[:500] for x in checks[:10]],
            "completed_steps": completed[:10],
            "failed_steps": failed[:10],
            "note": str(result.get("note", ""))[:1000],
        }

    except Exception as exc:
        return {
            "status": "unverified",
            "checks": [],
            "completed_steps": [],
            "failed_steps": [],
            "note": f"Automatic verification failed: {str(exc)[:1000]}",
        }


def format_plan(plan):
    if not isinstance(plan, dict):
        return "No execution plan available."

    lines = [f"Objective: {plan.get('objective', '')}"]

    for step in plan.get("steps", []):
        lines.append(
            f"{step.get('id', '?')}. {step.get('description', '')}"
        )

    return "\n".join(lines)
