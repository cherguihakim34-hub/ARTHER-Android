
import os
from uuid import uuid4
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from agent_core import run_agent
from memory_store import init_memory, close_memory

APP_NAME = "ARTHER"
VERSION = "3.2.0"
API_KEY = os.getenv("AI_API_KEY", "")


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.memory_ready = await init_memory()
    yield
    await close_memory()


app = FastAPI(
    title="ARTHER AI",
    description="Autonomous Research and Technical Helper",
    version=VERSION,
    lifespan=lifespan
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


class AgentRequest(BaseModel):
    prompt: str = Field(min_length=1, max_length=12000)
    language: str = "auto"
    session_id: str | None = Field(default=None, min_length=1, max_length=80)


class ExperimentRequest(BaseModel):
    code: str = Field(min_length=1, max_length=5000)


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
        "agent_core": True,
        "memory_enabled": app.state.memory_ready
    }


async def execute_agent(prompt: str, language: str, session_id: str):
    if not API_KEY:
        raise HTTPException(
            status_code=503,
            detail="AI_API_KEY is not configured on the server."
        )

    try:
        result = await run_agent(
            prompt=prompt,
            language=language,
            session_id=session_id
        )

        return {
            "agent": APP_NAME,
            "version": VERSION,
            **result,
            "memory_enabled": app.state.memory_ready
        }

    except httpx.TimeoutException:
        raise HTTPException(
            status_code=504,
            detail="The agent or AI provider timed out."
        )

    except RuntimeError as error:
        raise HTTPException(status_code=502, detail=str(error))

    except HTTPException:
        raise

    except Exception as error:
        raise HTTPException(
            status_code=500,
            detail=f"Agent execution failed: {str(error)[:500]}"
        )


@app.post("/api/analyze")
async def analyze(request: AnalyzeRequest):
    return await execute_agent(
        request.prompt,
        request.language,
        str(uuid4())
    )


@app.post("/api/agent")
async def agent(request: AgentRequest):
    session_id = request.session_id or str(uuid4())

    return await execute_agent(
        request.prompt,
        request.language,
        session_id
    )


@app.post("/api/experiment")
async def experiment(request: ExperimentRequest):
    raise HTTPException(
        status_code=501,
        detail=(
            "Secure isolated Python execution is not installed yet. "
            "Remote execution remains disabled."
        )
    )
