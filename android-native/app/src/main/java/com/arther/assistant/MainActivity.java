package com.arther.assistant;

import android.Manifest;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.net.Uri;
import android.location.Location;
import android.location.LocationManager;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import com.getcapacitor.BridgeActivity;
import rikka.shizuku.Shizuku;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.Collections;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends BridgeActivity {
    private ArtherBridge artherBridge;
    private boolean voiceReceiverRegistered = false;
    private final BroadcastReceiver voiceReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String type = intent.getStringExtra(ArtherWakeService.EXTRA_EVENT);
            String value = intent.getStringExtra(ArtherWakeService.EXTRA_TEXT);
            String safe = JSONObject.quote(value == null ? "" : value);
            String script = "result".equals(type)
                ? "if(window.ArtherVoiceResult){window.ArtherVoiceResult(" + safe + ");}"
                : "if(window.ArtherListeningStatus){window.ArtherListeningStatus(" + safe + ");}";
            WebView view = getBridge() == null ? null : getBridge().getWebView();
            if (view != null) view.evaluateJavascript(script, null);
        }
    };
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
        IntentFilter voiceFilter = new IntentFilter(ArtherWakeService.ACTION_EVENT);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(voiceReceiver, voiceFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(voiceReceiver, voiceFilter);
        }
        voiceReceiverRegistered = true;
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
        if (requestCode == 701) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                artherBridge.startListening();
            } else {
                artherBridge.sendStatus("لم تمنح إذن الميكروفون؛ لن يبدأ الاستماع حتى تسمح به.");
            }
        }
        if (requestCode == 703) {
            boolean locationGranted = false;
            for (int result : grantResults) {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    locationGranted = true;
                    break;
                }
            }
            if (locationGranted) artherBridge.beginLocationRequest();
            else artherBridge.sendLocationError("لم يتم منح إذن الموقع. اسمح بالموقع التقريبي أو الدقيق ثم أعد المحاولة.");
        }
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
        private TextToSpeech tts;
        private String pendingFlash;
        private String speechLanguageOverride = "auto";
        private boolean ttsReady = false;
        private String pendingSpeech;

        ArtherBridge(MainActivity activity, WebView webView) {
            this.activity = activity;
            this.webView = webView;
            tts = new TextToSpeech(activity, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true;
                    tts.setLanguage(Locale.forLanguageTag(preferredLanguageTag()));
                    tts.setSpeechRate(0.92f);
                    tts.setPitch(0.82f);
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String utteranceId) {}
                        @Override public void onDone(String utteranceId) {
                            resumeVoiceService();
                            webView.post(() -> webView.evaluateJavascript(
                                "if(window.ArtherSpeechFinished){window.ArtherSpeechFinished();}", null
                            ));
                        }
                        @Override public void onError(String utteranceId) {
                            resumeVoiceService();
                            webView.post(() -> webView.evaluateJavascript(
                                "if(window.ArtherSpeechFinished){window.ArtherSpeechFinished();}", null
                            ));
                        }
                    });
                    if (pendingSpeech != null && !pendingSpeech.trim().isEmpty()) {
                        String queued = pendingSpeech;
                        pendingSpeech = null;
                        pauseVoiceService();
                        tts.speak(queued, TextToSpeech.QUEUE_FLUSH, null, "ARTHER_REPLY");
                    }
                }
            });
        }


        @JavascriptInterface
        public void getCapabilities() {
            try {
                JSONObject c = new JSONObject();
                c.put("microphone", activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
                c.put("camera", activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED);
                boolean preciseLocation = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                boolean approximateLocation = activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                c.put("location", preciseLocation || approximateLocation);
                c.put("locationPrecise", preciseLocation);
                c.put("writeSettings", Settings.System.canWrite(activity));
                android.os.PowerManager power = (android.os.PowerManager)
                    activity.getSystemService(android.content.Context.POWER_SERVICE);
                c.put("batteryOptimizationIgnored", android.os.Build.VERSION.SDK_INT < 23 ||
                    (power != null && power.isIgnoringBatteryOptimizations(activity.getPackageName())));
                c.put("voiceServiceRunning", ArtherWakeService.isRunning);
                c.put("speechRecognitionActive", ArtherWakeService.isListening);
                c.put("speechRecognitionAvailable", SpeechRecognizer.isRecognitionAvailable(activity));
                c.put("textToSpeechReady", ttsReady);
                c.put("accessibility", ArtherAccessibilityService.isEnabled());
                boolean running = Shizuku.pingBinder();
                c.put("shizukuRunning", running);
                c.put("shizukuGranted", running && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED);
                c.put("shizukuUid", running ? Shizuku.getUid() : -1);
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
            int uid = running ? Shizuku.getUid() : -1;
            String mode = uid == 0 ? "صلاحيات Root" : (uid == 2000 ? "صلاحيات ADB / Shell" : "صلاحيات Shizuku");
            reportShizuku(granted, !running ? "Shizuku غير مشغّل" :
                (granted ? "Shizuku متصل ومصرّح — " + mode : "Shizuku يعمل، بانتظار منح الصلاحية — " + mode));
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
            if ("fr".equalsIgnoreCase(speechLanguageOverride)) return "fr-FR";
            if ("en".equalsIgnoreCase(speechLanguageOverride)) return "en-US";
            if ("ar".equalsIgnoreCase(speechLanguageOverride)) return "ar-SA";
            String language = Locale.getDefault().getLanguage();
            if ("fr".equalsIgnoreCase(language)) return "fr-FR";
            if ("en".equalsIgnoreCase(language)) return "en-US";
            // Use a widely supported Arabic locale; ar-DZ is not available in every recognition service.
            return "ar-SA";
        }

        @JavascriptInterface
        public void setSpeechLanguage(String language) {
            String value = language == null ? "auto" : language.trim().toLowerCase(Locale.ROOT);
            if (!"ar".equals(value) && !"fr".equals(value) && !"en".equals(value)) value = "auto";
            final String selected = value;
            activity.runOnUiThread(() -> {
                speechLanguageOverride = selected;
                if (ArtherWakeService.isRunning) {
                    try {
                        Intent service = new Intent(activity, ArtherWakeService.class);
                        service.putExtra(ArtherWakeService.EXTRA_LANGUAGE, preferredLanguageTag());
                        if (android.os.Build.VERSION.SDK_INT >= 26) activity.startForegroundService(service);
                        else activity.startService(service);
                    } catch (Exception ignored) {}
                }
                if (tts != null && ttsReady) {
                    tts.setPitch(0.82f);
                    int result = tts.setLanguage(Locale.forLanguageTag(preferredLanguageTag()));
                    sendStatus(result == TextToSpeech.LANG_MISSING_DATA ||
                        result == TextToSpeech.LANG_NOT_SUPPORTED
                        ? "حزمة صوت اللغة المختارة غير متاحة على الهاتف"
                        : "تم اختيار لغة الصوت: " + selected);
                } else {
                    sendStatus("تم حفظ اختيار اللغة، جارٍ تهيئة الصوت");
                }
            });
        }

        @JavascriptInterface
        public void startListening() {
            activity.runOnUiThread(() -> {
                if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    sendStatus("أحتاج إذن الميكروفون. اسمح لـ ARTHER باستخدام الميكروفون.");
                    activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 701); return;
                }
                try {
                    Intent service = new Intent(activity, ArtherWakeService.class);
                    service.putExtra(ArtherWakeService.EXTRA_LANGUAGE, preferredLanguageTag());
                    if (android.os.Build.VERSION.SDK_INT >= 26) activity.startForegroundService(service); else activity.startService(service);
                    sendStatus("جارٍ تشغيل الاستماع الصوتي...");
                } catch (Exception error) { sendStatus("تعذر تشغيل خدمة الاستماع: " + error.getClass().getSimpleName()); }
            });
        }

        @JavascriptInterface
        public boolean isVoiceServiceRunning() { return ArtherWakeService.isRunning && ArtherWakeService.isListening; }

        private void pauseVoiceService() {
            try { if (ArtherWakeService.isRunning) {
                Intent intent = new Intent(activity, ArtherWakeService.class); intent.setAction(ArtherWakeService.ACTION_PAUSE); activity.startService(intent);
            }} catch (Exception ignored) {}
        }
        private void resumeVoiceService() {
            try { if (ArtherWakeService.isRunning) {
                Intent intent = new Intent(activity, ArtherWakeService.class); intent.setAction(ArtherWakeService.ACTION_RESUME); activity.startService(intent);
            }} catch (Exception ignored) {}
        }
        @JavascriptInterface
        public void pauseListening() { activity.runOnUiThread(this::pauseVoiceService); }

        @JavascriptInterface
        public void resumeListening() { activity.runOnUiThread(this::resumeVoiceService); }
        @JavascriptInterface
        public void stopListening() {
            activity.runOnUiThread(() -> {
                activity.stopService(new Intent(activity, ArtherWakeService.class)); getCapabilities(); sendStatus("تم إيقاف الاستماع الصوتي");
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
                if (text == null || text.trim().isEmpty()) return;
                if (tts != null && ttsReady) {
                    pendingSpeech = null;
                    pauseVoiceService();
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ARTHER_REPLY");
                } else {
                    pendingSpeech = text;
                }
            });
        }

        @JavascriptInterface
        public void stopSpeaking() {
            activity.runOnUiThread(() -> { pendingSpeech = null; if (tts != null) tts.stop(); });
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
        public String getInstalledApps() {
            try {
                PackageManager pm = activity.getPackageManager();
                Intent query = new Intent(Intent.ACTION_MAIN);
                query.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> matches = pm.queryIntentActivities(query, PackageManager.MATCH_ALL);
                Set<String> labels = new HashSet<>();
                for (ResolveInfo info : matches) {
                    if (info == null || info.activityInfo == null) continue;
                    CharSequence label = info.loadLabel(pm);
                    if (label != null && !label.toString().trim().isEmpty()) {
                        labels.add(label.toString().trim());
                    }
                }
                List<String> sorted = new ArrayList<>(labels);
                Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
                JSONArray result = new JSONArray();
                for (int i = 0; i < Math.min(sorted.size(), 120); i++) {
                    result.put(sorted.get(i));
                }
                return result.toString();
            } catch (Exception error) {
                return "[]";
            }
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
                        {"maps", "خرائط", "الخريطة", "خرائط جوجل"},
                        {"phone", "هاتف", "اتصال", "dialer", "telephone"},
                        {"messages", "رسائل", "الرسائل", "sms", "messagerie"},
                        {"gallery", "معرض", "الصور", "photos", "galerie"},
                        {"clock", "ساعة", "الساعة", "horloge", "alarm"},
                        {"files", "ملفاتي", "الملفات", "my files", "fichiers"},
                        {"calculator", "آلة حاسبة", "الحاسبة", "calculatrice"},
                        {"play store", "متجر بلاي", "بلاي ستور", "playstore"},
                        {"gmail", "جيميل", "بريد جوجل", "mail"}
                    };
                    String[] packages = {
                        "com.google.android.youtube", "com.android.chrome", "com.whatsapp",
                        "com.facebook.katana", "com.sec.android.app.camera",
                        "com.android.settings", "com.google.android.apps.maps",
                        "com.samsung.android.dialer", "com.samsung.android.messaging",
                        "com.sec.android.gallery3d", "com.sec.android.app.clockpackage",
                        "com.sec.android.app.myfiles", "com.sec.android.app.popupcalculator",
                        "com.android.vending", "com.google.android.gm"
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
        public void requestBackgroundPermission() {
            activity.runOnUiThread(() -> {
                try {
                    if (android.os.Build.VERSION.SDK_INT < 23) {
                        appStatus(true, "لا تنطبق قيود تحسين البطارية على هذا الإصدار");
                        return;
                    }
                    android.os.PowerManager power = (android.os.PowerManager)
                        activity.getSystemService(android.content.Context.POWER_SERVICE);
                    if (power != null && power.isIgnoringBatteryOptimizations(activity.getPackageName())) {
                        appStatus(true, "السماح بالعمل في الخلفية مفعّل بالفعل");
                        getCapabilities();
                        return;
                    }
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(intent);
                } catch (Exception error) {
                    appStatus(false, "تعذر فتح إعدادات تحسين البطارية؛ اضبط بطارية ARTHER على غير مقيّد يدوياً");
                }
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
        public void getCurrentLocation() {
            activity.runOnUiThread(() -> {
                boolean fine = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                boolean coarse = activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                if (!fine && !coarse) {
                    activity.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 703);
                    return;
                }
                beginLocationRequest();
            });
        }

        void beginLocationRequest() {
            activity.runOnUiThread(() -> {
                try {
                    LocationManager manager = (LocationManager) activity.getSystemService(android.content.Context.LOCATION_SERVICE);
                    List<String> providers = new ArrayList<>();
                    if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) providers.add(LocationManager.GPS_PROVIDER);
                    if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) providers.add(LocationManager.NETWORK_PROVIDER);
                    if (providers.isEmpty()) {
                        sendLocationError("خدمة الموقع مغلقة. فعّل الموقع من إعدادات الهاتف ثم أعد المحاولة.");
                        return;
                    }
                    if (android.os.Build.VERSION.SDK_INT < 30) {
                        sendLocationError("يلزم تحديث Android للحصول على موقع مباشر موثوق. لن أرسل موقعاً قديماً محفوظاً.");
                        return;
                    }
                    requestFreshLocation(manager, providers, 0);
                } catch (SecurityException e) {
                    sendLocationError("اسمح لـ ARTHER بالوصول إلى موقعك الدقيق من إعدادات التطبيقات.");
                } catch (Exception e) {
                    sendLocationError("خطأ GPS: " + e.getClass().getSimpleName());
                }
            });
        }

        private void requestFreshLocation(LocationManager manager, List<String> providers, int index) {
            if (index >= providers.size()) {
                sendLocationError("لم أحصل على تثبيت GPS حديث ودقيق. فعّل الموقع الدقيق، وانتظر 20-30 ثانية قرب نافذة أو في مكان مفتوح، ثم أعد المحاولة. لم أستخدم موقعاً قديماً.");
                return;
            }
            String provider = providers.get(index);
            CancellationSignal cancellation = new CancellationSignal();
            Handler handler = new Handler(Looper.getMainLooper());
            boolean[] completed = {false};
            Runnable timeout = () -> {
                if (completed[0]) return;
                completed[0] = true;
                cancellation.cancel();
                requestFreshLocation(manager, providers, index + 1);
            };
            handler.postDelayed(timeout, 12000L);
            try {
                manager.getCurrentLocation(provider, cancellation, activity.getMainExecutor(), location -> {
                    if (completed[0]) return;
                    completed[0] = true;
                    handler.removeCallbacks(timeout);
                    if (location != null) {
                        long ageMs = locationAgeMillis(location);
                        boolean hasAccuracy = location.hasAccuracy();
                        float accuracy = hasAccuracy ? location.getAccuracy() : Float.MAX_VALUE;
                        if (ageMs <= 30000L && hasAccuracy && accuracy <= 100f) {
                            reportLocation(location);
                            return;
                        }
                    }
                    requestFreshLocation(manager, providers, index + 1);
                });
            } catch (SecurityException e) {
                handler.removeCallbacks(timeout);
                if (!completed[0]) {
                    completed[0] = true;
                    sendLocationError("إذن الموقع الدقيق غير ممنوح لـ ARTHER.");
                }
            } catch (Exception e) {
                handler.removeCallbacks(timeout);
                if (!completed[0]) {
                    completed[0] = true;
                    requestFreshLocation(manager, providers, index + 1);
                }
            }
        }
        private long locationAgeMillis(Location location) {
            if (location == null) return Long.MAX_VALUE;
            if (location.getElapsedRealtimeNanos() > 0L) {
                long delta = android.os.SystemClock.elapsedRealtimeNanos() -
                    location.getElapsedRealtimeNanos();
                return delta < 0L ? Long.MAX_VALUE : delta / 1000000L;
            }
            long wallClockDelta = System.currentTimeMillis() - location.getTime();
            return wallClockDelta < 0L ? Long.MAX_VALUE : wallClockDelta;
        }
        private void reportLocation(Location location) {
            try {
                JSONObject data = new JSONObject();
                data.put("ok", true);
                data.put("latitude", location.getLatitude());
                data.put("longitude", location.getLongitude());
                data.put("accuracy_m", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL);
                data.put("provider", location.getProvider());
                data.put("fix_age_ms", locationAgeMillis(location));
                String payload = JSONObject.quote(data.toString());
                webView.post(() -> webView.evaluateJavascript("if(window.ArtherLocationResult){window.ArtherLocationResult(" + payload + ");}", null));
            } catch (Exception ignored) {}
        }

        private void sendLocationError(String message) {
            try {
                JSONObject data = new JSONObject();
                data.put("ok", false);
                data.put("error", message);
                String payload = JSONObject.quote(data.toString());
                webView.post(() -> webView.evaluateJavascript("if(window.ArtherLocationResult){window.ArtherLocationResult(" + payload + ");}", null));
            } catch (Exception ignored) {}
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
        if (voiceReceiverRegistered) {
            try { unregisterReceiver(voiceReceiver); } catch (Exception ignored) {}
            voiceReceiverRegistered = false;
        }
        if (artherBridge != null) {
            if (artherBridge.tts != null) artherBridge.tts.shutdown();
        }
        super.onDestroy();
    }
}