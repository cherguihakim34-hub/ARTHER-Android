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

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends BridgeActivity {

    private ArtherBridge artherBridge;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView webView = getBridge().getWebView();
        artherBridge = new ArtherBridge(this, webView);
        webView.addJavascriptInterface(artherBridge, "ArtherNative");
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode, String[] permissions, int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (artherBridge == null) return;

        if (requestCode == 701 &&
            grantResults.length > 0 &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            artherBridge.beginListening();
        }

        if (requestCode == 702) {
            if (grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                artherBridge.applyPendingFlash();
            } else {
                artherBridge.reportFlash(false, "لم تسمح بإذن الكاميرا");
            }
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
        public void startListening() {
            activity.runOnUiThread(() -> {
                if (activity.checkSelfPermission(
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(
                        new String[]{Manifest.permission.RECORD_AUDIO}, 701
                    );
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
                    @Override
                    public void onReadyForSpeech(Bundle params) {
                        sendStatus("أنا أستمع إليك...");
                    }

                    @Override public void onBeginningOfSpeech() {}
                    @Override public void onRmsChanged(float value) {}
                    @Override public void onBufferReceived(byte[] buffer) {}

                    @Override
                    public void onEndOfSpeech() {
                        sendStatus("أحلل كلامك...");
                    }

                    @Override
                    public void onError(int error) {
                        sendStatus("تعذر سماع الأمر. حاول مجدداً.");
                    }

                    @Override
                    public void onResults(Bundle results) {
                        ArrayList<String> matches =
                            results.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            );

                        if (matches != null && !matches.isEmpty()) {
                            String safe = JSONObject.quote(matches.get(0));
                            webView.post(() ->
                                webView.evaluateJavascript(
                                    "window.ArtherVoiceResult(" + safe + ")",
                                    null
                                )
                            );
                        }
                    }

                    @Override public void onPartialResults(Bundle b) {}
                    @Override public void onEvent(int e, Bundle b) {}
                });

                Intent intent =
                    new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);

                intent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                );
                intent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    "ar-DZ"
                );
                intent.putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS, 1
                );

                recognizer.startListening(intent);
            });
        }

        @JavascriptInterface
        public void setFlash(String state) {
            activity.runOnUiThread(() -> {
                String value = state == null
                    ? ""
                    : state.trim().toUpperCase(Locale.ROOT);

                if (!value.equals("ON") && !value.equals("OFF")) {
                    reportFlash(false, "أمر الفلاش غير صحيح");
                    return;
                }

                pendingFlash = value;

                if (activity.checkSelfPermission(
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED) {
                    activity.requestPermissions(
                        new String[]{Manifest.permission.CAMERA}, 702
                    );
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
                    CameraManager camera = (CameraManager)
                        activity.getSystemService(
                            android.content.Context.CAMERA_SERVICE
                        );

                    String selectedId = null;

                    for (String id : camera.getCameraIdList()) {
                        CameraCharacteristics characteristics =
                            camera.getCameraCharacteristics(id);

                        Boolean hasFlash = characteristics.get(
                            CameraCharacteristics.FLASH_INFO_AVAILABLE
                        );

                        Integer facing = characteristics.get(
                            CameraCharacteristics.LENS_FACING
                        );

                        if (Boolean.TRUE.equals(hasFlash) &&
                            (facing == null ||
                             facing == CameraCharacteristics.LENS_FACING_BACK)) {
                            selectedId = id;
                            break;
                        }
                    }

                    if (selectedId == null) {
                        reportFlash(false, "لا يوجد فلاش متاح في الجهاز");
                        return;
                    }

                    camera.setTorchMode(
                        selectedId, requested.equals("ON")
                    );

                    reportFlash(
                        true,
                        requested.equals("ON")
                            ? "تم تشغيل الفلاش"
                            : "تم إطفاء الفلاش"
                    );

                } catch (Exception e) {
                    reportFlash(false, "تعذر التحكم في الفلاش");
                } finally {
                    pendingFlash = null;
                }
            });
        }

        void reportFlash(boolean success, String message) {
            String safe = JSONObject.quote(message);
            String script =
                "if(window.ArtherFlashResult){" +
                "window.ArtherFlashResult(" + success + "," + safe + ");}";

            webView.post(() ->
                webView.evaluateJavascript(script, null)
            );
        }

        @JavascriptInterface
        public void speak(String text) {
            activity.runOnUiThread(() -> {
                if (tts != null && text != null) {
                    tts.speak(
                        text, TextToSpeech.QUEUE_FLUSH,
                        null, "ARTHER_REPLY"
                    );
                }
            });
        }

        @JavascriptInterface
        public void stopSpeaking() {
            activity.runOnUiThread(() -> {
                if (tts != null) tts.stop();
            });
        }

        @JavascriptInterface
        public void performAction(String action) {
            activity.runOnUiThread(() -> {
                boolean success =
                    ArtherAccessibilityService.perform(action);

                if (!success) {
                    Toast.makeText(
                        activity,
                        "فعّل صلاحية إمكانية الوصول لـ ARTHER",
                        Toast.LENGTH_LONG
                    ).show();
                }
            });
        }

        @JavascriptInterface
        public void openApp(String appName) {
            activity.runOnUiThread(() -> {
                try {
                    if (appName == null || appName.trim().isEmpty()) return;

                    String wanted = appName.trim().toLowerCase(Locale.ROOT);
                    PackageManager pm = activity.getPackageManager();

                    String[] mapAliases = {
                        "خرائط", "الخريطة", "الخرائط",
                        "maps", "map", "google maps", "cartes"
                    };

                    for (String alias : mapAliases) {
                        if (wanted.contains(alias)) {
                            Intent mapsIntent = new Intent(
                                Intent.ACTION_VIEW,
                                android.net.Uri.parse("geo:0,0")
                            );

                            if (mapsIntent.resolveActivity(pm) != null) {
                                activity.startActivity(mapsIntent);
                            } else {
                                Toast.makeText(activity,
                                    "لا يوجد تطبيق خرائط متاح",
                                    Toast.LENGTH_LONG).show();
                            }
                            return;
                        }
                    }

                    String[][] aliases = {
                        {"youtube", "يوتيوب"},
                        {"chrome", "كروم"},
                        {"whatsapp", "واتساب"},
                        {"facebook", "فيسبوك"},
                        {"camera", "الكاميرا"},
                        {"settings", "الإعدادات"}
                    };

                    String[] packages = {
                        "com.google.android.youtube",
                        "com.android.chrome",
                        "com.whatsapp",
                        "com.facebook.katana",
                        "com.android.camera",
                        "com.android.settings"
                    };

                    for (int i = 0; i < aliases.length; i++) {
                        for (String alias : aliases[i]) {
                            if (wanted.contains(alias)) {
                                Intent launch =
                                    pm.getLaunchIntentForPackage(packages[i]);

                                if (launch != null) {
                                    activity.startActivity(launch);
                                    return;
                                }
                            }
                        }
                    }

                    Intent query = new Intent(Intent.ACTION_MAIN);
                    query.addCategory(Intent.CATEGORY_LAUNCHER);

                    for (ResolveInfo info :
                            pm.queryIntentActivities(query, 0)) {

                        String label = info.loadLabel(pm)
                            .toString().toLowerCase(Locale.ROOT);

                        if (label.contains(wanted)) {
                            Intent launch = pm.getLaunchIntentForPackage(
                                info.activityInfo.packageName
                            );

                            if (launch != null) {
                                activity.startActivity(launch);
                                return;
                            }
                        }
                    }

                    Toast.makeText(activity,
                        "لم أجد التطبيق: " + appName,
                        Toast.LENGTH_LONG).show();

                } catch (Exception e) {
                    Toast.makeText(activity,
                        "تعذر فتح التطبيق",
                        Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void typeText(String text) {
            if (text == null) return;

            activity.runOnUiThread(() ->
                ArtherAccessibilityService.perform("TYPE:" + text)
            );
        }

        private void sendStatus(String message) {
            String safe = JSONObject.quote(message);

            webView.post(() ->
                webView.evaluateJavascript(
                    "document.getElementById('voiceStatus').textContent=" +
                    safe, null
                )
            );
        }
    }

    @Override
    public void onDestroy() {
        if (artherBridge != null) {
            if (artherBridge.recognizer != null) {
                artherBridge.recognizer.destroy();
            }
            if (artherBridge.tts != null) {
                artherBridge.tts.shutdown();
            }
        }
        super.onDestroy();
    }
}
