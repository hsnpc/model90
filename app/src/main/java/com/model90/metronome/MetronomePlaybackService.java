package com.model90.metronome;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Pure Android SDK foreground media playback service shown ONLY while
 * "Background Play" is enabled and the metronome is actively playing.
 *
 * - Displays lock-screen & notification-shade media controls (Metronome 90 / Metronome 100 + live BPM)
 * - Does NOT request exclusive AudioFocus so users can play along with other music/backing tracks
 * - Tapping Stop/Pause on the notification, lock screen, or headset immediately stops playback and dismisses the card
 */
public final class MetronomePlaybackService extends Service {

    private static final String CHANNEL_ID = "model90_bg_playback";
    private static final int NOTIFICATION_ID = 9001;

    private static final String ACTION_START_OR_UPDATE = "com.model90.metronome.action.START_OR_UPDATE";
    private static final String ACTION_STOP = "com.model90.metronome.action.STOP";

    private static final String EXTRA_BPM = "extra_bpm";
    private static final String EXTRA_MODEL_NUM = "extra_model_num";
    private static final String EXTRA_LANG = "extra_lang";

    private static final long NOTIFICATION_UPDATE_DEBOUNCE_MS = 120L;

    private static MetronomePlaybackService activeInstance;
    private static Runnable stopRequestListener;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private NotificationManager notificationManager;
    private MediaSession mediaSession;
    private boolean isForegroundActive = false;

    private int currentBpm = 108;
    private int currentModelNum = 90;
    private String currentLang = "en";

    private final Runnable debouncedNotificationUpdate = () -> {
        if (!isForegroundActive || mediaSession == null || notificationManager == null) return;
        updateMediaSessionMetadataAndState();
        notificationManager.notify(NOTIFICATION_ID, buildNotification());
    };

    static void setStopRequestListener(Runnable listener) {
        stopRequestListener = listener;
    }

    static void clearStopRequestListener(Runnable listener) {
        if (stopRequestListener == listener) {
            stopRequestListener = null;
        }
    }

