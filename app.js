
"use strict";

const API = "https://arther-api.onrender.com";
const $ = id => document.getElementById(id);

const report = $("report");
const question = $("question");
const connection = $("connection");
const voiceStatus = $("voiceStatus");

let busy = false;

function show(text) {
  report.textContent = String(text || "");
  report.classList.remove("hidden");
}

function speak(text) {
  if (window.ArtherNative?.speak) {
    window.ArtherNative.speak(String(text));
    return;
  }

  if ("speechSynthesis" in window) {
    speechSynthesis.cancel();
    const msg = new SpeechSynthesisUtterance(String(text));
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

  rec.onend = () => {
    if (voiceStatus.textContent === "ARTHER يستمع إليك...")
      voiceStatus.textContent = "انتهى الاستماع";
  };

  rec.start();
}

window.ArtherVoiceResult = function(text) {
  question.value = text || "";
  voiceStatus.textContent = "تم استقبال الأمر";
  if (text) runArther();
};

async function runArther() {
  const prompt = question.value.trim();
  if (!prompt || busy) return;

  busy = true;
  $("run").disabled = true;
  connection.textContent = "ARTHER ينفذ...";

  try {
    const response = await fetch(API + "/api/agent", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
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
    show("التحكم يحتاج إلى تفعيل خدمة إمكانية الوصول.");
    return;
  }

  window.ArtherNative.performAction(action);
}

$("listen").addEventListener("click", listen);
$("run").addEventListener("click", runArther);

$("stopVoice").addEventListener("click", () => {
  if (window.ArtherNative?.stopSpeaking)
    window.ArtherNative.stopSpeaking();

  if ("speechSynthesis" in window)
    speechSynthesis.cancel();
});

$("home").addEventListener("click", () => nativeAction("HOME"));
$("back").addEventListener("click", () => nativeAction("BACK"));
$("scrollDown").addEventListener("click", () => nativeAction("SCROLL_DOWN"));

async function checkConnection() {
  try {
    const r = await fetch(API + "/health");
    if (!r.ok) throw new Error("offline");

    const h = await r.json();

    connection.textContent = h.ai_configured
      ? "ARTHER متصل"
      : "الخادم متصل والذكاء غير مضبوط";

  } catch {
    connection.textContent = "غير متصل";
  }
}

checkConnection();
