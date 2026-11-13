
"use strict";

const $ = (id) => document.getElementById(id);

const report = $("report");
const connection = $("connection");

const DEFAULT_API_URL = "https://arther-api.onrender.com";

let apiUrl = (
  localStorage.getItem("arther_api_base") ||
  DEFAULT_API_URL
).replace(/\/+$/, "");

function showReport(message) {
  if (!report) return;
  report.classList.remove("hidden");
  report.textContent = String(message);
}

function showError(error) {
  const message = error?.message || String(error);
  showReport("خطأ: " + message);

  if (connection) {
    connection.textContent = "حدث خطأ";
  }
}

async function requestApi(path, data) {
  let response;

  try {
    response = await fetch(apiUrl + path, {
      method: data ? "POST" : "GET",
      headers: {
        "Accept": "application/json",
        ...(data ? { "Content-Type": "application/json" } : {})
      },
      body: data ? JSON.stringify(data) : undefined
    });
  } catch {
    throw new Error("تعذر الاتصال بالخادم. تحقق من الإنترنت.");
  }

  const result = await response.json().catch(() => ({}));

  if (!response.ok) {
    throw new Error(
      result.detail ||
      result.message ||
      `HTTP ${response.status}`
    );
  }

  return result;
}

function setBusy(button, busy, label) {
  if (!button) return;

  button.disabled = busy;

  if (busy) {
    button.dataset.originalText = button.textContent;
    button.textContent = label;
  } else if (button.dataset.originalText) {
    button.textContent = button.dataset.originalText;
  }
}

function formatAgentResult(result) {
  const sections = [];

  sections.push(result.answer || "لم يرجع الخادم إجابة.");

  if (result.plan?.steps?.length) {
    sections.push("\n\n--- خطة التنفيذ ---");

    for (const step of result.plan.steps) {
      sections.push(
        `${step.id || ""}. ${step.description || ""}`
      );
    }
  }

  if (result.verification) {
    sections.push("\n\n--- التحقق ---");
    sections.push("الحالة: " + result.verification.status);

    if (result.verification.note) {
      sections.push(result.verification.note);
    }

    if (result.verification.failed_steps?.length) {
      sections.push(
        "العمليات الفاشلة: " +
        result.verification.failed_steps.join(", ")
      );
    }
  }

  if (Array.isArray(result.tool_calls)) {
    sections.push(
      "\nعدد عمليات الأدوات: " + result.tool_calls.length
    );
  }

  return sections.join("\n");
}

async function runArther() {
  const question = $("question")?.value.trim();
  const runButton = $("run");

  if (!question) {
    showError(new Error("اكتب طلبك أولاً."));
    return;
  }

  setBusy(runButton, true, "ARTHER يفكر وينفذ...");

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
      connection.textContent = result.success
        ? "اكتملت المهمة"
        : "انتهى التنفيذ مع وجود مشكلة";
    }
  } catch (error) {
    showError(error);
  } finally {
    setBusy(runButton, false);
  }
}

const runButton = $("run");

if (runButton) {
  runButton.addEventListener("click", runArther);
}

const executeButton = $("execute");

if (executeButton) {
  executeButton.addEventListener("click", () => {
    showError(
      new Error(
        "تنفيذ Python غير متاح حالياً لأسباب أمنية."
      )
    );
  });
}

const clearButton = $("clear");

if (clearButton) {
  clearButton.addEventListener("click", () => {
    if ($("question")) $("question").value = "";
    if ($("code")) $("code").value = "";

    if (report) {
      report.textContent = "";
      report.classList.add("hidden");
    }

    if (connection) {
      connection.textContent = "جاهز";
    }
  });
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
  } catch {
    if (connection) {
      connection.textContent = "غير متصل بالخادم";
    }
  }
}

checkConnection();
