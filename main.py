from __future__ import annotations
import os, subprocess, sys, tempfile, textwrap, json
from pathlib import Path
from typing import Optional
import httpx
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

load_dotenv()
app = FastAPI(title="ARTHER Research Agent API", version="2.0.0")
# Local development only. Keep this service bound to 127.0.0.1.
app.add_middleware(
    CORSMiddleware, allow_origins=["http://127.0.0.1:8080", "http://localhost:8080"],
    allow_credentials=False, allow_methods=["GET", "POST"], allow_headers=["Content-Type"]
)

SYSTEM_PROMPT = """أنت ARTHER: وكيل ذكاء اصطناعي بحثي ومهندس تجريب ومحاكاة.
لست مجرد روبوت دردشة. تعامل مع كل فكرة قابلة للاختبار وفق دورة:
1) فكك الفرضية وحدد المتغيرات والافتراضات والشروط الحدية.
2) صمم تجربة قابلة للقياس، واذكر معيار النجاح والفشل.
3) نفّذ Python فعلياً عبر أداة التنفيذ المتاحة عندما تكون المحاكاة مفيدة.
4) اختبر الحالات الطرفية وحاول دحض الفرضية، ثم صحح النموذج وأعد الاختبار عند وجود خطأ.
قواعد النزاهة:
- لا تقل إنك نفذت كوداً أو آلاف الحالات إلا إذا وصلتك نتيجة تنفيذ فعلية.
- لا تدّعِ اليقين المطلق؛ ميّز بين ما أثبته الاختبار وما لم يختبره.
- لا تختلق أرقاماً أو مصادر أو نتائج.
- إذا لم تتوفر أداة التنفيذ أو فشل التنفيذ، صرّح بذلك بوضوح.
- اشرح الافتراضات والقيود وأي مخاطر أو ثغرات.
أخرج النتيجة دائماً بأربعة عناوين:
## الحكم النهائي
## ما تم اختباره
## الأدلة والنتائج
## الخطوة التالية المقترحة
كن مختصراً وواضحاً ومناسباً لشاشة الهاتف، وأجب بلغة المستخدم."""

class ExperimentRequest(BaseModel):
    code: str = Field(min_length=1, max_length=12000)
    timeout: int = Field(default=8, ge=1, le=15)

class AnalyzeRequest(BaseModel):
    message: str = Field(min_length=1, max_length=12000)
    language: str = "auto"
    previous_report: Optional[str] = None
    execution_result: Optional[dict] = None

@app.get("/health")
def health():
    return {"ok": True, "service": "ARTHER", "version": "2.0.0",
            "ai_configured": bool(os.getenv("AI_BASE_URL") and os.getenv("AI_API_KEY") and os.getenv("AI_MODEL"))}

@app.post("/api/experiment")
def run_experiment(req: ExperimentRequest):
    """Run trusted local Python code in a separate process with a timeout.
    This is NOT a hardened security sandbox. Do not expose it to untrusted users.
    """
    timeout = min(req.timeout, int(os.getenv("EXECUTION_TIMEOUT_SECONDS", "8")))
    with tempfile.TemporaryDirectory(prefix="arther_") as td:
        script = Path(td) / "experiment.py"
        script.write_text(req.code, encoding="utf-8")
        try:
            proc = subprocess.run(
                [sys.executable, "-I", str(script)],
                cwd=td, capture_output=True, text=True, timeout=timeout,
                env={"PATH": os.environ.get("PATH", ""), "PYTHONIOENCODING": "utf-8"},
            )
            return {"ok": proc.returncode == 0, "returncode": proc.returncode,
                    "stdout": proc.stdout[-12000:], "stderr": proc.stderr[-6000:],
                    "timed_out": False}
        except subprocess.TimeoutExpired as exc:
            return {"ok": False, "returncode": None,
                    "stdout": (exc.stdout or b"").decode(errors="replace") if isinstance(exc.stdout, bytes) else (exc.stdout or ""),
                    "stderr": "انتهت مهلة التنفيذ.", "timed_out": True}

@app.post("/api/analyze")
async def analyze(req: AnalyzeRequest):
    base = os.getenv("AI_BASE_URL", "").rstrip("/")
    key, model = os.getenv("AI_API_KEY", ""), os.getenv("AI_MODEL", "")
    if not (base and key and model):
        raise HTTPException(503, "لم يتم إعداد مزود الذكاء الاصطناعي. أضف AI_BASE_URL وAI_API_KEY وAI_MODEL في backend/.env.")
    user_content = req.message
    if req.execution_result is not None:
        user_content += "\n\nنتيجة تنفيذ Python الفعلية (بيانات موثوقة من الأداة):\n" + json.dumps(req.execution_result, ensure_ascii=False)
    if req.previous_report:
        user_content += "\n\nالتقرير السابق المطلوب مراجعته:\n" + req.previous_report
    payload = {"model": model, "messages": [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": user_content}
    ], "temperature": 0.2}
    try:
        async with httpx.AsyncClient(timeout=90) as client:
            response = await client.post(base + "/chat/completions",
                headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"}, json=payload)
            response.raise_for_status()
            data = response.json()
            answer = data["choices"][0]["message"]["content"]
            return {"ok": True, "reply": answer}
    except httpx.HTTPStatusError as exc:
        raise HTTPException(502, f"مزود الذكاء الاصطناعي أعاد خطأ: {exc.response.status_code}")
    except Exception as exc:
        raise HTTPException(502, f"تعذر الاتصال بمزود الذكاء الاصطناعي: {type(exc).__name__}")

