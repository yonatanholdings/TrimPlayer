package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;

import androidx.annotation.Nullable;

import java.util.Date;

import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.preferences.TrimPlaybackLog;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.Playable;

/**
 * Provides utility methods for Playable objects.
 */
public abstract class PlayableUtils {
    /**
     * A position write that moves an episode this far backwards is treated as suspicious.
     * Above the legitimate rewinds (rewind-on-resume tops out at 10 s, as does the manual
     * rewind button) so routine playback never trips it.
     */
    private static final int REGRESSION_THRESHOLD_MS = 15000;

    private static String lastWriteId = null;
    private static int lastWritePosition = -1;

    /**
     * Saves the current position of this object.
     *
     * @param newPosition  new playback position in ms
     * @param timestamp  current time in ms
     */
    public static void saveCurrentPosition(Playable playable, int newPosition, long timestamp) {
        saveCurrentPosition(null, playable, newPosition, timestamp);
    }

    /**
     * @param ctx  optional; when supplied, a backwards position write is recorded to the
     *             playback trail. Pass it from anything that writes during playback.
     */
    public static void saveCurrentPosition(@Nullable Context ctx, Playable playable,
                                           int newPosition, long timestamp) {
        recordPositionWrite(ctx, playable, newPosition);
        playable.setPosition(newPosition);
        playable.setLastPlayedTimeStatistics(timestamp);

        if (playable instanceof FeedMedia) {
            FeedMedia media = (FeedMedia) playable;
            media.setLastPlayedTimeHistory(new Date(timestamp));
            FeedItem item = media.getItem();
            if (item != null && item.isNew()) {
                DBWriter.markItemPlayed(FeedItem.UNPLAYED, item.getId());
            }
            // Treat startPosition == -1 as 0 (episode played from beginning, onPlaybackStart was never called).
            // Without this, fresh episodes never accumulate played_duration and are excluded from statistics.
            int effectiveStart = Math.max(media.getStartPosition(), 0);
            if (playable.getPosition() > effectiveStart) {
                media.setPlayedDuration(media.getPlayedDurationWhenStarted()
                        + playable.getPosition() - effectiveStart);
            }
            DBWriter.setFeedMediaPlaybackInformation(media);
        }
    }

    /**
     * Diagnostic for the still-open "returned to the beginning" class
     * (docs/investigation-reported-issues-2026-07.md). Every position write in the app funnels
     * through {@link #saveCurrentPosition}, so this is the one place that can see a write move
     * an episode backwards — the 2026-09-01 drive trail showed a stored position regress
     * 215921ms -> 85587ms with no logged seek, and nothing recorded which writer did it.
     *
     * <p>Only regressions are logged, never the 5 s ticks: {@link TrimPlaybackLog} is a
     * discrete-events-only, size-capped trail attached to bug reports. A deliberate long
     * backwards seek by the user also trips this, which is fine — the caller frame in the line
     * distinguishes a user seek (PlaybackController.seekTo) from an unattributed overwrite.
     */
    private static synchronized void recordPositionWrite(@Nullable Context ctx, Playable playable,
                                                         int newPosition) {
        if (ctx == null || playable == null || newPosition < 0) {
            return;
        }
        try {
            Object rawId = playable.getIdentifier();
            String id = rawId == null ? "?" : rawId.toString();
            if (id.equals(lastWriteId) && lastWritePosition >= 0
                    && newPosition < lastWritePosition - REGRESSION_THRESHOLD_MS) {
                TrimPlaybackLog.log(ctx, "position-regression from=" + lastWritePosition
                        + "ms to=" + newPosition
                        + "ms delta=-" + (lastWritePosition - newPosition)
                        + "ms by=" + callerFrame()
                        + " ep=" + playable.getEpisodeTitle());
            }
            lastWriteId = id;
            lastWritePosition = newPosition;
        } catch (Exception e) {
            // Diagnostics must never disturb playback.
        }
    }

    /** First stack frame outside this class — i.e. which writer performed the write. */
    private static String callerFrame() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        for (StackTraceElement el : stack) {
            if (!PlayableUtils.class.getName().equals(el.getClassName())) {
                String cls = el.getClassName();
                int dot = cls.lastIndexOf('.');
                return (dot >= 0 ? cls.substring(dot + 1) : cls) + "." + el.getMethodName();
            }
        }
        return "?";
    }
}
