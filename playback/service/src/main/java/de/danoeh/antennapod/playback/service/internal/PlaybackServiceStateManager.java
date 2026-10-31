package de.danoeh.antennapod.playback.service.internal;

import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ServiceCompat;
import de.danoeh.antennapod.playback.service.PlaybackService;
import de.danoeh.antennapod.storage.preferences.TrimPlaybackLog;

public class PlaybackServiceStateManager {
    private static final String TAG = "PlaybackSrvState";
    private final PlaybackService playbackService;

    private volatile boolean isInForeground = false;
    private volatile boolean hasReceivedValidStartCommand = false;

    public PlaybackServiceStateManager(PlaybackService playbackService) {
        this.playbackService = playbackService;
    }

    public void startForeground(int notificationId, Notification notification) {
        Log.d(TAG, "startForeground");
        try {
            playbackService.startForeground(notificationId, notification);
            isInForeground = true;
        } catch (IllegalStateException e) {
            // Android 12+ throws ForegroundServiceStartNotAllowedException (an
            // IllegalStateException) when we are in the background without an exemption.
            // Losing foreground status is recoverable; crashing mid-episode is not.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && e instanceof ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "startForeground not allowed from the background", e);
                TrimPlaybackLog.log(playbackService, "startForeground refused: " + e.getMessage());
                return;
            }
            throw e;
        }
    }

    public void stopService() {
        Log.d(TAG, "stopService");
        stopForeground(true);
        playbackService.stopSelf();
        hasReceivedValidStartCommand = false;
    }

    public void stopForeground(boolean removeNotification) {
        Log.d(TAG, "stopForeground");
        if (isInForeground) {
            if (removeNotification) {
                ServiceCompat.stopForeground(playbackService, ServiceCompat.STOP_FOREGROUND_REMOVE);
            } else {
                ServiceCompat.stopForeground(playbackService, ServiceCompat.STOP_FOREGROUND_DETACH);
            }
        }
        isInForeground = false;
    }

    public boolean hasReceivedValidStartCommand() {
        return hasReceivedValidStartCommand;
    }

    public void validStartCommandWasReceived() {
        this.hasReceivedValidStartCommand = true;
    }
}
