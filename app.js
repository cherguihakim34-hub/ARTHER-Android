
const $ = (id) => document.getElementById(id);

const output = $("output");
const status = $("status");

const savedUrl = localStorage.getItem("arther_api_base") || "";
const baseInput = $("apiUrl");

if (baseInput) baseInput.value = savedUrl;

function apiBase() {
  const value = (baseInput?.value || "").trim().replace(/\/$/, "");

  if (!value) {
    throw new Error("أدخل رابط خادم ARTHER أولاً");
  }

  localStorage.setItem("arther_api_base", value);
  return value;
}

async function request(path, data) {
  const response = await fetch(apiBase() + path, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(data)
  });

  const result = await response.json().catch(() => ({}));

  if (!response.ok) {
    throw new Error(result.detail || "حدث خطأ في الخادم");
  }

  return result;
}

function showError(error) {
  if (output) output.textContent = "خطأ: " + error.message;
  if (status) status.textContent = "غير متصل";
}

// تحليل الأسئلة والفرضيات
const analyzeButton = $("analyzeBtn");

if (analyzeButton) {
  analyzeButton.addEventListener("click", async () => {
    const question = $("question")?.value?.trim();

    if (!question) {
      output.textContent = "اكتب سؤالك أولاً.";
      return;
    }

    analyzeButton.disabled = true;
    status.textContent = "جارٍ التحليل...";

    try {
      const result = await request("/api/analyze", {
        question
      });

      output.textContent =
        result.answer ||
        result.analysis ||
        JSON.stringify(result, null, 2);

      status.textContent = "اكتمل التحليل";
    } catch (error) {
      showError(error);
    } finally {
      analyzeButton.disabled = false;
    }
  });
}

// تنفيذ تجارب Python
const runButton = $("runBtn");

if (runButton) {
  runButton.addEventListener("click", async () => {
    const code = $("code")?.value || "";

    if (!code.trim()) {
      output.textContent = "أدخل كود Python أولاً.";
      return;
    }

    runButton.disabled = true;
    status.textContent = "جارٍ التنفيذ...";

    try {
      const result = await request("/api/experiment", {
        code
      });

      output.textContent =
        result.output ||
        result.stdout ||
        JSON.stringify(result, null, 2);

      status.textContent = "انتهى التنفيذ";
    } catch (error) {
      showError(error);
    } finally {
      runButton.disabled = false;
    }
  });
}

// مسح النتائج
const clearButton = $("clearBtn");

if (clearButton) {
  clearButton.addEventListener("click", () => {
    output.textContent = "";
    status.textContent = "جاهز";
  });
}

// اختبار الاتصال
const connectButton = $("connectBtn");

if (connectButton) {
  connectButton.addEventListener("click", async () => {
    try {
      const response = await fetch(apiBase() + "/health");

      if (!response.ok) {
        throw new Error("الخادم لا يستجيب");
      }

      status.textContent = "متصل";
    } catch (error) {
      showError(error);
    }
  });
}
