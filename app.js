
"use strict";

const API = "https://arther-api.onrender.com";
const $ = id => document.getElementById(id);

const report = $("report");
const question = $("question");
const connection = $("connection");
const voiceStatus = $("voiceStatus");

let busy = false;
let lastFlashOn = false;

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
    .replace(/[؟?!.,،]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function nativeAction(action) {
  if (!window.ArtherNative?.performAction) {
    show("التحكم يحتاج إلى نسخة Android الأصلية وتفعيل خدمة إمكانية الوصول.");
    return false;
  }

  window.ArtherNative.performAction(action);
  return true;
}

function detectFlashCommand(prompt) {
  const text = normalizeCommand(prompt);

  const target =
    "(?:الفلاش|فلاش|المصباح|الكشاف|ضوء الهاتف|ضو الهاتف|flash|torch|torche)";

  const on = new RegExp(
    "(?:شغل|تشغيل|افتح|فتح|ولع|اضئ|انر|شعل|اشعل|قم بتشغيل|turn on|enable|allume|active).*" +
    target +
    "|" + target +
    ".*(?:شغل|تشغيل|افتح|ولع|اضئ|انر|شعل|on|allume)",
    "i"
  );

  const off = new RegExp(
    "(?:اطف|اطفاء|اغلق|سكر|طفي|قم باطفاء|turn off|disable|eteins|desactive).*" +
    target +
    "|" + target +
    ".*(?:اطف|اغلق|سكر|طفي|off|eteins)",
    "i"
  );

  if (on.test(text)) return "ON";
  if (off.test(text)) return "OFF";

  if (
    lastFlashOn &&
    /^(اطفئه|اطفيه|طفيه|اغلقه|سكره|اطف|طفي|طف|off)$/.test(text)
  ) {
    return "OFF";
  }

  return null;
}

function executeLocalCommand(prompt) {
  const text = normalizeCommand(prompt);

  // الفلاش
  const flash = detectFlashCommand(prompt);

  if (flash) {
    if (!window.ArtherNative?.setFlash) {
      show("التحكم بالفلاش غير متاح في هذه النسخة.");
      speak("التحكم بالفلاش غير متاح.");
      return true;
    }

    lastFlashOn = flash === "ON";
    show(flash === "ON" ? "تشغيل الفلاش..." : "إطفاء الفلاش...");
    window.ArtherNative.setFlash(flash);
    return true;
  }

  // التنقل في الهاتف
  const navigation = [
    [/^(الرئيسية|اذهب للرئيسية|الشاشة الرئيسية|home)$/, "HOME"],
    [/^(رجوع|ارجع|الى الخلف|back)$/, "BACK"],
    [/^(التطبيقات المفتوحة|التطبيقات الاخيرة|اخر التطبيقات|recent apps)$/, "RECENTS"],
    [/^(افتح الاشعارات|اظهر الاشعارات|شريط الاشعارات)$/, "NOTIFICATIONS"],
    [/^(الاعدادات السريعة|لوحة الاختصارات|quick settings)$/, "QUICK_SETTINGS"],
    [/^(اقفل الهاتف|اقفل الشاشة|قفل الشاشة|lock screen)$/, "LOCK_SCREEN"],
    [/^(التقط صورة للشاشة|لقطة شاشة|سكرين شوت|screenshot)$/, "TAKE_SCREENSHOT"],
    [/^(انزل|مرر للاسفل|انزل الصفحة|scroll down)$/, "SCROLL_DOWN"],
    [/^(اصعد|مرر للاعلى|اصعد الصفحة|scroll up)$/, "SCROLL_UP"]
  ];

  for (const [pattern, action] of navigation) {
    if (pattern.test(text)) {
      nativeAction(action);
      return true;
    }
  }

  // فتح تطبيق
  const openMatch = text.match(
    /^(?:افتح|شغل|ابدأ|launch|open)\s+(.+)$/
  );

  if (openMatch) {
    const appName = openMatch[1].trim();

    if (window.ArtherNative?.openApp) {
      window.ArtherNative.openApp(appName);
      show("جارٍ فتح " + appName);
    } else {
      show("فتح التطبيقات غير متاح في هذه النسخة.");
    }

    return true;
  }

  // الضغط على عنصر ظاهر بالاسم
  const clickMatch = text.match(
    /^(?:اضغط على|انقر على|اختار|اختر|click)\s+(.+)$/
  );

  if (clickMatch) {
    nativeAction("CLICK_TEXT:" + clickMatch[1].trim());
    return true;
  }

  // الضغط المطول
  const longClickMatch = text.match(
    /^(?:اضغط مطولا على|ضغط مطول على|long press)\s+(.+)$/
  );

  if (longClickMatch) {
    nativeAction("LONG_CLICK_TEXT:" + longClickMatch[1].trim());
    return true;
  }

  // الكتابة في الحقل المحدد
  const typeMatch = text.match(
    /^(?:اكتب|اكتب النص|اكتب له|type)\s+(.+)$/
  );

  if (typeMatch) {
    const value = typeMatch[1].trim();

    if (window.ArtherNative?.typeText) {
      window.ArtherNative.typeText(value);
      show("تم إرسال النص إلى حقل الكتابة المحدد.");
    } else {
      show("خدمة الكتابة غير متاحة. فعّل خدمة إمكانية الوصول.");
    }

    return true;
  }

  return false;
}

async function runArther() {
  const prompt = question.value.trim();

  if (!prompt || busy) return;

  if (executeLocalCommand(prompt)) return;

  busy = true;

  if ($("run")) $("run").disabled = true;
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
    if ($("run")) $("run").disabled = false;
  }
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
