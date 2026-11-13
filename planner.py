
import json
import re


# ============================================================
# ARTHER PLANNER
# Creates a structured execution plan.
# ============================================================

def _json_object(raw):
    """Convert an AI response into a Python dictionary."""

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

    match = re.search(
        r"\{.*\}",
        content,
        re.DOTALL,
    )

    if match:
        try:
            result = json.loads(match.group(0))

            if isinstance(result, dict):
                return result

        except Exception:
            pass

    return {}


async def _ask(
    client,
    base_url,
    api_key,
    model,
    system,
    user,
):
    """Send a request to the configured AI provider."""

    response = await client.post(
        f"{base_url.rstrip('/')}/chat/completions",

        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },

        json={
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

            "temperature": 0.1,

            "response_format": {
                "type": "json_object",
            },
        },
    )

    response.raise_for_status()

    data = response.json()

    return _json_object(
        data["choices"][0]["message"].get("content", "")
    )


# ============================================================
# PLANNING ENGINE
# ============================================================

async def create_plan(
    client,
    base_url,
    api_key,
    model,
    prompt,
    language="auto",
):
    """
    Analyze the user's request and create an execution plan.
    """

    system = """
You are ARTHER, an advanced AI planning engine.

Your task is to understand the user's objective,
break it into practical steps, and identify the tools
needed for each step.

Think carefully before creating the plan.

RULES:

1. Understand the complete user objective.
2. Break complex tasks into clear steps.
3. Use between 1 and 7 steps.
4. Avoid unnecessary steps.
5. Identify the required tools.
6. Never invent tools.
7. Never claim that a step has already been executed.
8. Never request passwords or API secrets.
9. Never propose destructive actions without approval.
10. Never propose arbitrary shell execution.
11. Never assume access to devices or applications
    that are not connected.

AVAILABLE TOOLS:

- list_files
- read_file
- write_file
- save_memory
- search_memories
- none

Return JSON only.

Required format:

{
    "objective": "Main objective",
    "reasoning_summary": "Short explanation of the approach",
    "steps": [
        {
            "id": 1,
            "description": "First step",
            "tools": ["none"],
            "expected_result": "Expected outcome"
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
            json.dumps(
                request,
                ensure_ascii=False,
            ),
        )

        steps = result.get("steps")

        if not isinstance(steps, list):
            raise ValueError(
                "Planner did not return a valid steps list"
            )

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
                tool
                for tool in tools
                if isinstance(tool, str)
                and tool in allowed_tools
            ]

            clean_steps.append({

                "id": index,

                "description": str(
                    step.get(
                        "description",
                        "Execute the next task step",
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
                "Planner generated an empty plan"
            )

        return {

            "objective": str(
                result.get(
                    "objective",
                    prompt,
                )
            )[:1000],

            "reasoning_summary": str(
                result.get(
                    "reasoning_summary",
                    "",
                )
            )[:1000],

            "steps": clean_steps,

            "status": "planned",

        }

    except Exception as exc:

        return {

            "objective": prompt[:1000],

            "reasoning_summary": (
                "The planning service failed. "
                "Using a safe fallback plan."
            ),

            "steps": [
                {
                    "id": 1,

                    "description": (
                        "Process the request using "
                        "the available agent capabilities."
                    ),

                    "tools": ["none"],

                    "expected_result": (
                        "Respond to the user's request."
                    ),
                }
            ],

            "status": "fallback",

            "planner_error": str(exc)[:500],

        }


# ============================================================
# EXECUTION VERIFICATION ENGINE
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
    """
    Evaluate execution evidence and determine
    whether the task appears completed.
    """

    system = """
You are ARTHER's execution verification engine.

Your responsibility is to evaluate the evidence
returned by the tools and the agent.

IMPORTANT:

1. Verify only what the evidence supports.
2. Never invent successful tool results.
3. Distinguish between completed and incomplete steps.
4. Report errors honestly.
5. Do not confuse an AI-generated answer
   with a successfully executed external action.
6. If no tool was required, use unverified.
7. If a tool failed, consider partial or blocked.
8. Do not claim that files were modified
   unless tool results confirm it.

Return JSON only.

Required format:

{
    "status": "completed",
    "checks": [
        "The available evidence supports this result."
    ],
    "completed_steps": [],
    "failed_steps": [],
    "note": "Verification summary"
}

Allowed status values:

- completed
- partial
- blocked
- unverified
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

        checks = result.get(
            "checks",
            [],
        )

        completed_steps = result.get(
            "completed_steps",
            [],
        )

        failed_steps = result.get(
            "failed_steps",
            [],
        )

        if not isinstance(checks, list):
            checks = []

        if not isinstance(completed_steps, list):
            completed_steps = []

        if not isinstance(failed_steps, list):
            failed_steps = []

        return {

            "status": status,

            "checks": [
                str(item)[:500]
                for item in checks[:10]
            ],

            "completed_steps": completed_steps[:10],

            "failed_steps": failed_steps[:10],

            "note": str(
                result.get(
                    "note",
                    "",
                )
            )[:1000],

        }

    except Exception as exc:

        return {

            "status": "unverified",

            "checks": [],

            "completed_steps": [],

            "failed_steps": [],

            "note": (
                "Automatic verification failed: "
                + str(exc)[:500]
            ),

        }


# ============================================================
# PLAN SUMMARY
# ============================================================

def format_plan(plan):
    """Create a readable plan summary."""

    if not isinstance(plan, dict):
        return "No execution plan available."

    lines = []

    objective = plan.get(
        "objective",
        "",
    )

    lines.append(
        f"Objective: {objective}"
    )

    steps = plan.get(
        "steps",
        [],
    )

    for step in steps:

        lines.append(
            f"{step.get('id', '?')}. "
            f"{step.get('description', '')}"
        )

    return "\n".join(lines)