    static void startOrUpdate(Context context, int bpm, int modelNum, String lang) {
        if (context == null) return;
        MetronomePlaybackService running = activeInstance;
        if (running != null && running.isForegroundActive) {
            running.applyMetadataLive(bpm, modelNum, lang);
            return;
        }
        Intent intent = new Intent(context, MetronomePlaybackService.class);
        intent.setAction(ACTION_START_OR_UPDATE);
        intent.putExtra(EXTRA_BPM, bpm);
        intent.putExtra(EXTRA_MODEL_NUM, modelNum);
        intent.putExtra(EXTRA_LANG, lang);
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException ignored) {
            // Guard against background-start restrictions if a delayed JS callback arrives after activity stop.
        }
    }

    static void updateMetadataIfRunning(int bpm, int modelNum, String lang) {
        MetronomePlaybackService running = activeInstance;
        if (running != null && running.isForegroundActive) {
            running.applyMetadataLive(bpm, modelNum, lang);
        }
    }

    static void stopIfRunning(Context context) {
        MetronomePlaybackService running = activeInstance;
        if (running != null) {
            running.tearDownService();
        } else if (context != null) {
            try {
                context.stopService(new Intent(context, MetronomePlaybackService.class));
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        activeInstance = this;
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        ensureNotificationChannel();
        initMediaSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            handleUserStopFromControls();
            return START_NOT_STICKY;
        }

        if (intent != null) {
            currentBpm = clampBpm(intent.getIntExtra(EXTRA_BPM, currentBpm));
            currentModelNum = intent.getIntExtra(EXTRA_MODEL_NUM, currentModelNum) == 100 ? 100 : 90;
            String lang = intent.getStringExtra(EXTRA_LANG);
            if (lang != null) {
                currentLang = "ko".equals(lang) ? "ko" : "en";
            }
        }

        ensureNotificationChannel();
        if (mediaSession == null) {
            initMediaSession();
        }
        updateMediaSessionMetadataAndState();

        Notification notification = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Api29Impl.startMediaForeground(this, NOTIFICATION_ID, notification);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            isForegroundActive = true;
        } catch (RuntimeException e) {
            tearDownService();
        }

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        handleUserStopFromControls();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(debouncedNotificationUpdate);
        releaseMediaSessionCleanly();
        if (activeInstance == this) {
            activeInstance = null;
        }
        isForegroundActive = false;
        super.onDestroy();
    }

    private void applyMetadataLive(int bpm, int modelNum, String lang) {
        int clamped = clampBpm(bpm);
        int normalizedModel = modelNum == 100 ? 100 : 90;
        String normalizedLang = "ko".equals(lang) ? "ko" : "en";

        boolean langChanged = !normalizedLang.equals(currentLang);
        if (clamped == currentBpm && normalizedModel == currentModelNum && !langChanged) {
            return;
        }

        currentBpm = clamped;
        currentModelNum = normalizedModel;
        currentLang = normalizedLang;

        if (langChanged) {
            ensureNotificationChannel();
        }

        mainHandler.removeCallbacks(debouncedNotificationUpdate);
        mainHandler.postDelayed(debouncedNotificationUpdate, NOTIFICATION_UPDATE_DEBOUNCE_MS);
    }

    private static int clampBpm(int bpm) {
        return Math.max(20, Math.min(400, bpm));
    }

    private boolean isKorean() {
        return "ko".equals(currentLang);
    }

    private String resolveTitleText() {
        if (isKorean()) {
            return currentModelNum == 100 ? "메트로놈 100" : "메트로놈 90";
        }
        return currentModelNum == 100 ? "Metronome 100" : "Metronome 90";
    }

    private String resolveStatusText() {
        if (isKorean()) {
            return currentBpm + " BPM · 재생 중";
        }
        return currentBpm + " BPM · Playing";
    }

    private String resolveStopActionLabel() {
        return isKorean() ? "정지" : "Stop";
    }

    private String resolveChannelName() {
        return isKorean() ? "백그라운드 박자 재생" : "Background Playback";
    }

    private void ensureNotificationChannel() {
        if (notificationManager == null) return;
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            resolveChannelName(),
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setShowBadge(false);
        channel.enableLights(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        notificationManager.createNotificationChannel(channel);
    }

    private void initMediaSession() {
        releaseMediaSessionCleanly();
        mediaSession = new MediaSession(this, "Model90MetronomeSession");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPause() {
                handleUserStopFromControls();
            }

            @Override
            public void onStop() {
                handleUserStopFromControls();
            }
        });
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent sessionActivityPi = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        mediaSession.setSessionActivity(sessionActivityPi);
        mediaSession.setActive(true);
    }

    private void updateMediaSessionMetadataAndState() {
        if (mediaSession == null) return;

        MediaMetadata metadata = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, resolveTitleText())
            .putString(MediaMetadata.METADATA_KEY_ARTIST, resolveStatusText())
            .putLong(MediaMetadata.METADATA_KEY_DURATION, -1L)
            .build();
        mediaSession.setMetadata(metadata);

        PlaybackState state = new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP)
            .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build();
        mediaSession.setPlaybackState(state);
    }

    private Notification buildNotification() {
        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(
            this,
            101,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent stopIntent = new Intent(this, MetronomePlaybackService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this,
            102,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Icon stopIcon = Icon.createWithResource(this, R.drawable.ic_stat_stop);
        Notification.Action stopAction = new Notification.Action.Builder(
            stopIcon,
            resolveStopActionLabel(),
            stopPendingIntent
        ).build();

        Notification.MediaStyle mediaStyle = new Notification.MediaStyle()
            .setShowActionsInCompactView(0);
        if (mediaSession != null) {
            mediaStyle.setMediaSession(mediaSession.getSessionToken());
        }

        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_metronome)
            .setContentTitle(resolveTitleText())
            .setContentText(resolveStatusText())
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(stopPendingIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(stopAction)
            .setStyle(mediaStyle)
            .build();
    }

    private void handleUserStopFromControls() {
        tearDownService();
        final Runnable listener = stopRequestListener;
        if (listener != null) {
            mainHandler.post(listener);
        }
    }

    private void tearDownService() {
        mainHandler.removeCallbacks(debouncedNotificationUpdate);
        isForegroundActive = false;
        releaseMediaSessionCleanly();
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } catch (RuntimeException ignored) {
        }
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }
        stopSelf();
    }

    private void releaseMediaSessionCleanly() {
        if (mediaSession != null) {
            try {
                PlaybackState clearState = new PlaybackState.Builder()
                    .setActions(0L)
                    .setState(PlaybackState.STATE_NONE, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
                    .build();
                mediaSession.setPlaybackState(clearState);
                mediaSession.setActive(false);
                mediaSession.release();
            } catch (RuntimeException ignored) {
            }
            mediaSession = null;
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private static final class Api29Impl {
        static void startMediaForeground(Service service, int id, Notification notification) {
            service.startForeground(
                id,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            );
        }
    }
}
