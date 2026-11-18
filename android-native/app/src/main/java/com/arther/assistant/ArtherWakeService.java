package com.arther.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ArtherWakeService extends Service {
    public static final String ACTION_EVENT = "com.arther.assistant.VOICE_EVENT";
    public static final String EXTRA_EVENT = "event";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_LANGUAGE = "language";
    public static final String ACTION_PAUSE = "com.arther.assistant.PAUSE_VOICE";
    public static final String ACTION_RESUME = "com.arther.assistant.RESUME_VOICE";
    private static final String CHANNEL_ID = "arther_voice_service";
    private static final int NOTIFICATION_ID = 11901;

    public static volatile boolean isRunning = false;
    public static volatile boolean isListening = false;
    public static volatile boolean isPaused = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private PowerManager.WakeLock wakeLock;
    private String languageTag = "ar-SA";
    private boolean paused = false;
    private boolean forceDefaultProvider = false;
    private boolean destroyed = false;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PAUSE.equals(action)) {
            paused = true;
            isPaused = true;
            stopRecognition();
            // Do not keep the CPU awake while text-to-speech is playing.
            releaseCpuWakeLock();
            return START_NOT_STICKY;
        }
        if (ACTION_RESUME.equals(action)) {
            paused = false;
            isPaused = false;
            if (isRunning) acquireCpuWakeLock();
            startRecognition();
            return START_NOT_STICKY;
        }
        // A normal start request is an explicit request to resume listening.
        paused = false;
        isPaused = false;
        if (intent != null) {
            String selected = intent.getStringExtra(EXTRA_LANGUAGE);
            if (selected != null) {
                String normalized = selected.trim().toLowerCase(Locale.ROOT);
                String nextLanguage = normalized.startsWith("fr") ? "fr-FR" :
                    (normalized.startsWith("en") ? "en-US" : "ar-SA");
                if (!nextLanguage.equals(languageTag)) {
                    languageTag = nextLanguage;
                    if (recognizer != null) {
                        stopRecognition();
                        startRecognition();
                    }
                }
            }
        }
        try {
            Intent openApp = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 119, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
            Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("ARTHER · الاستعداد الصوتي")
                .setContentText("الاستماع الصوتي يعمل. اضغط لفتح المساعد أو أوقفه من داخله.")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .build();

            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            acquireCpuWakeLock();
            isRunning = true;
            if (!paused) startRecognition();
        } catch (SecurityException | IllegalStateException error) {
            sendEvent("status", "تعذر تشغيل خدمة الميكروفون: " + error.getClass().getSimpleName());
            isRunning = false;
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startRecognition() {
        if (destroyed || paused || !isRunning || recognizer != null) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendEvent("status", "خدمة التعرف على الكلام غير متاحة على الهاتف");
            scheduleRestart(5000);
            return;
        }
        try {
            ComponentName selected = null;
            Intent query = new Intent(android.speech.RecognitionService.SERVICE_INTERFACE);
            List<ResolveInfo> services = getPackageManager().queryIntentServices(query, PackageManager.MATCH_ALL);
            for (ResolveInfo info : services) {
                if (info.serviceInfo == null) continue;
                boolean googleProvider = "com.google.android.googlequicksearchbox"
                    .equals(info.serviceInfo.packageName);
                if ((!forceDefaultProvider && googleProvider) ||
                    (forceDefaultProvider && !googleProvider)) {
                    selected = new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name);
                    break;
                }
            }
            recognizer = selected == null
                ? SpeechRecognizer.createSpeechRecognizer(this)
                : SpeechRecognizer.createSpeechRecognizer(this, selected);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    isListening = true;
                    sendEvent("status", "أنا أستمع إليك...");
                }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float value) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {
                    isListening = false;
                    sendEvent("status", "أحلل كلامك...");
                }
                @Override public void onError(int error) {
                    isListening = false;
                    if (error == 11) forceDefaultProvider = true;
                    sendEvent("status", errorMessage(error));
                    destroyRecognizer();
                    scheduleRestart(error == 7 ? 900 : (error == 11 ? 3500 : 1800));
                }
                @Override public void onResults(Bundle results) {
                    ArrayList<String> matches = results == null ? null :
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String transcript = matches != null && !matches.isEmpty() ? matches.get(0) : "";
                    isListening = false;
                    sendEvent("result", transcript);
                    // The app decides when to resume after processing the transcript.
                    // This prevents recognition restarting during command execution or TTS.
                    destroyRecognizer();
                }
                @Override public void onPartialResults(Bundle partial) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
            Intent speech = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            speech.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            speech.putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag);
            speech.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag);
            speech.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            speech.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            speech.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            recognizer.startListening(speech);
        } catch (Exception error) {
            sendEvent("status", "تعذر بدء التعرف على الصوت: " + error.getClass().getSimpleName());
            destroyRecognizer();
            scheduleRestart(2500);
        }
    }

    private String errorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "تعذر استخدام مسار الميكروفون (رمز 3)";
            case SpeechRecognizer.ERROR_CLIENT: return "توقف محرك التعرف (رمز 5)";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "إذن الميكروفون غير ممنوح (رمز 9)";
            case SpeechRecognizer.ERROR_NETWORK: return "تعذر الاتصال بخدمة التعرف (رمز 2)";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "انتهت مهلة خدمة التعرف (رمز 1)";
            case SpeechRecognizer.ERROR_NO_MATCH: return "لم أتعرف على كلمات واضحة (رمز 7)";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "الميكروفون مشغول بتطبيق آخر (رمز 8)";
            case SpeechRecognizer.ERROR_SERVER: return "خدمة التعرف لا تستجيب (رمز 4)";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "لم أسمع كلاماً (رمز 6)";
            case 11: return "انقطع اتصال محرك التعرف؛ أجرب مزود Android الاحتياطي (رمز 11)";
            case 12:
            case 13: return "لغة التعرف غير متاحة؛ اختر لغة أخرى (رمز " + error + ")";
            default: return "خطأ في التعرف على الصوت (رمز " + error + ")";
        }
    }

    private void scheduleRestart(long delay) {
        handler.removeCallbacks(restartRunnable);
        if (!paused && !destroyed) handler.postDelayed(restartRunnable, delay);
    }
    private final Runnable restartRunnable = this::startRecognition;

    private void destroyRecognizer() {
        SpeechRecognizer old = recognizer;
        recognizer = null;
        isListening = false;
        if (old != null) {
            try { old.cancel(); } catch (Exception ignored) {}
            try { old.destroy(); } catch (Exception ignored) {}
        }
    }

    private void stopRecognition() {
        handler.removeCallbacks(restartRunnable);
        destroyRecognizer();
    }

    private void sendEvent(String type, String message) {
        Intent event = new Intent(ACTION_EVENT);
        event.setPackage(getPackageName());
        event.putExtra(EXTRA_EVENT, type);
        event.putExtra(EXTRA_TEXT, message == null ? "" : message);
        sendBroadcast(event);
    }

    private void acquireCpuWakeLock() {
        try {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power == null) return;
            if (wakeLock == null) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ARTHER:VoiceWake");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } catch (Exception ignored) {}
    }

    private void releaseCpuWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
        catch (Exception ignored) {}
        wakeLock = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "ARTHER Voice", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("إشعار دائم عند تشغيل الاستعداد الصوتي");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        stopRecognition();
        isRunning = false;
        isPaused = false;
        releaseCpuWakeLock();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
