"use strict";
// Build sync: include speech recognition service visibility manifest fix.

const API = "https://arther-api.onrender.com";
const $ = id => document.getElementById(id);

const report = $("report");
const question = $("question");
const connection = $("connection");
const voiceStatus = $("voiceStatus");
const chatMessages = $("chatMessages");

function getDeviceSessionId() {
  const key = "arther_device_session_id";
  try {
    let id = localStorage.getItem(key);
    if (!id) {
      id = "arther-" + (window.crypto?.randomUUID?.() ||
        (Date.now().toString(36) + "-" + Math.random().toString(36).slice(2)));
      localStorage.setItem(key, id);
    }
    return id;
  } catch (_) {
    return "arther-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2);
  }
}
const SESSION_ID = getDeviceSessionId();
const CHAT_STORAGE_KEY = "arther_chat_history_v1";
let chatHistory = [];

function saveChatHistory() {
  try { localStorage.setItem(CHAT_STORAGE_KEY, JSON.stringify(chatHistory.slice(-60))); }
  catch (_) {}
}

function renderChatHistory() {
  if (!chatMessages) return;
  chatMessages.innerHTML = "";
  if (!chatHistory.length) {
    chatMessages.innerHTML = '<p class="chat-empty">ستظهر إجابات ARTHER هنا. يمكنك التحدث أو الكتابة.</p>';
    return;
  }
  chatHistory.slice(-60).forEach(item => addChatMessage(item.role, item.message, false));
}

function addChatMessage(role, message, persist = true) {
  if (!chatMessages || !String(message || "").trim()) return;
  if (persist) {
    chatHistory.push({ role: role === "user" ? "user" : "assistant", message: String(message), at: Date.now() });
    chatHistory = chatHistory.slice(-60);
    saveChatHistory();
  }
  const empty = chatMessages.querySelector(".chat-empty");
  if (empty) empty.remove();
  const bubble = document.createElement("div");
  bubble.className = "chat-message " + (role === "user" ? "user" : "assistant");
  const speaker = document.createElement("span");
  speaker.className = "speaker";
  speaker.textContent = role === "user" ? "أنت" : "ARTHER";
  const content = document.createElement("div");
  content.textContent = String(message);
  bubble.append(speaker, content);
  chatMessages.appendChild(bubble);
  while (chatMessages.children.length > 60) chatMessages.removeChild(chatMessages.firstElementChild);
  chatMessages.scrollTop = chatMessages.scrollHeight;
}

function clearChat() {
  chatHistory = [];
  saveChatHistory();
  if (!chatMessages) return;
  chatMessages.innerHTML = '<p class="chat-empty">ستظهر إجابات ARTHER هنا. يمكنك التحدث أو الكتابة.</p>';
}

try {
  const saved = JSON.parse(localStorage.getItem(CHAT_STORAGE_KEY) || "[]");
  if (Array.isArray(saved)) {
    chatHistory = saved.filter(item =>
      item && (item.role === "user" || item.role === "assistant") &&
      typeof item.message === "string"
    ).slice(-60);
  }
} catch (_) { chatHistory = []; }
renderChatHistory();

let busy = false;
let lastFlashOn = false;
let wakeWordMode = !!voiceStatus;
let wakeArmed = false;
let wakeRestartTimer = null;
let wakeArmTimer = null;
let wakeError11Count = 0;

function scheduleWakeListening(delay = 500) {
  // The restored classic interface has no voice controls/status element.
  // Keep voice wake-listening disabled there instead of crashing the page.
  if (!voiceStatus || !wakeWordMode || document.hidden) return;
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
  if (!voiceStatus) {
    wakeWordMode = false;
    return;
  }
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
  refreshCapabilities();
};

window.ArtherCapabilities = function(raw) {
  const el = $("permissionStatus");
  if (!el) return;
  try {
    const c = typeof raw === "string" ? JSON.parse(raw) : raw;
    const lines = [
      "الميكروفون: " + (c.microphone ? "مسموح" : "يحتاج إذناً"),
      "التحكم بالشاشة: " + (c.accessibility ? "مفعّل" : "غير مفعّل"),
      "تعديل السطوع: " + (c.writeSettings ? "مسموح" : "يحتاج إذناً"),
      "Shizuku: " + (!c.shizukuRunning ? "غير مشغّل" : (c.shizukuGranted ? "متصل ومصرّح" : "ينتظر الإذن"))
    ];
    el.textContent = lines.join(" · ");
  } catch (_) {
    el.textContent = "تعذر قراءة حالة الصلاحيات.";
  }
};

