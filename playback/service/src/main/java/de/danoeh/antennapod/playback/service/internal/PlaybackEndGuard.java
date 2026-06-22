package de.danoeh.antennapod.playback.service.internal;

/**
 * Decides when to force end-of-episode because the player's MP3 duration estimate overshoots the
 * real audio length.
 *
 * <p>ExoPlayer's {@code setConstantBitrateSeekingEnabled(true)} (enabled upstream for headerless
 * MP3 seeking) derives a duration from a constant-bitrate estimate when the file has no Xing/VBRI
 * header. On a VBR file that estimate overshoots the true audio length, leaving a silent "phantom
 * tail" between the real end and the player's believed end. Playback runs into that tail — no
 * audio, position still advancing — and never reaches {@code STATE_ENDED}, so the episode never
 * completes or auto-advances.
 *
 * <p>The publisher-provided {@code itunes:duration} (stored on {@code FeedMedia}) is the trusted
 * real length. When the player's estimate exceeds it by a clear margin, we end at the feed
 * duration instead of waiting for the inflated estimate.
 *
 * <p>Pure (no Android) so the boundary logic is unit-testable on the JVM.
 */
public final class PlaybackEndGuard {

    private PlaybackEndGuard() {
    }

    /**
     * @param positionMs       current playhead from the player clock
     * @param feedDurationMs   publisher-provided duration ({@code itunes:duration}); the trusted
     *                         real audio length, or {@code <= 0} if the feed didn't supply one
     * @param playerDurationMs the duration the player reports. For streaming headerless MP3s with no
     *                         content-length this is {@code TIME_UNSET} and the caller falls back to
     *                         the feed value, so it equals {@code feedDurationMs}. When the player
     *                         instead reports a value <em>greater</em> than the feed, it knows a
     *                         genuinely longer end (accurate longer file / under-stated feed) and we
     *                         must let it play out and fire {@code STATE_ENDED} itself.
     * @param marginMs         how far past the feed duration the playhead must run before we end the
     *                         episode. Provides a small grace so accurate feeds (which stop exactly
     *                         at the end) never trip it, and protects feeds that under-state real
     *                         content by less than the margin.
     * @return true when the playhead has overrun the real (feed) end and the player has no known end
     *         beyond it — i.e. we are in a silent phantom/trailing tail and should complete now
     */
    public static boolean shouldCompleteAtFeedDuration(
            long positionMs, long feedDurationMs, long playerDurationMs, long marginMs) {
        if (feedDurationMs <= 0) {
            // No trustworthy feed length to end at — leave it to ExoPlayer.
            return false;
        }
        if (playerDurationMs > feedDurationMs) {
            // The player knows a real end past the feed duration (e.g. a downloaded file whose feed
            // under-states it): let ExoPlayer play it out and end itself — don't cut real audio.
            return false;
        }
        // The player has no end beyond the feed duration (streaming, duration unknown → fell back to
        // the feed value), yet the playhead has overrun it: a silent phantom/trailing tail that will
        // never reach STATE_ENDED at the right place. End at the feed duration.
        return positionMs >= feedDurationMs + marginMs;
    }
}
