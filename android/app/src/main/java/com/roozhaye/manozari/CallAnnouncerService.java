package com.roozhaye.manozari;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class CallAnnouncerService extends Service {
    public static final String CHANNEL_ID = "rmz_call_announcer_channel";
    public static final int NOTIF_ID = 7002;
    public static final String EXTRA_TEXT = "spoken_text";
    public static final String EXTRA_TITLE = "notif_title";
    public static final String EXTRA_IS_MALE = "is_male";
    public static final String EXTRA_REPEAT = "repeat_count";
    public static final String EXTRA_IS_CALL = "is_call";
    private static final String DEFAULT_API_BASE = "https://roozhaye-manozari-7.ai.studio";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled = false;
    private MediaPlayer activePlayer = null;

    public static void announce(Context context, String title, String text, boolean isMale, int repeatCount, boolean isCall) {
        Intent intent = new Intent(context, CallAnnouncerService.class);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_TEXT, text);
        intent.putExtra(EXTRA_IS_MALE, isMale);
        intent.putExtra(EXTRA_REPEAT, Math.max(1, Math.min(2, repeatCount)));
        intent.putExtra(EXTRA_IS_CALL, isCall);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stopActive(Context context) {
        context.stopService(new Intent(context, CallAnnouncerService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "اعلام صوتی تماس و پیام‌ها",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        cancelled = false;
        stopActivePlayer();
        handler.removeCallbacksAndMessages(null);

        String title = intent != null ? intent.getStringExtra(EXTRA_TITLE) : "اعلام صوتی";
        String text = intent != null ? intent.getStringExtra(EXTRA_TEXT) : "";
        boolean isMale = intent != null && intent.getBooleanExtra(EXTRA_IS_MALE, false);
        int repeat = intent != null ? intent.getIntExtra(EXTRA_REPEAT, 1) : 1;
        boolean isCall = intent != null && intent.getBooleanExtra(EXTRA_IS_CALL, false);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title != null ? title : "روزهای من و زری")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, notification);
        }

        if (text == null || text.trim().isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        final String spoken = repeat == 2 ? (text + " . " + text) : text;
        final String voiceName = isMale ? "Charon" : "Kore";

        handler.postDelayed(() -> {
            if (cancelled) return;
            new Thread(() -> playOnlineOrFallback(spoken, voiceName, isMale, isCall)).start();
        }, isCall ? 400 : 150);

        // Safety stop after 40s
        handler.postDelayed(this::stopSelf, 40000);
        return START_NOT_STICKY;
    }

    private void playOnlineOrFallback(String spoken, String voiceName, boolean isMale, boolean isCall) {
        if (cancelled) return;
        File wavFile = fetchOrGetCachedWav(spoken, voiceName);
        if (cancelled) return;

        if (wavFile != null && wavFile.exists() && wavFile.length() > 44) {
            handler.post(() -> {
                if (cancelled) return;
                if (!playWavFile(wavFile, isCall)) {
                    fallbackOfflineSpeak(spoken, isMale, isCall);
                }
            });
            return;
        }

        handler.post(() -> {
            if (!cancelled) {
                fallbackOfflineSpeak(spoken, isMale, isCall);
            }
        });
    }

    private void fallbackOfflineSpeak(String spoken, boolean isMale, boolean isCall) {
        BilingualSpeechHelper.speak(this, spoken, isMale, 1.0f, isCall, success -> stopSelf());
    }

    private boolean playWavFile(File wavFile, boolean isCall) {
        try {
            stopActivePlayer();
            MediaPlayer mp = new MediaPlayer();
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(isCall ? AudioAttributes.USAGE_NOTIFICATION_RINGTONE : AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            mp.setAudioAttributes(attrs);
            mp.setDataSource(wavFile.getAbsolutePath());
            mp.setOnCompletionListener(player -> {
                stopActivePlayer();
                stopSelf();
            });
            mp.setOnErrorListener((player, what, extra) -> {
                stopActivePlayer();
                stopSelf();
                return true;
            });
            mp.prepare();
            if (cancelled) {
                mp.release();
                return false;
            }
            activePlayer = mp;
            mp.start();
            return true;
        } catch (Exception ignored) {
            stopActivePlayer();
            return false;
        }
    }

    private File fetchOrGetCachedWav(String text, String voiceName) {
        try {
            File cacheDir = new File(getCacheDir(), "rmz_tts_wav");
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            String key = sha256Hex(voiceName + "::" + text);
            File cachedFile = new File(cacheDir, key + ".wav");
            if (cachedFile.exists() && cachedFile.length() > 44) {
                return cachedFile;
            }

            SharedPreferences prefs = getSharedPreferences("rmz_native_prefs", MODE_PRIVATE);
            String baseUrl = prefs.getString("server_base_url", DEFAULT_API_BASE);
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                baseUrl = DEFAULT_API_BASE;
            }
            baseUrl = baseUrl.replaceAll("/+$", "");

            URL url = new URL(baseUrl + "/api/tts");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(6500);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            JSONObject body = new JSONObject();
            body.put("text", text.length() > 1200 ? text.substring(0, 1200) : text);
            body.put("voiceName", voiceName);
            body.put("style", "Clear fluent Tehran Persian and English announcer.");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                return null;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
            conn.disconnect();

            JSONObject resp = new JSONObject(sb.toString());
            if (resp.optBoolean("fallbackOffline", false)) {
                return null;
            }
            String audioB64 = resp.optString("audioBase64", "");
            if (audioB64.isEmpty()) {
                return null;
            }

            byte[] wavBytes = Base64.decode(audioB64, Base64.DEFAULT);
            if (wavBytes.length <= 44) {
                return null;
            }
            try (FileOutputStream fos = new FileOutputStream(cachedFile)) {
                fos.write(wavBytes);
            }
            return cachedFile;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String sha256Hex(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < Math.min(16, digest.length); i++) {
            hex.append(String.format("%02x", digest[i]));
        }
        return hex.toString();
    }

    private void stopActivePlayer() {
        if (activePlayer != null) {
            try {
                if (activePlayer.isPlaying()) activePlayer.stop();
            } catch (Exception ignored) {
            }
            try {
                activePlayer.release();
            } catch (Exception ignored) {
            }
            activePlayer = null;
        }
    }

    @Override
    public void onDestroy() {
        cancelled = true;
        handler.removeCallbacksAndMessages(null);
        stopActivePlayer();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
