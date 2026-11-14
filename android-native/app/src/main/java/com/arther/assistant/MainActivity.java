package com.arther.assistant;

import android.Manifest;
import android.content.Intent;
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
                    tts.setLanguage(Locale.forLanguageTag("ar-DZ"));
                    tts.setSpeechRate(0.92f);
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

        @JavascriptInterface
        public void startListening() {
            activity.runOnUiThread(() -> {
                if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
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
                if (recognizer != null) recognizer.destroy();
                recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
                recognizer.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(Bundle params) { sendStatus("أنا أستمع إليك..."); }
                    @Override public void onBeginningOfSpeech() {}
                    @Override public void onRmsChanged(float value) {}
                    @Override public void onBufferReceived(byte[] buffer) {}
                    @Override public void onEndOfSpeech() { sendStatus("أحلل كلامك..."); }
                    @Override public void onError(int error) { sendStatus("تعذر سماع الأمر. حاول مجدداً."); }
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
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-DZ");
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                recognizer.startListening(intent);
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

        private void sendStatus(String message) {
            String safe = JSONObject.quote(message);
            webView.post(() -> webView.evaluateJavascript(
                "document.getElementById('voiceStatus').textContent=" + safe, null
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