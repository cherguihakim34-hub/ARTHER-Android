"use strict";

const $ = (id) => document.getElementById(id);

const report = $("report");
const connection = $("connection");

const DEFAULT_API_URL = "https://arther-api.onrender.com";

let apiUrl = (
  localStorage.getItem("arther_api_base") ||
  DEFAULT_API_URL
).replace(/\/+$/, "");

function getApiUrl() {
  return apiUrl;
}

function showReport(message) {
  if (!report) return;

  report.classList.remove("hidden");
  report.textContent = message;
}

function showError(error) {
  showReport("خطأ: " + (error?.message || String(error)));

  if (connection) {
    connection.textContent = "غير متصل";
  }
}

async function requestApi(path, data = null) {
  const options = {
    method: data === null ? "GET" : "POST",
    headers: {
      "Accept": "application/json"
    }
  };

  if (data !== null) {
    options.headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(data);
  }

  let response;

  try {
    response = await fetch(getApiUrl() + path, options);
  } catch (error) {
    throw new Error("تعذر الاتصال بالخادم. تحقق من الإنترنت.");
  }

  const result = await response.json().catch(() => ({}));

  if (!response.ok) {
    const detail =
      result.detail ||
      result.message ||
      result.error ||
      `HTTP ${response.status}`;

    throw new Error(detail);
  }

  return result;
}

async function checkConnection() {
  if (connection) {
    connection.textContent = "جارٍ الاتصال...";
  }

  try {
    const health = await requestApi("/health");

    if (connection) {
      connection.textContent = health.ai_configured
        ? "متصل - ARTHER جاهز"
        : "متصل - مفتاح الذكاء الاصطناعي غير مضبوط";
    }

    return health;
  } catch (error) {
    if (connection) {
      connection.textContent = "غير متصل بالخادم";
    }

    return null;
  }
}

function setBusy(button, busy, text) {
  if (!button) return;

  button.disabled = busy;

  if (busy) {
    button.dataset.originalText = button.textContent;
    button.textContent = text;
  } else if (button.dataset.originalText) {
    button.textContent = button.dataset.originalText;
  }
}

function formatAgentResult(result) {
  const sections = [];

  sections.push(result.answer || "لم يرجع الخادم إجابة.");

  if (result.plan) {
    sections.push("\n\n--- خطة التنفيذ ---");

    const steps = result.plan.steps || [];

    for (const step of steps) {
      sections.push(
        `${step.id}. ${step.description}`
      );
    }
  }

  if (result.verification) {
    sections.push("\n\n--- التحقق ---");
    sections.push(
      "الحالة: " + result.verification.status
    );

    if (result.verification.note) {
      sections.push(result.verification.note);
    }
  }

  if (Array.isArray(result.tool_calls)) {
    sections.push(
      "\n\nعدد عمليات الأدوات: " +
      result.tool_calls.length
    );
  }

  return sections.join("\n");
}

// ==========================================
// ANALYZE
// ==========================================

const runButton = $("run");

if (runButton) {
  runButton.addEventListener("click", async () => {
    const question = $("question")?.value.trim();

    if (!question) {
      showError(new Error("اكتب سؤالك أولاً."));
      return;
    }

    setBusy(runButton, true, "جارٍ التحليل...");

    if (connection) {
      connection.textContent = "ARTHER يعمل...";
    }

    try {
      const result = await requestApi("/api/analyze", {
        prompt: question,
        language: "ar"
      });

      showReport(
        result.answer ||
        "لم يرجع الخادم إجابة."
      );

      if (connection) {
        connection.textContent = "اكتمل التحليل";
      }
    } catch (error) {
      showError(error);
    } finally {
      setBusy(runButton, false);
    }
  });
}

// ==========================================
// AGENT
// ==========================================

const agentButton = $("agent");

if (agentButton) {
  agentButton.addEventListener("click", async () => {
    const question = $("question")?.value.trim();

    if (!question) {
      showError(new Error("اكتب طلبك أولاً."));
      return;
    }

    setBusy(agentButton, true, "جارٍ التفكير والتنفيذ...");

    if (connection) {
      connection.textContent = "ARTHER ينفذ المهمة...";
    }

    try {
      const result = await requestApi("/api/agent", {
        prompt: question,
        language: "ar",
        session_id: "arther-web-session"
      });

      showReport(formatAgentResult(result));

      if (connection) {
        connection.textContent = "انتهت المهمة";
      }
    } catch (error) {
      showError(error);
    } finally {
      setBusy(agentButton, false);
    }
  });
}

// ==========================================
// EXPERIMENT
// ==========================================

const executeButton = $("execute");

if (executeButton) {
  executeButton.addEventListener("click", () => {
    showError(
      new Error(
        "تنفيذ Python غير متاح حالياً. " +
        "الخادم يعطّل هذه الميزة لأسباب أمنية."
      )
    );
  });
}

// ==========================================
// CLEAR
// ==========================================

const clearButton = $("clear");

if (clearButton) {
  clearButton.addEventListener("click", () => {
    if ($("question")) {
      $("question").value = "";
    }

    if ($("code")) {
      $("code").value = "";
    }

    if (report) {
      report.textContent = "";
      report.classList.add("hidden");
    }

    if (connection) {
      connection.textContent = "جاهز";
    }
  });
}

// ==========================================
// START
// ==========================================

document.addEventListener("DOMContentLoaded", () => {
  checkConnection();
});