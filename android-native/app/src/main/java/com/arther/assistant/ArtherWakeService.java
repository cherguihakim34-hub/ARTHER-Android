package com.arther.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class ArtherWakeService extends Service {
    private static final String CHANNEL_ID = "arther_voice_service";
    private static final int NOTIFICATION_ID = 11901;
    public static volatile boolean isRunning = false;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
                .setContentTitle("ARTHER · وضع الاستعداد الصوتي")
                .setContentText("خدمة الخلفية نشطة؛ قد يتوقف التعرف إذا أوقف Android جلسة الصوت.")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .build();

            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                );
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            acquireCpuWakeLock();
            isRunning = true;
        } catch (SecurityException | IllegalStateException error) {
            isRunning = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    private void acquireCpuWakeLock() {
        try {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power == null) return;
            if (wakeLock == null) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ARTHER:VoiceWake");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) {
                // Hold CPU awake only while the user-enabled foreground voice service runs.
                // Android releases the lock if the process is killed; onDestroy releases it normally.
                wakeLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private void releaseCpuWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        wakeLock = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "ARTHER Voice",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("إشعار دائم عند تشغيل الاستعداد الصوتي");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        releaseCpuWakeLock();
        super.onDestroy();
    }
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}