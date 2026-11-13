
import os
import httpx
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

APP_NAME = "ARTHER"
VERSION = "3.0.0"

API_KEY = os.getenv("AI_API_KEY", "")
BASE_URL = os.getenv(
    "AI_BASE_URL",
    "https://api.groq.com/openai/v1"
).rstrip("/")
MODEL = os.getenv("AI_MODEL", "openai/gpt-oss-120b")

app = FastAPI(
    title="ARTHER AI",
    description="Autonomous Research and Technical Helper",
    version=VERSION
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


class AnalyzeRequest(BaseModel):
    prompt: str = Field(min_length=1, max_length=12000)
    language: str = "auto"


class ExperimentRequest(BaseModel):
    code: str = Field(min_length=1, max_length=5000)


SYSTEM_PROMPT = """
You are ARTHER, an advanced AI research and engineering assistant.

Your mission:
- Analyze problems critically.
- Develop plans and testable hypotheses.
- Explain technical subjects accurately.
- Help with programming, engineering and research.
- Identify errors and propose corrections.
- Never claim that you executed an action unless a tool
  actually performed it and returned a result.

Languages:
Arabic, French and English.
Reply in the user's language.

Behavior:
- Be direct, precise and practical.
- Break complex tasks into clear steps.
- State uncertainty honestly.
- Never invent test results, files, access or capabilities.
- Ask only for information essential to proceed.

You are connected to the ARTHER application backend.
"""


@app.get("/")
async def home():
    return {
        "name": APP_NAME,
        "version": VERSION,
        "status": "online"
    }


@app.get("/health")
async def health():
    return {
        "name": APP_NAME,
        "version": VERSION,
        "status": "online",
        "ai_configured": bool(API_KEY),
        "model": MODEL
    }


@app.post("/api/analyze")
async def analyze(request: AnalyzeRequest):
    if not API_KEY:
        raise HTTPException(
            status_code=503,
            detail="AI_API_KEY is not configured on the server."
        )

    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {
            "role": "user",
            "content": (
                f"Preferred language: {request.language}\n\n"
                f"Request:\n{request.prompt}"
            )
        }
    ]

    try:
        async with httpx.AsyncClient(timeout=90.0) as client:
            response = await client.post(
                f"{BASE_URL}/chat/completions",
                headers={
                    "Authorization": f"Bearer {API_KEY}",
                    "Content-Type": "application/json"
                },
                json={
                    "model": MODEL,
                    "messages": messages,
                    "temperature": 0.4
                }
            )

        if response.status_code >= 400:
            raise HTTPException(
                status_code=502,
                detail="AI provider error: " + response.text[:1000]
            )

        data = response.json()
        answer = data["choices"][0]["message"]["content"]

        return {
            "success": True,
            "answer": answer,
            "model": MODEL,
            "agent": APP_NAME
        }

    except HTTPException:
        raise
    except httpx.TimeoutException:
        raise HTTPException(
            status_code=504,
            detail="AI provider timed out."
        )
    except Exception as error:
        raise HTTPException(
            status_code=500,
            detail=f"Analysis failed: {str(error)}"
        )


@app.post("/api/experiment")
async def experiment(request: ExperimentRequest):
    if os.getenv("ALLOW_REMOTE_EXECUTION", "false").lower() != "true":
        raise HTTPException(
            status_code=403,
            detail=(
                "Remote code execution is disabled for security. "
                "Use a local sandbox for experiments."
            )
        )

    raise HTTPException(
        status_code=501,
        detail="A secure isolated execution sandbox is not installed yet."
    )
