
const $ = (id) => document.getElementById(id);

const report = $("report");
const connection = $("connection");

let apiUrl = localStorage.getItem("arther_api_base") || "";

function getApiUrl() {
  if (!apiUrl) {
    const entered = prompt("أدخل رابط خادم ARTHER:");
    if (!entered) throw new Error("أدخل رابط الخادم أولاً");

    apiUrl = entered.trim().replace(/\/$/, "");
    localStorage.setItem("arther_api_base", apiUrl);
  }
  return apiUrl;
}

async function callApi(path, data) {
  const response = await fetch(getApiUrl() + path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data)
  });

  const result = await response.json().catch(() => ({}));

  if (!response.ok) {
    throw new Error(result.detail || "خطأ في الاتصال بالخادم");
  }

  return result;
}

function showError(error) {
  report.classList.remove("hidden");
  report.textContent = "خطأ: " + error.message;
  connection.textContent = "غير متصل";
}

async function checkConnection() {
  try {
    const response = await fetch(getApiUrl() + "/health");
    if (!response.ok) throw new Error();

    const health = await response.json();

    connection.textContent = health.ai_configured
      ? "متصل والذكاء الاصطناعي جاهز"
      : "متصل، لكن يلزم إعداد الذكاء الاصطناعي";
  } catch {
    connection.textContent = "غير متصل";
  }
}

const runButton = $("run");

runButton.addEventListener("click", async () => {
  const question = $("question").value.trim();

  if (!question) {
    showError(new Error("اكتب الفرضية أولاً"));
    return;
  }

  runButton.disabled = true;
  connection.textContent = "جارٍ التحليل...";

  try {
    const result = await callApi("/api/analyze", {
      message: question
    });

    report.classList.remove("hidden");
    report.textContent = result.reply || "لا توجد نتيجة";
    connection.textContent = "اكتمل التحليل";
  } catch (error) {
    showError(error);
  } finally {
    runButton.disabled = false;
  }
});

const executeButton = $("execute");

executeButton.addEventListener("click", async () => {
  const code = $("code").value.trim();

  if (!code) {
    showError(new Error("أدخل كود Python"));
    return;
  }

  executeButton.disabled = true;
  connection.textContent = "جارٍ التنفيذ...";

  try {
    const result = await callApi("/api/experiment", { code });

    report.classList.remove("hidden");
    report.textContent =
      (result.ok ? "نجح التنفيذ\n" : "فشل التنفيذ\n") +
      (result.stdout || "") +
      "\n" +
      (result.stderr || "");

    connection.textContent = "انتهى التنفيذ";
  } catch (error) {
    showError(error);
  } finally {
    executeButton.disabled = false;
  }
});

const clearButton = $("clear");

clearButton.addEventListener("click", () => {
  $("question").value = "";
  $("code").value = "";
  report.textContent = "";
  report.classList.add("hidden");
  connection.textContent = "جاهز";
});