function refreshCapabilities() {
  if (window.ArtherNative?.getCapabilities) {
    try { window.ArtherNative.getCapabilities(); } catch (_) {}
  }
}

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
    .replace(/[٠-٩]/g, d => String("٠١٢٣٤٥٦٧٨٩".indexOf(d)))
    .replace(/[۰-۹]/g, d => String("۰۱۲۳۴۵۶۷۸۹".indexOf(d)))
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


window.ArtherLocationResult = function(raw) {
  let result;
  try { result = typeof raw === "string" ? JSON.parse(raw) : raw; }
  catch (_) { show("وصل رد GPS بصيغة غير مفهومة."); return; }
  if (!result || !result.ok) {
    const message = result?.error || "تعذر تحديد موقع الهاتف.";
    show(message);
    speak(message);
    return;
  }
  const lat = Number(result.latitude), lon = Number(result.longitude);
  const accuracy = Number(result.accuracy_m);
  const message = "تم تحديد موقع الهاتف.\nخط العرض: " + lat.toFixed(6) +
    "\nخط الطول: " + lon.toFixed(6) +
    (Number.isFinite(accuracy) ? "\nدقة تقريبية: " + Math.round(accuracy) + " متر" : "");
  show(message);
  speak("وجدت موقع هاتفك. أفتح الخريطة الآن.");
  window.ArtherNative?.openMap?.(lat + "," + lon);
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
  return /(معلومات|مواصفات|بيانات).*(هاتفي|الهاتف|الجهاز|تليفوني|تلفوني)|(هاتفي|الهاتف|الجهاز|تليفوني|تلفوني).*(معلومات|مواصفات|نوع|طراز)|(ما نوع|ما طراز|ما موديل|وش نوع|واش نوع|شنو نوع).*(هاتفي|الهاتف|جهازي|تليفوني)|البطاريه|نسبه الشحن|كم شحن|battery|niveau batterie|device info|phone info|about this phone|informations du telephone|modele du telephone/.test(t);
}

