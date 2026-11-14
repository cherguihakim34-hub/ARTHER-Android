"use strict";
// Build sync: speech recognizer provider recovery update.

const API = "https://arther-api.onrender.com";
const $ = id => document.getElementById(id);

const report = $("report");
const question = $("question");
const connection = $("connection");
const voiceStatus = $("voiceStatus");

let busy = false;
let lastFlashOn = false;
let wakeWordMode = true;
let wakeArmed = false;
let wakeRestartTimer = null;
let wakeArmTimer = null;
let wakeError11Count = 0;

function scheduleWakeListening(delay = 500) {
  if (!wakeWordMode || document.hidden) return;
  clearTimeout(wakeRestartTimer);
  wakeRestartTimer = setTimeout(() => {
    if (!wakeWordMode || document.hidden || busy) return;

    // Capacitor may inject the native bridge after this script starts.
    // Keep retrying briefly instead of silently losing the first listen request.
    if (window.ArtherNative && typeof window.ArtherNative.startListening === "function") {
      window.ArtherNative.startListening();
    } else {
      voiceStatus.textContent = "جارٍ تهيئة الميكروفون...";
      scheduleWakeListening(600);
    }
  }, delay);
}

function startWakeWordMode() {
  wakeWordMode = true;
  wakeArmed = false;
  voiceStatus.textContent = "بانتظار كلمة آرثر...";
  scheduleWakeListening(700);
}

function show(message) {
  report.textContent = String(message || "");
  report.classList.remove("hidden");
}

function speak(message) {
  const value = String(message || "");
  const delay = Math.min(20000, Math.max(1800, value.split(/\s+/).length * 380));
  if (window.ArtherNative?.speak) {
    window.ArtherNative.speak(value);
    scheduleWakeListening(delay);
    return;
  }
  if ("speechSynthesis" in window) {
    speechSynthesis.cancel();
    const msg = new SpeechSynthesisUtterance(value);
    msg.lang = preferredSpeechLanguage();
    msg.rate = 0.9;
    msg.onend = () => scheduleWakeListening(400);
    speechSynthesis.speak(msg);
  } else {
    scheduleWakeListening(1000);
  }
}

function preferredSpeechLanguage() {
  const language = String(navigator.language || "ar-DZ").toLowerCase();
  if (language.startsWith("fr")) return "fr-FR";
  if (language.startsWith("en")) return "en-US";
  return "ar-DZ";
}

function listen() {
  if (window.ArtherNative?.startListening) {
    voiceStatus.textContent = "ARTHER يستمع إليك...";
    window.ArtherNative.startListening();
    return;
  }
  const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!Recognition) {
    show("التعرف على الصوت يحتاج إلى نسخة Android الأصلية.");
    return;
  }
  const rec = new Recognition();
  rec.lang = preferredSpeechLanguage();
  rec.interimResults = false;
  rec.maxAlternatives = 1;
  rec.onresult = event => {
    question.value = event.results[0][0].transcript;
    voiceStatus.textContent = "تم استقبال الأمر";
    runArther();
  };
  rec.onerror = () => voiceStatus.textContent = "تعذر التعرف على الصوت";
  rec.start();
}

window.ArtherListeningStatus = function(message) {
  const status = String(message || "");
  voiceStatus.textContent = status;

  if (/أنا أستمع إليك/.test(status)) {
    wakeError11Count = 0;
    return;
  }

  if (!wakeWordMode || !/تعذر سماع الأمر/.test(status)) return;

  // Error 7 means no clear speech; error 11 means the recognition
  // service disconnected. Retry with a bounded backoff for error 11.
  if (/رمز 11/.test(status)) {
    wakeError11Count = Math.min(wakeError11Count + 1, 4);
    const delay = Math.min(12000, 2500 * Math.pow(2, wakeError11Count - 1));
    voiceStatus.textContent = "انقطع محرك الصوت؛ سأعيد الاتصال تلقائياً...";
    scheduleWakeListening(delay);
  } else if (/رمز 7/.test(status)) {
    scheduleWakeListening(1800);
  } else {
    scheduleWakeListening(2500);
  }
};

