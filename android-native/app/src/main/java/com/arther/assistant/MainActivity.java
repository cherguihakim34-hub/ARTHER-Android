
package com.arther.assistant;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
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
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) {
        super.onRequestPermissionsResult(
            requestCode, permissions, grantResults
        );

        if (requestCode == 701 &&
            grantResults.length > 0 &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            artherBridge.beginListening();
        }
    }

    public static class ArtherBridge {

        private final MainActivity activity;
        private final WebView webView;

        private SpeechRecognizer recognizer;
        private TextToSpeech tts;

        ArtherBridge(MainActivity activity, WebView webView) {
            this.activity = activity;
            this.webView = webView;

            tts = new TextToSpeech(activity, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    tts.setLanguage(
                        Locale.forLanguageTag("ar-SA")
                    );
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
                        new String[]{
                            Manifest.permission.RECORD_AUDIO
                        }, 701
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

                if (recognizer != null) {
                    recognizer.destroy();
                }

                recognizer =
                    SpeechRecognizer.createSpeechRecognizer(activity);

                recognizer.setRecognitionListener(
                    new RecognitionListener() {

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
                                String safe =
                                    JSONObject.quote(matches.get(0));

                                webView.post(() ->
                                    webView.evaluateJavascript(
                                        "window.ArtherVoiceResult(" +
                                        safe + ")", null
                                    )
                                );
                            }
                        }

                        @Override public void onPartialResults(Bundle b) {}
                        @Override public void onEvent(int e, Bundle b) {}
                    }
                );

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
        public void speak(String text) {
            activity.runOnUiThread(() -> {
                if (tts != null) {
                    tts.speak(
                        text,
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "ARTHER_REPLY"
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
                    String wanted =
                        appName.toLowerCase(Locale.ROOT);

                    PackageManager pm =
                        activity.getPackageManager();

                    for (android.content.pm.ApplicationInfo app :
                        pm.getInstalledApplications(0)) {

                        String label =
                            pm.getApplicationLabel(app)
                                .toString()
                                .toLowerCase(Locale.ROOT);

                        if (label.contains(wanted)) {
                            Intent launch =
                                pm.getLaunchIntentForPackage(
                                    app.packageName
                                );

                            if (launch != null) {
                                activity.startActivity(launch);
                                return;
                            }
                        }
                    }

                    Toast.makeText(
                        activity,
                        "لم أجد التطبيق",
                        Toast.LENGTH_SHORT
                    ).show();

                } catch (Exception e) {
                    Toast.makeText(
                        activity,
                        "تعذر فتح التطبيق",
                        Toast.LENGTH_SHORT
                    ).show();
                }
            });
        }

        @JavascriptInterface
        public void typeText(String text) {
            activity.runOnUiThread(() -> {
                ArtherAccessibilityService.perform("TYPE:" + text);
            });
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