function executeLocalCommand(prompt) {
  const text = normalizeCommand(prompt);

  // Safe native intents: Android asks for confirmation where appropriate.
  if (/(افتح الكاميرا|شغل الكاميرا|open camera|ouvrir la camera)/.test(text)) {
    window.ArtherNative?.openCamera?.();
    return true;
  }
  if (/(افتح اعدادات الواي فاي|اعدادات الواي فاي|wifi settings|parametres wifi)/.test(text)) {
    window.ArtherNative?.openSettings?.("wifi");
    return true;
  }
  if (/(افتح اعدادات البلوتوث|اعدادات البلوتوث|bluetooth settings|parametres bluetooth)/.test(text)) {
    window.ArtherNative?.openSettings?.("bluetooth");
    return true;
  }
  if (/(افتح اعدادات الشاشه|اعدادات الشاشه|display settings|parametres affichage)/.test(text)) {
    window.ArtherNative?.openSettings?.("display");
    return true;
  }
  if (/(افتح اعدادات الصوت|اعدادات الصوت|sound settings|parametres son)/.test(text)) {
    window.ArtherNative?.openSettings?.("sound");
    return true;
  }
  if (/(افتح اعدادات الموقع|اعدادات الموقع|location settings|parametres localisation)/.test(text)) {
    window.ArtherNative?.openSettings?.("location");
    return true;
  }
  if (/(افتح اعدادات البطاريه|اعدادات البطاريه|battery settings|parametres batterie)/.test(text)) {
    window.ArtherNative?.openSettings?.("battery");
    return true;
  }
  if (/(افتح اداره التطبيقات|اعدادات التطبيقات|التطبيقات المثبته|manage apps|app settings|parametres applications)/.test(text)) {
    window.ArtherNative?.openSettings?.("apps");
    return true;
  }
  if (/(افتح الاعدادات|اعدادات الهاتف|اعدادات الجهاز|system settings|phone settings|parametres du telephone)/.test(text)) {
    window.ArtherNative?.openSettings?.("system");
    return true;
  }
  const brightness = text.match(/(?:سطوع|اضاءه الشاشه|سطع الشاشه|brightness|luminosite).*?(\d{1,3})\s*%?/);
  if (brightness) {
    const level = Math.max(1, Math.min(100, Number(brightness[1])));
    window.ArtherNative?.setBrightness?.(String(level));
    return true;
  }
  if (/(ارفع|زيد|علي).*(سطوع|اضاءه الشاشه)|(increase|raise).*brightness/.test(text)) {
    window.ArtherNative?.setBrightness?.("75");
    return true;
  }
  if (/(اخفض|نقص|وطي).*(سطوع|اضاءه الشاشه)|(decrease|lower).*brightness/.test(text)) {
    window.ArtherNative?.setBrightness?.("25");
    return true;
  }
  const timer = text.match(/(?:مؤقت|تايمر|timer|minuteur)(?:\s+لمده|\s+لمدة|\s+بعد|\s+for)?\s*(\d{1,3})\s*(ثانيه|ثواني|دقيقه|دقائق|ساعه|ساعات|seconds?|minutes?|hours?)?/);
  if (timer) {
    let seconds = Number(timer[1]);
    const unit = timer[2] || "دقائق";
    if (/ثاني|second/.test(unit)) {}
    else if (/ساع|hour/.test(unit)) seconds *= 3600;
    else seconds *= 60;
    window.ArtherNative?.createTimer?.(String(seconds));
    return true;
  }
  const alarm = text.match(/(?:منبه|المنبه|alarm|reveil).*?(\d{1,2})(?:\s*[: ]\s*(\d{2}))?/);
  if (alarm) {
    const hour = Number(alarm[1]), minute = Number(alarm[2] || 0);
    if (hour < 24 && minute < 60) {
      window.ArtherNative?.createAlarm?.(String(hour), String(minute), "ARTHER");
      return true;
    }
  }
  const phone = text.match(/(?:اتصل|اتصال|عيط|عيطلي|call|appelle)\s*(?:ب|على|ل|au)?\s*([+\d][\d\s-]{5,})/);
  if (phone) {
    window.ArtherNative?.dialPhone?.(phone[1].replace(/[\s-]/g, ""));
    return true;
  }
  const sms = text.match(/(?:اكتب رساله|ارسل رساله|ابعث رساله|compose sms|sms)\s+(?:الى|ل|to)?\s*([+\d][\d\s-]{5,})\s+(?:نصها|محتواها|تقول|message)?\s*(.+)/);
  if (sms) {
    window.ArtherNative?.composeSms?.(sms[1].replace(/[\s-]/g, ""), sms[2]);
    return true;
  }

  if (/(موقعي الحالي|اين انا|وين راني|حدد موقعي|اعرف موقعي|مكاني الحالي|where am i|my current location|find my location|ma position actuelle|ou suis je)/.test(text)) {
    if (window.ArtherNative?.getCurrentLocation) {
      show("جارٍ تحديد موقع الهاتف عبر GPS...");
      window.ArtherNative.getCurrentLocation();
    } else {
      show("ميزة تحديد الموقع غير موجودة في هذه النسخة. حدّث ARTHER.");
    }
    return true;
  }
  const map = text.match(/(?:افتح خرائط|افتح الخريطه|وجهني الى|اذهب الى|navigate to|maps to)\s+(.+)/);
  if (map) {
    window.ArtherNative?.openMap?.(map[1]);
    return true;
  }
  const website = text.match(/(?:افتح موقع|افتح الرابط|open website|open site|ouvre le site)\s+(.+)/);
  if (website) {
    window.ArtherNative?.openWeb?.(website[1]);
    return true;
  }
  const share = text.match(/(?:شارك النص|شارك|share text|partage)\s+(.+)/);
  if (share) {
    window.ArtherNative?.shareText?.(share[1]);
    return true;
  }

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
    addChatMessage("user", prompt);
    scheduleWakeListening(1600);
    return;
  }

  addChatMessage("user", prompt);
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
        session_id: SESSION_ID
      })
    });

    const data = await response.json();
    if (!response.ok) throw new Error(data.detail || "فشل الاتصال بالخادم");

    const answer = data.answer || "لم يصل رد من الخادم.";
    addChatMessage("assistant", answer);
    show(answer);
    connection.textContent = "متصل";
    speak(answer);

  } catch (error) {
    addChatMessage("assistant", "تعذر إكمال الطلب: " + error.message);
    show("خطأ: " + error.message);
    connection.textContent = "خطأ في الاتصال";

  } finally {
    busy = false;
    if ($("run")) $("run").disabled = false;
  }
}

if ($("listen")) $("listen").addEventListener("click", startWakeWordMode);
if ($("run")) $("run").addEventListener("click", runArther);
if ($("clearChat")) $("clearChat").addEventListener("click", clearChat);
if ($("clearText")) $("clearText").addEventListener("click", () => {
  question.value = "";
  question.focus();
});
if (question) question.addEventListener("keydown", event => {
  if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
    event.preventDefault();
    runArther();
  }
});

