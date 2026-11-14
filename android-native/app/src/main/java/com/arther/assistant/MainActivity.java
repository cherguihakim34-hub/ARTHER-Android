package com.arther.assistant;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import com.getcapacitor.BridgeActivity;
import rikka.shizuku.Shizuku;
import org.json.JSONObject;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends BridgeActivity {
    private ArtherBridge artherBridge;
    private static final int SHIZUKU_REQUEST_CODE = 4102;
    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener = (requestCode, grantResult) -> {
        if (requestCode == SHIZUKU_REQUEST_CODE && artherBridge != null) {
            artherBridge.reportShizuku(grantResult == PackageManager.PERMISSION_GRANTED,
                grantResult == PackageManager.PERMISSION_GRANTED ? "تم منح ARTHER صلاحية Shizuku" : "لم يتم منح صلاحية Shizuku");
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView webView = getBridge().getWebView();
        artherBridge = new ArtherBridge(this, webView);
        webView.addJavascriptInterface(artherBridge, "ArtherNative");
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (artherBridge != null) artherBridge.getCapabilities();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (artherBridge == null) return;
        if (requestCode == 701 && grantResults.length > 0 &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) artherBridge.beginListening();
        if (requestCode == 702) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                artherBridge.applyPendingFlash();
            else artherBridge.reportFlash(false, "لم تسمح بإذن الكاميرا");
        }
        artherBridge.getCapabilities();
    }

    public static class ArtherBridge {
        private final MainActivity activity;
        private final WebView webView;
        private SpeechRecognizer recognizer;
        private TextToSpeech tts;
        private String pendingFlash;

        ArtherBridge(MainActivity activity, WebView webView) {
            this.activity = activity;
            this.webView = webView;
            tts = new TextToSpeech(activity, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    tts.setLanguage(Locale.forLanguageTag(preferredLanguageTag()));
                    tts.setSpeechRate(0.92f);
                }
            });
        }


        @JavascriptInterface
        public void getCapabilities() {
            try {
                JSONObject c = new JSONObject();
                c.put("microphone", activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
                c.put("camera", activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED);
                c.put("writeSettings", Settings.System.canWrite(activity));
                c.put("accessibility", ArtherAccessibilityService.isEnabled());
                boolean running = Shizuku.pingBinder();
                c.put("shizukuRunning", running);
                c.put("shizukuGranted", running && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED);
                String safe = JSONObject.quote(c.toString());
                webView.post(() -> webView.evaluateJavascript(
                    "if(window.ArtherCapabilities){window.ArtherCapabilities(" + safe + ");}", null
                ));
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void requestMicrophonePermission() {
            activity.runOnUiThread(() -> {
                if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    sendStatus("إذن الميكروفون ممنوح بالفعل");
                    getCapabilities();
                } else {
                    activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 701);
                }
            });
        }

        @JavascriptInterface
        public void openAccessibilitySettings() {
            activity.runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(intent);
                    Toast.makeText(activity, "فعّل خدمة ARTHER Phone Control ثم ارجع إلى التطبيق", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(activity, "تعذر فتح إعدادات إمكانية الوصول", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void shizukuStatus() {
            boolean running = Shizuku.pingBinder();
            boolean granted = running && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            reportShizuku(granted, !running ? "Shizuku غير مشغّل" :
                (granted ? "Shizuku متصل ومصرّح" : "Shizuku يعمل، بانتظار منح الصلاحية"));
        }

        @JavascriptInterface
        public void requestShizukuPermission() {
            activity.runOnUiThread(() -> {
                if (!Shizuku.pingBinder()) {
                    reportShizuku(false, "شغّل Shizuku أولاً");
                } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    reportShizuku(true, "ARTHER لديه صلاحية Shizuku");
                } else {
                    Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
                }
            });
        }

        void reportShizuku(boolean granted, String message) {
            String safe = JSONObject.quote(message);
            webView.post(() -> webView.evaluateJavascript(
                "if(window.ArtherShizukuResult){window.ArtherShizukuResult(" + granted + "," + safe + ");}",
                null
            ));
        }

        @JavascriptInterface
        public String getDeviceInfo() {
            try {
                android.content.Intent batteryIntent = activity.registerReceiver(
                    null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
                int level = -1;
                if (batteryIntent != null) {
                    int current = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                    int scale = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                    if (current >= 0 && scale > 0) level = Math.round(current * 100f / scale);
                }
                JSONObject info = new JSONObject();
                info.put("manufacturer", android.os.Build.MANUFACTURER);
                info.put("brand", android.os.Build.BRAND);
                info.put("model", android.os.Build.MODEL);
                info.put("device", android.os.Build.DEVICE);
                info.put("android_version", android.os.Build.VERSION.RELEASE);
                info.put("sdk", android.os.Build.VERSION.SDK_INT);
                info.put("battery_percent", level);
                info.put("language", Locale.getDefault().toLanguageTag());
                return info.toString();
            } catch (Exception e) {
                return JSONObject.quote("تعذر قراءة معلومات الجهاز");
            }
        }

        @JavascriptInterface
        public String getScreenText() {
            try {
                JSONObject result = new JSONObject();
                result.put("available", ArtherAccessibilityService.isEnabled());
                result.put("package", ArtherAccessibilityService.getCachedPackageName());
                result.put("text", ArtherAccessibilityService.getCachedScreenText());
                return result.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        private String preferredLanguageTag() {
            String language = Locale.getDefault().getLanguage();
            if ("fr".equalsIgnoreCase(language)) return "fr-FR";
            if ("en".equalsIgnoreCase(language)) return "en-US";
            // Use a widely supported Arabic locale; ar-DZ is not available in every recognition service.
            return "ar-SA";
        }

        @JavascriptInterface
        public void startListening() {
            activity.runOnUiThread(() -> {
                if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    sendStatus("أحتاج إذن الميكروفون. اسمح لـ ARTHER باستخدام الميكروفون.");
                    activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 701);
                    return;
                }
                beginListening();
            });
        }

        void beginListening() {
            activity.runOnUiThread(() -> {
                if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
                    sendStatus("التعرف على الصوت غير متاح");
                    return;
                }
                // Recreate a clean recognizer instance for each listening session.
                if (recognizer != null) {
                    try { recognizer.cancel(); } catch (Exception ignored) {}
                    try { recognizer.destroy(); } catch (Exception ignored) {}
                    recognizer = null;
                }

                // Prefer Google's installed recognition service when available, then use
                // Android's configured default provider as a fallback.
                android.content.ComponentName selectedService = null;
                Intent serviceQuery = new Intent(android.speech.RecognitionService.SERVICE_INTERFACE);
                List<ResolveInfo> recognitionServices = activity.getPackageManager()
                    .queryIntentServices(serviceQuery, PackageManager.MATCH_ALL);
                for (ResolveInfo info : recognitionServices) {
                    if (info.serviceInfo != null &&
                        "com.google.android.googlequicksearchbox".equals(info.serviceInfo.packageName)) {
                        selectedService = new android.content.ComponentName(
                            info.serviceInfo.packageName, info.serviceInfo.name);
                        break;
                    }
                }
                try {
                    recognizer = selectedService != null
                        ? SpeechRecognizer.createSpeechRecognizer(activity, selectedService)
                        : SpeechRecognizer.createSpeechRecognizer(activity);
                } catch (Exception providerError) {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
                }

                recognizer.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(Bundle params) { sendStatus("أنا أستمع إليك..."); }
                    @Override public void onBeginningOfSpeech() {}
                    @Override public void onRmsChanged(float value) {}
                    @Override public void onBufferReceived(byte[] buffer) {}
                    @Override public void onEndOfSpeech() { sendStatus("أحلل كلامك..."); }
                    @Override public void onError(int error) {
                        String reason;
                        switch (error) {
                            case SpeechRecognizer.ERROR_AUDIO:
                                reason = "مشكلة في الميكروفون أو مسار الصوت";
                                break;
                            case SpeechRecognizer.ERROR_CLIENT:
                                reason = "توقف محرك التعرف عن العمل";
                                break;
                            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                                reason = "إذن الميكروفون غير ممنوح. اسمح لـ ARTHER باستخدام الميكروفون";
                                break;
                            case SpeechRecognizer.ERROR_NETWORK:
                                reason = "تعذر الاتصال بخدمة التعرف على الصوت. تحقق من الإنترنت";
                                break;
                            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                                reason = "انتهت مهلة الاتصال بخدمة التعرف على الصوت";
                                break;
                            case SpeechRecognizer.ERROR_NO_MATCH:
                                reason = "لم أتعرف على كلمات واضحة. اقترب من الميكروفون وتكلم بوضوح";
                                break;
                            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                                reason = "الميكروفون مشغول بتطبيق آخر. أغلق التطبيق الذي يستخدمه";
                                break;
                            case SpeechRecognizer.ERROR_SERVER:
                                reason = "خدمة التعرف على الصوت لا تستجيب حالياً";
                                break;
                            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                                reason = "لم أسمع كلاماً. قل آرثر بعد بدء الاستماع مباشرة";
                                break;
                            case 11:
                                reason = "انقطع الاتصال بمحرك التعرف على الكلام. أعد تشغيل خدمة Google للتعرف على الكلام وتحقق من اتصال الإنترنت";
                                break;
                            case 12:
                            case 13:
                                reason = "لغة التعرف غير مدعومة أو غير محمّلة. تحقق من لغات تطبيق Google أو جرّب الفرنسية";
                                break;
                            default:
                                reason = "خطأ غير معروف في التعرف على الصوت";
                                break;
                        }
                        sendStatus("تعذر سماع الأمر: " + reason + " (رمز " + error + ")");
                    }
                    @Override public void onResults(Bundle results) {
                        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (matches != null && !matches.isEmpty()) {
                            String safe = JSONObject.quote(matches.get(0));
                            webView.post(() -> webView.evaluateJavascript("window.ArtherVoiceResult(" + safe + ")", null));
                        }
                    }
                    @Override public void onPartialResults(Bundle b) {}
                    @Override public void onEvent(int e, Bundle b) {}
                });
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, preferredLanguageTag());
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, preferredLanguageTag());
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.getPackageName());
                try {
                    recognizer.startListening(intent);
                } catch (Exception startError) {
                    sendStatus("تعذر تشغيل خدمة التعرف على الصوت: " + startError.getClass().getSimpleName());
                    try { recognizer.destroy(); } catch (Exception ignored) {}
                    recognizer = null;
                }
            });
        }

        @JavascriptInterface
        public void setFlash(String state) {
            activity.runOnUiThread(() -> {
                String value = state == null ? "" : state.trim().toUpperCase(Locale.ROOT);
                if (!value.equals("ON") && !value.equals("OFF")) {
                    reportFlash(false, "أمر الفلاش غير صحيح");
                    return;
                }
                pendingFlash = value;
                if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, 702);
                    return;
                }
                applyPendingFlash();
            });
        }

        void applyPendingFlash() {
            activity.runOnUiThread(() -> {
                if (pendingFlash == null) return;
                String requested = pendingFlash;
                try {
                    CameraManager camera = (CameraManager) activity.getSystemService(android.content.Context.CAMERA_SERVICE);
                    String selectedId = null;
                    for (String id : camera.getCameraIdList()) {
                        CameraCharacteristics characteristics = camera.getCameraCharacteristics(id);
                        Boolean hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                        Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                        if (Boolean.TRUE.equals(hasFlash) &&
                            (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK)) {
                            selectedId = id;
                            break;
                        }
                    }
                    if (selectedId == null) {
                        reportFlash(false, "لا يوجد فلاش متاح في الجهاز");
                        return;
                    }
                    camera.setTorchMode(selectedId, requested.equals("ON"));
                    reportFlash(true, requested.equals("ON") ? "تم تشغيل الفلاش" : "تم إطفاء الفلاش");
                } catch (Exception e) {
                    reportFlash(false, "تعذر التحكم في الفلاش: " + e.getClass().getSimpleName());
                } finally {
                    pendingFlash = null;
                }
            });
        }

        void reportFlash(boolean success, String message) {
            String safe = JSONObject.quote(message);
            String script = "if(window.ArtherFlashResult){window.ArtherFlashResult(" + success + "," + safe + ");}";
            webView.post(() -> webView.evaluateJavascript(script, null));
        }

        private int previousMusicVolume = -1;

        @JavascriptInterface
        public void controlVolume(String command) {
            activity.runOnUiThread(() -> {
                try {
                    android.media.AudioManager audio = (android.media.AudioManager)
                        activity.getSystemService(android.content.Context.AUDIO_SERVICE);
                    int current = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                    int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                    String action = command == null ? "" : command.trim().toUpperCase(Locale.ROOT);
                    switch (action) {
                        case "UP":
                            audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                                android.media.AudioManager.ADJUST_RAISE, 0);
                            break;
                        case "DOWN":
                            audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                                android.media.AudioManager.ADJUST_LOWER, 0);
                            break;
                        case "MUTE":
                            if (current > 0) previousMusicVolume = current;
                            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0);
                            break;
                        case "UNMUTE":
                            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                                previousMusicVolume > 0 ? Math.min(previousMusicVolume, max) : Math.max(1, max / 2), 0);
                            break;
                        case "MAX":
                            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, max, 0);
                            break;
                        case "MIN":
                            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0);
                            break;
                        default:
                            deviceResult(false, "أمر مستوى الصوت غير معروف");
                            return;
                    }
                    int now = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                    deviceResult(true, "مستوى صوت الوسائط الآن " + Math.round(now * 100f / Math.max(1, max)) + "%");
                } catch (Exception e) {
                    deviceResult(false, "تعذر تغيير مستوى الصوت");
                }
            });
        }

        private void deviceResult(boolean success, String message) {
            String safe = JSONObject.quote(message);
            webView.post(() -> webView.evaluateJavascript(
                "if(window.ArtherDeviceResult){window.ArtherDeviceResult(" + success + "," + safe + ");}", null
            ));
        }

        @JavascriptInterface
        public void speak(String text) {
            activity.runOnUiThread(() -> {
                if (tts != null && text != null)
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ARTHER_REPLY");
            });
        }

        @JavascriptInterface
        public void stopSpeaking() {
            activity.runOnUiThread(() -> { if (tts != null) tts.stop(); });
        }

        @JavascriptInterface
        public void performAction(String action) {
            activity.runOnUiThread(() -> {
                boolean accepted = ArtherAccessibilityService.perform(action);
                String message;
                if (!ArtherAccessibilityService.isEnabled()) {
                    message = "خدمة إمكانية الوصول غير مفعّلة. فعّلها من إعدادات الهاتف.";
                } else if (accepted) {
                    message = "تم إرسال الأمر إلى Android. تحقق من تغيّر الشاشة قبل متابعة الخطوة التالية.";
                } else {
                    message = "لم يقبل Android تنفيذ الأمر. قد لا يكون العنصر متاحاً على الشاشة الحالية.";
                }
                boolean result = accepted;
                String safe = JSONObject.quote(message);
                webView.post(() -> webView.evaluateJavascript(
                    "if(window.ArtherActionResult){window.ArtherActionResult(" + result + "," + safe + ");}",
                    null
                ));
                if (!accepted) Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
            });
        }

        private String normalizeAppName(String value) {
            String s = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD);
            s = s.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
            s = s.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
                 .replace('ى', 'ي').replace('ؤ', 'و').replace('ئ', 'ي');
            s = s.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
            s = s.replaceAll("^(افتح|فتح|شغل|تشغيل|ابدأ|التطبيق|برنامج|open|launch|start|ouvre|ouvrir)\\s+", "");
            return s.replaceAll("\\s+", " ").trim();
        }

        private int appMatchScore(String wanted, String label) {
            if (wanted.isEmpty() || label.isEmpty()) return 0;
            if (wanted.equals(label)) return 100;
            if (label.contains(wanted) || wanted.contains(label)) return 75;
            String[] wantedParts = wanted.split("\\s+");
            Set<String> labelParts = new HashSet<>();
            for (String part : label.split("\\s+")) labelParts.add(part);
            int hits = 0;
            for (String part : wantedParts) if (part.length() > 1 && labelParts.contains(part)) hits++;
            if (hits == 0) return 0;
            return 40 + (hits * 50 / Math.max(1, wantedParts.length));
        }

        @JavascriptInterface
        public void openApp(String appName) {
            activity.runOnUiThread(() -> {
                try {
                    String wanted = normalizeAppName(appName);
                    if (wanted.isEmpty()) {
                        appStatus(false, "اذكر اسم التطبيق الذي تريد فتحه");
                        return;
                    }
                    PackageManager pm = activity.getPackageManager();

                    String[][] known = {
                        {"youtube", "يوتيوب", "يوتيب"},
                        {"chrome", "كروم", "جوجل كروم"},
                        {"whatsapp", "واتساب", "واتس اب"},
                        {"facebook", "فيسبوك", "فايسبوك"},
                        {"camera", "كاميرا", "الكاميرا"},
                        {"settings", "اعدادات", "الاعدادات", "بارامتر"},
                        {"maps", "خرائط", "الخريطة", "خرائط جوجل"}
                    };
                    String[] packages = {
                        "com.google.android.youtube", "com.android.chrome", "com.whatsapp",
                        "com.facebook.katana", "com.sec.android.app.camera",
                        "com.android.settings", "com.google.android.apps.maps"
                    };
                    for (int i = 0; i < known.length; i++) {
                        boolean match = false;
                        for (String alias : known[i]) {
                            String a = normalizeAppName(alias);
                            if (wanted.equals(a) || wanted.contains(a) || a.contains(wanted)) {
                                match = true;
                                break;
                            }
                        }
                        if (match) {
                            Intent launch = pm.getLaunchIntentForPackage(packages[i]);
                            if (launch != null) {
                                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                activity.startActivity(launch);
                                appStatus(true, "تم فتح " + appName);
                                return;
                            }
                        }
                    }

                    Intent query = new Intent(Intent.ACTION_MAIN);
                    query.addCategory(Intent.CATEGORY_LAUNCHER);
                    List<ResolveInfo> apps = pm.queryIntentActivities(query, PackageManager.MATCH_ALL);
                    ResolveInfo best = null;
                    int bestScore = 0;
                    for (ResolveInfo info : apps) {
                        if (info.activityInfo == null) continue;
                        String label = normalizeAppName(info.loadLabel(pm).toString());
                        int score = appMatchScore(wanted, label);
                        if (score > bestScore) {
                            bestScore = score;
                            best = info;
                        }
                    }
                    if (best != null && bestScore >= 40) {
                        Intent launch = new Intent(Intent.ACTION_MAIN);
                        launch.addCategory(Intent.CATEGORY_LAUNCHER);
                        launch.setClassName(best.activityInfo.packageName, best.activityInfo.name);
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(launch);
                        appStatus(true, "تم فتح " + best.loadLabel(pm));
                    } else {
                        appStatus(false, "لم أجد تطبيقاً مطابقاً للاسم: " + appName);
                    }
                } catch (Exception e) {
                    appStatus(false, "تعذر فتح التطبيق: " + e.getClass().getSimpleName());
                }
            });
        }

        private void appStatus(boolean success, String message) {
            String safe = JSONObject.quote(message);
            webView.post(() -> webView.evaluateJavascript(
                "if(window.ArtherAppResult){window.ArtherAppResult(" + success + "," + safe + ");}",
                null
            ));
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
        }

        @JavascriptInterface
        public void typeText(String text) {
            if (text == null) {
                appStatus(false, "لم يصل نص للكتابة");
                return;
            }
            activity.runOnUiThread(() -> {
                boolean success = ArtherAccessibilityService.perform("TYPE:" + text);
                appStatus(success,
                    success ? "تم إدخال النص في الحقل المحدد" :
                    "تعذرت الكتابة. افتح حقلاً قابلاً للتحرير وفعّل خدمة إمكانية الوصول");
            });
        }



        @JavascriptInterface
        public void requestBrightnessPermission() {
            activity.runOnUiThread(() -> {
                try {
                    if (Settings.System.canWrite(activity)) {
                        appStatus(true, "إذن تعديل السطوع ممنوح بالفعل");
                        getCapabilities();
                        return;
                    }
                    Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(intent);
                } catch (Exception e) { appStatus(false, "تعذر فتح إذن تعديل إعدادات النظام"); }
            });
        }

        @JavascriptInterface
        public void showAlarms() {
            activity.runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
                    if (intent.resolveActivity(activity.getPackageManager()) != null) activity.startActivity(intent);
                    else appStatus(false, "لا يوجد تطبيق منبه متاح");
                } catch (Exception e) { appStatus(false, "تعذر فتح المنبه"); }
            });
        }

        @JavascriptInterface
        public void openCamera() {
            activity.runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                    if (intent.resolveActivity(activity.getPackageManager()) != null)
                        activity.startActivity(intent);
                    else appStatus(false, "لم أجد تطبيق كاميرا متاحاً");
                } catch (Exception e) { appStatus(false, "تعذر فتح الكاميرا"); }
            });
        }

        @JavascriptInterface
        public void openSettings(String page) {
            activity.runOnUiThread(() -> {
                String key = page == null ? "" : page.trim().toLowerCase(Locale.ROOT);
                String action;
                switch (key) {
                    case "wifi": action = Settings.ACTION_WIFI_SETTINGS; break;
                    case "bluetooth": action = Settings.ACTION_BLUETOOTH_SETTINGS; break;
                    case "display": action = Settings.ACTION_DISPLAY_SETTINGS; break;
                    case "sound": action = Settings.ACTION_SOUND_SETTINGS; break;
                    case "location": action = Settings.ACTION_LOCATION_SOURCE_SETTINGS; break;
                    case "battery": action = Settings.ACTION_BATTERY_SAVER_SETTINGS; break;
                    case "apps": action = Settings.ACTION_APPLICATION_SETTINGS; break;
                    default: action = Settings.ACTION_SETTINGS; break;
                }
                try { activity.startActivity(new Intent(action)); }
                catch (Exception e) { appStatus(false, "تعذر فتح صفحة الإعدادات المطلوبة"); }
            });
        }

        @JavascriptInterface
        public void openWeb(String address) {
            activity.runOnUiThread(() -> {
                try {
                    String value = address == null ? "" : address.trim();
                    if (value.isEmpty()) { appStatus(false, "اذكر عنوان الموقع"); return; }
                    if (!value.matches("(?i)^https?://.*")) value = "https://" + value;
                    Uri uri = Uri.parse(value);
                    if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                        appStatus(false, "نوع الرابط غير مسموح"); return;
                    }
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    appStatus(true, "فتحت الرابط في المتصفح");
                } catch (Exception e) { appStatus(false, "تعذر فتح الموقع"); }
            });
        }

        @JavascriptInterface
        public void openMap(String query) {
            activity.runOnUiThread(() -> {
                try {
                    String value = query == null ? "" : query.trim();
                    if (value.isEmpty()) { appStatus(false, "حدد المكان الذي تريد البحث عنه"); return; }
                    Uri uri = Uri.parse("geo:0,0?q=" + Uri.encode(value));
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    if (intent.resolveActivity(activity.getPackageManager()) != null) activity.startActivity(intent);
                    else activity.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(value))));
                    appStatus(true, "فتحت الخرائط للبحث عن " + value);
                } catch (Exception e) { appStatus(false, "تعذر فتح الخرائط"); }
            });
        }

        @JavascriptInterface
        public void dialPhone(String number) {
            activity.runOnUiThread(() -> {
                try {
                    String value = number == null ? "" : number.replaceAll("[^+0-9]", "");
                    if (value.length() < 6) { appStatus(false, "رقم الهاتف غير مكتمل"); return; }
                    activity.startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + value)));
                    appStatus(true, "فتحت لوحة الاتصال؛ راجع الرقم واضغط اتصال بنفسك");
                } catch (Exception e) { appStatus(false, "تعذر فتح لوحة الاتصال"); }
            });
        }

        @JavascriptInterface
        public void composeSms(String number, String message) {
            activity.runOnUiThread(() -> {
                try {
                    String value = number == null ? "" : number.replaceAll("[^+0-9]", "");
                    if (value.length() < 6) { appStatus(false, "رقم الهاتف غير مكتمل"); return; }
                    Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + value));
                    intent.putExtra("sms_body", message == null ? "" : message);
                    activity.startActivity(intent);
                    appStatus(true, "جهزت الرسالة؛ راجعها وأرسلها بنفسك");
                } catch (Exception e) { appStatus(false, "تعذر فتح تطبيق الرسائل"); }
            });
        }

        @JavascriptInterface
        public void createAlarm(String hourValue, String minuteValue, String label) {
            activity.runOnUiThread(() -> {
                try {
                    int hour = Integer.parseInt(hourValue);
                    int minute = Integer.parseInt(minuteValue);
                    Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, label == null ? "ARTHER" : label)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, false);
                    if (intent.resolveActivity(activity.getPackageManager()) != null) activity.startActivity(intent);
                    else appStatus(false, "لا يوجد تطبيق منبه متاح");
                } catch (Exception e) { appStatus(false, "تعذر إنشاء المنبه؛ تحقق من الوقت"); }
            });
        }

        @JavascriptInterface
        public void createTimer(String secondsValue) {
            activity.runOnUiThread(() -> {
                try {
                    int seconds = Integer.parseInt(secondsValue);
                    if (seconds < 1 || seconds > 86400) { appStatus(false, "مدة المؤقت يجب أن تكون بين ثانية و24 ساعة"); return; }
                    Intent intent = new Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, "ARTHER")
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, false);
                    if (intent.resolveActivity(activity.getPackageManager()) != null) activity.startActivity(intent);
                    else appStatus(false, "لا يوجد تطبيق مؤقت متاح");
                } catch (Exception e) { appStatus(false, "تعذر تشغيل المؤقت"); }
            });
        }

        @JavascriptInterface
        public void setBrightness(String percentValue) {
            activity.runOnUiThread(() -> {
                try {
                    int percent = Math.max(1, Math.min(100, Integer.parseInt(percentValue)));
                    if (!Settings.System.canWrite(activity)) {
                        Intent permission = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(permission);
                        appStatus(false, "اسمح لـ ARTHER بتعديل إعدادات النظام ثم أعد أمر السطوع");
                        return;
                    }
                    int value = Math.round(percent * 255f / 100f);
                    Settings.System.putInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    boolean changed = Settings.System.putInt(activity.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, value);
                    appStatus(changed, changed ? "تم ضبط سطوع الشاشة على " + percent + "%" :
                        "تعذر تغيير سطوع الشاشة");
                } catch (Exception e) { appStatus(false, "تعذر ضبط السطوع"); }
            });
        }

        @JavascriptInterface
        public void createCalendarEvent(String title, long startMillis, long endMillis, String description) {
            activity.runOnUiThread(() -> {
                try {
                    if (endMillis <= startMillis) { appStatus(false, "وقت نهاية الموعد يجب أن يكون بعد بدايته"); return; }
                    Intent intent = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, title == null ? "موعد ARTHER" : title)
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
                        .putExtra(CalendarContract.Events.DESCRIPTION, description == null ? "" : description);
                    activity.startActivity(intent);
                    appStatus(true, "فتحت التقويم لمراجعة الموعد وحفظه");
                } catch (Exception e) { appStatus(false, "تعذر فتح التقويم"); }
            });
        }

        @JavascriptInterface
        public void shareText(String value) {
            activity.runOnUiThread(() -> {
                try {
                    if (value == null || value.trim().isEmpty()) { appStatus(false, "لا يوجد نص للمشاركة"); return; }
                    Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, value);
                    activity.startActivity(Intent.createChooser(intent, "مشاركة عبر"));
                } catch (Exception e) { appStatus(false, "تعذرت مشاركة النص"); }
            });
        }

        private void sendStatus(String message) {
            String safe = JSONObject.quote(message);
            webView.post(() -> webView.evaluateJavascript(
                "if(window.ArtherListeningStatus){window.ArtherListeningStatus(" + safe + ");}" +
                "else if(document.getElementById('voiceStatus')){document.getElementById('voiceStatus').textContent=" + safe + ";}",
                null
            ));
        }
    }

    @Override
    public void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        if (artherBridge != null) {
            if (artherBridge.recognizer != null) artherBridge.recognizer.destroy();
            if (artherBridge.tts != null) artherBridge.tts.shutdown();
        }
        super.onDestroy();
    }
}