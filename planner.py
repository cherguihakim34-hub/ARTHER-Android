
import json
import re
from typing import Any

import httpx


def _json_object(raw: str) -> dict:
    """Extract a JSON object from the model response."""
    raw = (raw or "").strip()

    try:
        data = json.loads(raw)
        if isinstance(data, dict):
            return data
    except json.JSONDecodeError:
        pass

    match = re.search(r"\{[\s\S]*\}", raw)
    if match:
        try:
            data = json.loads(match.group(0))
            if isinstance(data, dict):
                return data
        except json.JSONDecodeError:
            pass

    raise ValueError("The model did not return valid JSON.")


async def _ask(
    prompt: str,
    system_prompt: str,
    api_key: str,
    base_url: str,
    model: str,
) -> dict:
    """Ask the AI model for JSON, retrying without JSON mode on HTTP 400."""

    url = base_url.rstrip("/") + "/chat/completions"

    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }

    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": prompt},
    ]

    async with httpx.AsyncClient(timeout=90) as client:

        payload = {
            "model": model,
            "messages": messages,
            "temperature": 0.2,
        }

        # First attempt: JSON mode
        first_payload = {
            **payload,
            "response_format": {"type": "json_object"},
        }

        response = await client.post(
            url,
            headers=headers,
            json=first_payload,
        )

        # Retry without response_format if the provider rejects it.
        if response.status_code == 400:
            first_error = response.text

            response = await client.post(
                url,
                headers=headers,
                json=payload,
            )

            if response.status_code >= 400:
                raise RuntimeError(
                    "AI request failed.\n"
                    f"JSON mode error: {first_error}\n"
                    f"Retry error: {response.text}"
                )

        elif response.status_code >= 400:
            raise RuntimeError(
                f"AI request failed ({response.status_code}): "
                f"{response.text}"
            )

        result = response.json()
        content = (
            result.get("choices", [{}])[0]
            .get("message", {})
            .get("content", "")
        )

        if isinstance(content, list):
            content = "".join(
                item.get("text", "")
                for item in content
                if isinstance(item, dict)
            )

        return _json_object(content)


async def create_plan(
    goal: str,
    api_key: str,
    base_url: str,
    model: str,
    language: str = "ar",
) -> dict:
    """Create an execution plan for the user's goal."""

    system_prompt = """
You are ARTHER's planning engine.

Analyze the user's goal and create a practical execution plan.

Return ONLY a valid JSON object with this structure:

{
  "status": "planned",
  "goal": "goal description",
  "summary": "short summary",
  "steps": [
    {
      "id": 1,
      "description": "step description",
      "tool": "tool name or null",
      "status": "pending"
    }
  ]
}

Rules:
- Break the goal into clear, ordered steps.
- Use only tools that are actually available.
- Never claim a step was executed during planning.
- Do not invent execution results.
- Keep the user's requested language.
"""

    prompt = (
        f"Language: {language}\n"
        f"User goal: {goal}\n"
        "Create the execution plan."
    )

    try:
        plan = await _ask(
            prompt,
            system_prompt,
            api_key,
            base_url,
            model,
        )

        if not isinstance(plan.get("steps"), list):
            raise ValueError("Planner returned no valid steps.")

        plan["status"] = "planned"
        plan["goal"] = plan.get("goal") or goal

        return plan

    except Exception as exc:
        return {
            "status": "fallback",
            "goal": goal,
            "summary": "A basic fallback plan was created.",
            "planner_error": str(exc),
            "steps": [
                {
                    "id": 1,
                    "description": "Analyze the requested task.",
                    "tool": None,
                    "status": "pending",
                },
                {
                    "id": 2,
                    "description": "Execute the available actions.",
                    "tool": None,
                    "status": "pending",
                },
                {
                    "id": 3,
                    "description": "Verify the results.",
                    "tool": None,
                    "status": "pending",
                },
            ],
        }


async def verify_execution(
    plan: dict,
    tool_results: list,
    api_key: str,
    base_url: str,
    model: str,
    language: str = "ar",
) -> dict:
    """Verify execution results without allowing false completion."""

    successful_results = [
        result
        for result in tool_results
        if isinstance(result, dict) and result.get("ok") is True
    ]

    # A fallback plan cannot be marked completed.
    if plan.get("status") == "fallback":
        return {
            "status": "partial" if successful_results else "blocked",
            "verified": False,
            "summary": (
                "Some actions succeeded, but planning failed."
                if successful_results
                else "Planning failed; execution is not verified."
            ),
            "reason": plan.get("planner_error", "Planner fallback"),
        }

    system_prompt = """
You are ARTHER's execution verification engine.

Evaluate the plan and actual tool results.

Return ONLY valid JSON:

{
  "status": "completed or partial or blocked",
  "verified": true,
  "summary": "verification summary",
  "completed_steps": [],
  "failed_steps": [],
  "remaining_steps": []
}

Rules:
- Never claim an action succeeded without evidence.
- An empty tool result is not proof of success.
- If only some steps succeeded, return partial.
- If nothing was executed successfully, return blocked.
- Return completed only when the available evidence confirms
  that the requested task was completed.
- Use the requested language.
"""

    prompt = json.dumps(
        {
            "language": language,
            "plan": plan,
            "tool_results": tool_results,
        },
        ensure_ascii=False,
        default=str,
    )

    try:
        verification = await _ask(
            prompt,
            system_prompt,
            api_key,
            base_url,
            model,
        )

        status = verification.get("status")

        if status not in ("completed", "partial", "blocked"):
            status = "partial" if successful_results else "blocked"

        # Deterministic protection against unsupported completion.
        if status == "completed" and not successful_results:
            status = "blocked"

        verification["status"] = status
        verification["verified"] = status == "completed"

        return verification

    except Exception as exc:
        return {
            "status": "partial" if successful_results else "blocked",
            "verified": False,
            "summary": "Automatic verification could not be completed.",
            "verification_error": str(exc),
        }


def format_plan(plan: dict) -> str:
    """Format a plan as readable text."""

    lines = [
        f"Goal: {plan.get('goal', '')}",
        f"Status: {plan.get('status', 'unknown')}",
        f"Summary: {plan.get('summary', '')}",
        "",
        "Steps:",
    ]

    for step in plan.get("steps", []):
        lines.append(
            f"{step.get('id', '?')}. "
            f"{step.get('description', '')} "
            f"[{step.get('status', 'pending')}]"
        )

    if plan.get("planner_error"):
        lines.extend(
            [
                "",
                f"Planner error: {plan['planner_error']}",
            ]
        )

    return "\n".join(lines)