window.ArtherVoiceResult = function(text) {
  wakeError11Count = 0;
  const spoken = String(text || "").trim();
  if (!spoken) {
    voiceStatus.textContent = "بانتظار كلمة آرثر...";
    scheduleWakeListening(450);
    return;
  }

  if (!wakeWordMode) {
    question.value = spoken;
    voiceStatus.textContent = "تم استقبال الأمر";
    runArther();
    return;
  }

  const normalized = normalizeCommand(spoken);
  const words = normalized.split(/\s+/);
  const wakeWords = new Set(["ارثر", "arther", "arthur", "آرثر"]);
  const wakeIndex = words.findIndex(word => wakeWords.has(word));

  if (wakeIndex < 0 && !wakeArmed) {
    voiceStatus.textContent = "بانتظار كلمة آرثر...";
    scheduleWakeListening(400);
    return;
  }

  if (wakeIndex >= 0) {
    words.splice(wakeIndex, 1);
    wakeArmed = true;
    clearTimeout(wakeArmTimer);
    wakeArmTimer = setTimeout(() => {
      wakeArmed = false;
      voiceStatus.textContent = "بانتظار كلمة آرثر...";
    }, 9000);
  }

  const command = words.join(" ").trim();
  if (!command) {
    voiceStatus.textContent = "نعم، تفضل...";
    speak("نعم؟");
    return;
  }

  wakeArmed = false;
  clearTimeout(wakeArmTimer);
  question.value = command;
  voiceStatus.textContent = "أسمع أمرك...";
  runArther();
};

window.ArtherDeviceResult = function(success, message) {
  show(message);
  if (message) speak(message);
};

window.ArtherFlashResult = function(success, message) {
  show(message);
  speak(message);
};

window.ArtherShizukuResult = function(success, message) {
  show(message);
  speak(message);
};

window.ArtherAppResult = function(success, message) {
  show(message);
  speak(message);
};