if ($("stopVoice")) $("stopVoice").addEventListener("click", () => {
  window.ArtherNative?.stopSpeaking?.();
  if ("speechSynthesis" in window) speechSynthesis.cancel();
});

if ($("home")) $("home").addEventListener("click", () => nativeAction("HOME"));
if ($("back")) $("back").addEventListener("click", () => nativeAction("BACK"));
if ($("scrollDown")) $("scrollDown").addEventListener("click", () => nativeAction("SCROLL_DOWN"));
if ($("openCamera")) $("openCamera").addEventListener("click", () => window.ArtherNative?.openCamera?.());
if ($("openWifi")) $("openWifi").addEventListener("click", () => window.ArtherNative?.openSettings?.("wifi"));
if ($("openBluetooth")) $("openBluetooth").addEventListener("click", () => window.ArtherNative?.openSettings?.("bluetooth"));
if ($("openDisplay")) $("openDisplay").addEventListener("click", () => window.ArtherNative?.openSettings?.("display"));
if ($("openSound")) $("openSound").addEventListener("click", () => window.ArtherNative?.openSettings?.("sound"));
if ($("openLocationSettings")) $("openLocationSettings").addEventListener("click", () => window.ArtherNative?.openSettings?.("location"));
if ($("openBattery")) $("openBattery").addEventListener("click", () => window.ArtherNative?.openSettings?.("battery"));
if ($("openAppsSettings")) $("openAppsSettings").addEventListener("click", () => window.ArtherNative?.openSettings?.("apps"));
if ($("openSystemSettings")) $("openSystemSettings").addEventListener("click", () => window.ArtherNative?.openSettings?.("system"));
if ($("openMap")) $("openMap").addEventListener("click", () => window.ArtherNative?.getCurrentLocation?.());
if ($("openAlarms")) $("openAlarms").addEventListener("click", () => window.ArtherNative?.showAlarms?.());
if ($("quickScreenshot")) $("quickScreenshot").addEventListener("click", () => nativeAction("TAKE_SCREENSHOT"));
if ($("quickNotifications")) $("quickNotifications").addEventListener("click", () => nativeAction("NOTIFICATIONS"));
if ($("quickLock")) $("quickLock").addEventListener("click", () => nativeAction("LOCK_SCREEN"));
if ($("quickVolumeUp")) $("quickVolumeUp").addEventListener("click", () => window.ArtherNative?.controlVolume?.("UP"));
if ($("quickVolumeDown")) $("quickVolumeDown").addEventListener("click", () => window.ArtherNative?.controlVolume?.("DOWN"));
if ($("requestBrightness")) $("requestBrightness").addEventListener("click", () => window.ArtherNative?.requestBrightnessPermission?.());

if ($("requestMic")) $("requestMic").addEventListener("click", () => {
  if (window.ArtherNative?.requestMicrophonePermission) {
    window.ArtherNative.requestMicrophonePermission();
  } else show("طلب إذن الميكروفون غير متاح في هذه النسخة.");
});

if ($("enableAccessibility")) $("enableAccessibility").addEventListener("click", () => {
  if (window.ArtherNative?.openAccessibilitySettings) {
    window.ArtherNative.openAccessibilitySettings();
  } else show("تعذر فتح إعدادات إمكانية الوصول.");
});

if ($("shizuku")) $("shizuku").addEventListener("click", () => {
  if (!window.ArtherNative?.requestShizukuPermission) {
    show("ميزة Shizuku غير موجودة في هذه النسخة. ثبّت آخر تحديث من ARTHER.");
    return;
  }
  show("جارٍ التحقق من Shizuku وطلب الصلاحية...");
  window.ArtherNative.requestShizukuPermission();
});

if ($("clear")) $("clear").addEventListener("click", () => {
  if ($("question")) $("question").value = "";
  if ($("report")) $("report").classList.add("hidden");
});
if ($("execute")) $("execute").addEventListener("click", () => {
  show("تنفيذ Python غير متاح حالياً على الخادم.");
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
refreshCapabilities();
if (window.ArtherNative?.shizukuStatus) window.ArtherNative.shizukuStatus();
startWakeWordMode();

document.addEventListener("visibilitychange", () => {
  if (document.hidden) {
    clearTimeout(wakeRestartTimer);
    return;
  }
  if (wakeWordMode) scheduleWakeListening(500);
});