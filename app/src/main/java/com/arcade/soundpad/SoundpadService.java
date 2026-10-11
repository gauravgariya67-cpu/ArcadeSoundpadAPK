package com.arcade.soundpad;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SoundpadService extends Service {
    public static final String CHANNEL_ID = "arcade_soundpad_channel";
    public static final String ACTION_STOP = "com.arcade.soundpad.STOP_ALL";

    private final IBinder binder = new LocalBinder();
    private final List<MediaPlayer> activePlayers = Collections.synchronizedList(new ArrayList<>());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;

    public float volume = 0.85f;
    public float pitch = 1.0f;
    public float speed = 1.0f;
    public boolean loopEnabled = false;
    public boolean overlapEnabled = false;

    public class LocalBinder extends Binder {
        SoundpadService getService() {
            return SoundpadService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ArcadeSoundpad::BgWakeLock");
            wakeLock.acquire();
        }
        startForegroundNotification("Ready in background");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopAll();
        }
        return START_STICKY;
    }

    public void startForegroundNotification(String status) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel chan = new NotificationChannel(
                CHANNEL_ID, "Soundpad Playback", NotificationManager.IMPORTANCE_LOW
            );
            nm.createNotificationChannel(chan);
        }

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent stopIntent = new Intent(this, SoundpadService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);

        Notification notif = builder
            .setContentTitle("Arcade Soundpad")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(
                android.R.drawable.ic_media_pause, "STOP ALL", stopPi).build())
            .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(101, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(101, notif);
        }
    }

    public void playFile(File file, String title, double trimStart, double trimEnd) {
        if (!overlapEnabled) {
            stopAll();
        }
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
            mp.setDataSource(file.getAbsolutePath());
            mp.prepare();

            mp.setLooping(loopEnabled);
            mp.setVolume(volume, volume);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PlaybackParams params = new PlaybackParams();
                params.setPitch(Math.max(0.5f, Math.min(2.0f, pitch)));
                params.setSpeed(Math.max(0.5f, Math.min(2.0f, speed)));
                mp.setPlaybackParams(params);
            }

            if (trimStart > 0) {
                mp.seekTo((int) (trimStart * 1000));
            }

            mp.setOnCompletionListener(player -> {
                activePlayers.remove(player);
                player.release();
                if (activePlayers.isEmpty()) {
                    startForegroundNotification("Idle — Ready in background");
                }
            });

            activePlayers.add(mp);
            mp.start();
            startForegroundNotification("Playing: " + title);

            if (trimEnd > trimStart && !loopEnabled) {
                long durationMs = (long) (((trimEnd - trimStart) / speed) * 1000);
                mainHandler.postDelayed(() -> {
                    if (activePlayers.contains(mp)) {
                        try {
                            mp.stop();
                        } catch (Exception ignored) {}
                        activePlayers.remove(mp);
                        mp.release();
                    }
                }, Math.max(100, durationMs));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void stopAll() {
        synchronized (activePlayers) {
            for (MediaPlayer mp : activePlayers) {
                try {
                    if (mp.isPlaying()) mp.stop();
                } catch (Exception ignored) {}
                try {
                    mp.release();
                } catch (Exception ignored) {}
            }
            activePlayers.clear();
        }
        startForegroundNotification("Idle — Ready in background");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopAll();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }
}
