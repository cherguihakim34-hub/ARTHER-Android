
"use strict";

const API = "https://arther-api.onrender.com";
const $ = id => document.getElementById(id);

const report = $("report");
const question = $("question");
const connection = $("connection");
const voiceStatus = $("voiceStatus");

let busy = false;

function show(message) {
  report.textContent = String(message || "");
  report.classList.remove("hidden");
}

function speak(message) {
  if (window.ArtherNative?.speak) {
    window.ArtherNative.speak(String(message));
    return;
  }

  if ("speechSynthesis" in window) {
    speechSynthesis.cancel();
    const msg = new SpeechSynthesisUtterance(String(message));
    msg.lang = "ar-SA";
    msg.rate = 0.9;
    speechSynthesis.speak(msg);
  }
}

function listen() {
  if (window.ArtherNative?.startListening) {
    voiceStatus.textContent = "ARTHER يستمع إليك...";
    window.ArtherNative.startListening();
    return;
  }

  const Recognition =
    window.SpeechRecognition || window.webkitSpeechRecognition;

  if (!Recognition) {
    show("التعرف على الصوت يحتاج إلى نسخة Android الأصلية.");
    return;
  }

  const rec = new Recognition();
  rec.lang = "ar-DZ";
  rec.interimResults = false;
  rec.maxAlternatives = 1;

  rec.onresult = event => {
    question.value = event.results[0][0].transcript;
    voiceStatus.textContent = "تم استقبال الأمر";
    runArther();
  };

  rec.onerror = () => {
    voiceStatus.textContent = "تعذر التعرف على الصوت";
  };

  rec.start();
}

window.ArtherVoiceResult = function(text) {
  question.value = text || "";
  voiceStatus.textContent = "تم استقبال الأمر";
  if (text) runArther();
};

window.ArtherFlashResult = function(success, message) {
  show(message);
  speak(message);
};

function normalizeCommand(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/[أإآ]/g, "ا")
    .replace(/[ًٌٍَُِّْـ]/g, "")
    .replace(/[إ]/g, "ا")
    .trim();
}

function detectFlashCommand(prompt) {
  const text = normalizeCommand(prompt);

  const target =
    "(?:الفلاش|فلاش|المصباح|الكشاف|ضوء الهاتف|ضو الهاتف|flash|torch|torche)";

  const on = new RegExp(
    "(?:شغل|تشغيل|شغّل|افتح|فتح|ولع|اضئ|انر|شعل|اشعل|قم بتشغيل|turn on|enable|allume|active).*" +
    target +
    "|" + target +
    ".*(?:شغل|تشغيل|افتح|ولع|اضئ|انر|شعل|on|allume)",
    "i"
  );

  const off = new RegExp(
    "(?:اطف|اطفاء|إطفاء|اغلق|سكر|طفي|طفّي|قم بإطفاء|turn off|disable|eteins|desactive).*" +
    target +
    "|" + target +
    ".*(?:اطف|اغلق|سكر|طفي|off|eteins)",
    "i"
  );

  if (on.test(text)) return "ON";
  if (off.test(text)) return "OFF";

  return null;
}

async function runArther() {
  const prompt = question.value.trim();

  if (!prompt || busy) return;

  // تنفيذ الفلاش مباشرة على الهاتف
  const flashCommand = detectFlashCommand(prompt);

  if (flashCommand) {
    if (!window.ArtherNative?.setFlash) {
      show("التحكم بالفلاش غير متاح في هذه النسخة. ثبّت آخر إصدار من ARTHER.");
      speak("التحكم بالفلاش غير متاح في هذه النسخة.");
      return;
    }

    show("جارٍ تنفيذ أمر الفلاش...");
    window.ArtherNative.setFlash(flashCommand);
    return;
  }

  // إرسال بقية الطلبات إلى الذكاء الاصطناعي
  busy = true;
  $("run").disabled = true;
  connection.textContent = "ARTHER يعالج طلبك...";

  try {
    const response = await fetch(API + "/api/agent", {
      method: "POST",
      headers: {
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        prompt,
        language: "auto",
        session_id: "arther-android"
      })
    });

    const data = await response.json();

    if (!response.ok) {
      throw new Error(data.detail || "فشل الاتصال بالخادم");
    }

    const answer = data.answer || "لم يصل رد من الخادم.";

    show(answer);
    connection.textContent = "متصل";
    speak(answer);

  } catch (error) {
    show("خطأ: " + error.message);
    connection.textContent = "خطأ في الاتصال";

  } finally {
    busy = false;
    $("run").disabled = false;
  }
}

function nativeAction(action) {
  if (!window.ArtherNative?.performAction) {
    show("التحكم يحتاج إلى نسخة Android الأصلية.");
    return;
  }

  window.ArtherNative.performAction(action);
}

$("listen").addEventListener("click", listen);
$("run").addEventListener("click", runArther);

$("stopVoice").addEventListener("click", () => {
  if (window.ArtherNative?.stopSpeaking) {
    window.ArtherNative.stopSpeaking();
  }

  if ("speechSynthesis" in window) {
    speechSynthesis.cancel();
  }
});

$("home").addEventListener("click", () => nativeAction("HOME"));
$("back").addEventListener("click", () => nativeAction("BACK"));

$("scrollDown").addEventListener("click", () =>
  nativeAction("SCROLL_DOWN")
);

async function checkConnection() {
  try {
    const response = await fetch(API + "/health");

    if (!response.ok) throw new Error("offline");

    const health = await response.json();

    connection.textContent = health.ai_configured
      ? "ARTHER متصل"
      : "الخادم متصل والذكاء غير مضبوط";

  } catch {
    connection.textContent = "غير متصل";
  }
}

checkConnection();