function normalizeCommand(value) {
  return String(value || "")
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[أإآٱ]/g, "ا")
    .replace(/[ى]/g, "ي")
    .replace(/[ؤ]/g, "و")
    .replace(/[ئ]/g, "ي")
    .replace(/[ًٌٍَُِّْـ]/g, "")
    .replace(/[؟?!.,،؛:()[\]{}"'`]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function nativeAction(action) {
  if (!window.ArtherNative?.performAction) {
    show("هذا الأمر يحتاج إلى نسخة Android الأصلية وتفعيل خدمة إمكانية الوصول.");
    speak("هذا الأمر يحتاج إلى تفعيل خدمة إمكانية الوصول.");
    return false;
  }
  show("جارٍ إرسال الأمر إلى Android...");
  window.ArtherNative.performAction(action);
  return true;
}

window.ArtherActionResult = function(accepted, message) {
  show(message || (accepted ? "قبل Android الأمر؛ تحقق من نتيجة الحركة على الشاشة." : "لم يقبل Android الأمر."));
  if (!accepted && message) speak(message);
};

function detectFlashCommand(prompt) {
  const t = normalizeCommand(prompt);
  const hasFlash = /\b(flash|torch|torche|lampe)\b/.test(t) ||
    /(فلاش|مصباح|كشاف|ضوء|ضو الهاتف|ضو التليفون)/.test(t);
  if (!hasFlash) return null;

  const off = /(اطف|اطفاء|اغلق|سكر|طفي|طف|وقف|حبس|نحي|طفّي|off|disable|eteins|eteindre|desactive|desactiver)/.test(t);
  const on = /(شغل|تشغيل|افتح|فتح|ولع|اضئ|انر|شعل|ضوي|خدم|اشعل|on|enable|turn on|allume|activer|active)/.test(t);

  if (off && !on) return "OFF";
  if (on && !off) return "ON";
  if (/(اطف|طفي|طف|اغلق|سكر|off)/.test(t)) return "OFF";
  if (/(شغل|افتح|ولع|شعل|on|allume)/.test(t)) return "ON";
  return null;
}

function extractAppName(prompt) {
  let t = normalizeCommand(prompt);
  const openIntent = /(افتح|فتح|شغل|تشغيل|ابدأ|روح ل|اذهب الى|انتقل الى|launch|open|start|ouvre|ouvrir|lance|demarre)/;
  if (!openIntent.test(t)) return null;

  t = t.replace(/^(من فضلك|لو سمحت|يا ارثر|ارثر)\s+/, "");
  t = t.replace(/(من فضلك|لو سمحت|يا ارثر|ارثر)/g, " ");
  t = t.replace(/^(من فضلك )?(افتح|فتح|شغل|تشغيل|ابدأ|روح ل|اذهب الى|انتقل الى|launch|open|start|ouvre|ouvrir|lance|demarre)\s+/, "");
  t = t.replace(/^(لي|ليّا|ليها|من فضلك)\s+/, "");
  t = t.replace(/\s+(من فضلك|لو سمحت|يا ارثر|ارثر)$/, "");
  t = t.replace(/^(التطبيق|برنامج)\s+/, "");
  return t.trim() || null;
}

function isDeviceInfoRequest(prompt) {
  const t = normalizeCommand(prompt);
  return /(معلومات|مواصفات|بيانات).*(هاتفي|الهاتف|الجهاز|تليفوني|تلفوني)|(هاتفي|الهاتف|الجهاز|تليفوني|تلفوني).*(معلومات|مواصفات|نوع|طراز)|(ما نوع|ما طراز|ما موديل|وش نوع|واش نوع|شنو نوع).*(هاتفي|الهاتف|جهازي|تليفوني)|device info|phone info|about this phone|informations du telephone|modele du telephone/.test(t);
}

function executeLocalCommand(prompt) {
  const text = normalizeCommand(prompt);

  const volumeIntent = /(الصوت|مستوي الصوت|صوت الهاتف|volume|son du telephone)/.test(text);
  if (volumeIntent) {
    let action = null;
    if (/(ارفع|زيد|علي|زود|increase|up|monte|augment)/.test(text)) action = "UP";
    else if (/(اخفض|نقص|وطي|خفض|decrease|down|baisse|diminue)/.test(text)) action = "DOWN";
    else if (/(اكتم|كتم|صامت|mute|muet)/.test(text)) action = "MUTE";
    else if (/(الغاء الكتم|الغ الكتم|اعد الصوت|ارجع الصوت|unmute|retablir le son)/.test(text)) action = "UNMUTE";
    else if (/(اقصي|اقصى|اعلي|اعلى|max|maximum)/.test(text)) action = "MAX";
    else if (/(ادني|ادنى|اقل|minimum|min|صفر)/.test(text)) action = "MIN";
    if (action && window.ArtherNative?.controlVolume) {
      window.ArtherNative.controlVolume(action);
      show("جارٍ تعديل مستوى الصوت...");
      return true;
    }
  }

  if (/(اقرا الشاشه|ماذا يظهر على الشاشه|ما الموجود على الشاشه|اقرا محتوي الشاشه|read screen|what is on screen|lis la page)/.test(text)) {
    if (!window.ArtherNative?.getScreenText) {
      show("قراءة الشاشة غير متاحة في هذه النسخة.");
      speak("قراءة الشاشة غير متاحة في هذه النسخة.");
      return true;
    }
    try {
      const snapshot = JSON.parse(window.ArtherNative.getScreenText());
      if (!snapshot.available) {
        show("فعّل خدمة إمكانية الوصول لـ ARTHER من إعدادات الهاتف، ثم أعد الأمر.");
        speak("فعّل خدمة إمكانية الوصول أولاً.");
      } else if (!snapshot.text) {
        show("لم يصلني نص قابل للقراءة من الشاشة الحالية. قد يكون التطبيق يحجب محتواه.");
        speak("لا يوجد نص ظاهر يمكن قراءته حالياً.");
      } else {
        const answer = "محتوى الشاشة الحالية:\\n" + snapshot.text.slice(0, 1800);
        show(answer);
        speak(answer);
      }
    } catch (error) {
      show("تعذر قراءة محتوى الشاشة.");
    }
    return true;
  }

  if (isDeviceInfoRequest(prompt)) {
    if (window.ArtherNative?.getDeviceInfo) {
      try {
        const info = JSON.parse(window.ArtherNative.getDeviceInfo());
        const answer = info.error || `معلومات الجهاز:
الشركة: ${info.manufacturer}
العلامة: ${info.brand}
الطراز: ${info.model}
إصدار Android: ${info.android_version} (SDK ${info.sdk})
البطارية: ${info.battery_percent >= 0 ? info.battery_percent + "%" : "غير متاحة"}`;
        show(answer);
        speak(answer);
      } catch (e) { show("تعذر قراءة معلومات الجهاز."); }
    } else { show("وظيفة قراءة معلومات الجهاز غير موجودة في هذه النسخة."); }
    return true;
  }

  const flash = detectFlashCommand(prompt);
  if (flash) {
    if (!window.ArtherNative?.setFlash) {
      show("التحكم بالفلاش غير متاح في هذه النسخة.");
      speak("التحكم بالفلاش غير متاح.");
      return true;
    }
    lastFlashOn = flash === "ON";
    show(flash === "ON" ? "جار تشغيل الفلاش..." : "جار إطفاء الفلاش...");
    window.ArtherNative.setFlash(flash);
    return true;
  }

  if (lastFlashOn && /^(اطفئه|اطفيه|طفيه|اغلقه|سكره|اطف|طفي|طف|off)$/.test(text)) {
    window.ArtherNative?.setFlash?.("OFF");
    lastFlashOn = false;
    return true;
  }

  const navigation = [
    [/(الرئيسيه|اذهب للرئيسيه|الشاشه الرئيسيه|home|accueil|ecran d accueil)$/, "HOME"],
    [/(رجوع|ارجع|الى الخلف|back|retour|revenir en arriere)$/, "BACK"],
    [/(التطبيقات المفتوحه|التطبيقات الاخيره|اخر التطبيقات|recent apps|applications recentes)$/, "RECENTS"],
    [/(افتح الاشعارات|اظهر الاشعارات|شريط الاشعارات|notifications|ouvrir les notifications)$/, "NOTIFICATIONS"],
    [/(الاعدادات السريعه|لوحه الاختصارات|quick settings|parametres rapides)$/, "QUICK_SETTINGS"],
    [/(اقفل الهاتف|اقفل الشاشه|قفل الشاشه|lock screen|verrouiller l ecran)$/, "LOCK_SCREEN"],
    [/(التقط صوره للشاشه|لقطه شاشه|سكرين شوت|screenshot|capture d ecran)$/, "TAKE_SCREENSHOT"],
    [/(انزل|مرر للاسفل|انزل الصفحه|scroll down|defiler vers le bas)$/, "SCROLL_DOWN"],
    [/(اصعد|مرر للاعلى|اصعد الصفحه|scroll up|defiler vers le haut)$/, "SCROLL_UP"]
  ];

  for (const [pattern, action] of navigation) {
    if (pattern.test(text)) {
      nativeAction(action);
      return true;
    }
  }

  const appName = extractAppName(prompt);
  if (appName) {
    if (window.ArtherNative?.openApp) {
      show("أبحث عن التطبيق: " + appName);
      window.ArtherNative.openApp(appName);
    } else {
      show("فتح التطبيقات غير متاح في هذه النسخة.");
    }
    return true;
  }

  const clickMatch = text.match(/(?:اضغط على|انقر على|اختار|اختر|click|appuie sur|clique sur|selectionne)\s+(.+)/);
  if (clickMatch) {
    nativeAction("CLICK_TEXT:" + clickMatch[1].trim());
    return true;
  }

  const longClickMatch = text.match(/(?:اضغط مطولا على|ضغط مطول على|long press|appui long sur|appuie longuement sur)\s+(.+)/);
  if (longClickMatch) {
    nativeAction("LONG_CLICK_TEXT:" + longClickMatch[1].trim());
    return true;
  }

  const typeMatch = text.match(/(?:اكتب|اكتب النص|اكتب له|type|ecris|ecrire|tape)\s+(.+)/);
  if (typeMatch) {
    if (window.ArtherNative?.typeText) {
      show("جارٍ إدخال النص في الحقل المحدد...");
      window.ArtherNative.typeText(typeMatch[1].trim());
    } else show("خدمة الكتابة غير متاحة. فعّل خدمة إمكانية الوصول.");
    return true;
  }

  return false;
}

async function runArther() {
  const prompt = question.value.trim();
  if (!prompt || busy) return;
  if (executeLocalCommand(prompt)) {
    scheduleWakeListening(1600);
    return;
  }

  busy = true;
  if ($("run")) $("run").disabled = true;
  connection.textContent = "ARTHER يعالج طلبك...";

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
    if (!response.ok) throw new Error(data.detail || "فشل الاتصال بالخادم");

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

$("listen").addEventListener("click", startWakeWordMode);
$("run").addEventListener("click", runArther);

$("stopVoice").addEventListener("click", () => {
  window.ArtherNative?.stopSpeaking?.();
  if ("speechSynthesis" in window) speechSynthesis.cancel();
});

$("home").addEventListener("click", () => nativeAction("HOME"));
$("back").addEventListener("click", () => nativeAction("BACK"));
$("scrollDown").addEventListener("click", () => nativeAction("SCROLL_DOWN"));

$("shizuku").addEventListener("click", () => {
  if (!window.ArtherNative?.requestShizukuPermission) {
    show("ميزة Shizuku غير موجودة في هذه النسخة. ثبّت آخر تحديث من ARTHER.");
    return;
  }
  show("جارٍ التحقق من Shizuku وطلب الصلاحية...");
  window.ArtherNative.requestShizukuPermission();
});

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
if (window.ArtherNative?.shizukuStatus) window.ArtherNative.shizukuStatus();
startWakeWordMode();

document.addEventListener("visibilitychange", () => {
  if (document.hidden) {
    clearTimeout(wakeRestartTimer);
    return;
  }
  if (wakeWordMode) scheduleWakeListening(500);
});