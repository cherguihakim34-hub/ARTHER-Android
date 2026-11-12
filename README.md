# ARTHER — Research & Experiment Agent (MVP 2)

ARTHER is a local-first research assistant prototype designed around a testable workflow:
1. Decompose a hypothesis.
2. Design an experiment or simulation.
3. Execute Python in a constrained subprocess.
4. Stress-test, inspect failures, and report evidence.

## Important limits
- The application does **not** claim certainty. It reports what was tested, the assumptions, and the limits of the evidence.
- Real language-model reasoning requires an OpenAI-compatible API endpoint and API key configured in `backend/.env`.
- Python execution is intended for trusted local use. Do not expose the backend to the public internet. For production or untrusted code, run the executor inside a properly configured Docker/container sandbox.
- This is a web application/PWA source project, not a signed Android APK or Windows EXE.

## Start (Windows / Linux / macOS)
```bash
cd backend
python -m venv .venv
# Windows: .venv\\Scripts\\activate
# Linux/macOS: source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
uvicorn main:app --host 127.0.0.1 --port 8765
```
Open `frontend/index.html` using a local static server, e.g.:
```bash
python -m http.server 8080 --directory ../frontend
```
Then open `http://127.0.0.1:8080`.

## Configure the AI
Edit `backend/.env`:
- `AI_BASE_URL` — OpenAI-compatible chat completions base URL (example: `https://api.openai.com/v1`)
- `AI_API_KEY` — your secret key (keep it only in backend `.env`)
- `AI_MODEL` — model name supported by your provider

The browser calls only the local backend. The API key is never sent to the browser.

## API
- `GET /health`
- `POST /api/experiment` — execute Python simulation code with timeout and output cap.
- `POST /api/analyze` — send a question and the required research workflow to the configured model.

## Research report contract
Every completed analysis should use four sections:
- الحكم النهائي
- ما تم اختباره
- الأدلة والنتائج
- الخطوة التالية المقترحة

If no code execution was performed, ARTHER must explicitly say so. It must not fabricate simulation results.
