package de.danoeh.antennapod.event;

import android.os.Bundle;

public class AnalyticsEvent {
    public final String name;
    public final Bundle params;

    private AnalyticsEvent(String name, Bundle params) {
        this.name = name;
        this.params = params;
    }

    public static AnalyticsEvent episodePlayed(String episodeTitle, String podcastTitle,
                                               long durationMin, boolean androidAuto) {
        Bundle b = new Bundle();
        b.putString("episode_title", truncate(episodeTitle));
        b.putString("podcast_title", truncate(podcastTitle));
        b.putLong("duration_min", durationMin);
        b.putString("platform", androidAuto ? "android_auto" : "phone");
        return new AnalyticsEvent("episode_played", b);
    }

    public static AnalyticsEvent episodeCompleted(String episodeTitle, String podcastTitle,
                                                   int completionPct) {
        Bundle b = new Bundle();
        b.putString("episode_title", truncate(episodeTitle));
        b.putString("podcast_title", truncate(podcastTitle));
        b.putInt("completion_pct", completionPct);
        return new AnalyticsEvent("episode_completed", b);
    }

    public static AnalyticsEvent speedChanged(float speed) {
        Bundle b = new Bundle();
        b.putDouble("speed", Math.round(speed * 100.0) / 100.0);
        return new AnalyticsEvent("playback_speed_set", b);
    }

    public static AnalyticsEvent segmentSkipped(String type, int durationSec) {
        Bundle b = new Bundle();
        b.putString("segment_type", type != null ? type : "unknown");
        b.putInt("duration_sec", durationSec);
        return new AnalyticsEvent("segment_auto_skipped", b);
    }

    /** User seeked backwards into a range we just auto-skipped → false positive. */
    public static AnalyticsEvent segmentRevertSkip(int revertedSec) {
        Bundle b = new Bundle();
        b.putInt("reverted_sec", revertedSec);
        return new AnalyticsEvent("segment_revert_skip", b);
    }

    /** User manually skipped a long forward chunk that wasn't flagged → likely a missed ad. */
    public static AnalyticsEvent segmentMissed(int missedSec) {
        Bundle b = new Bundle();
        b.putInt("missed_sec", missedSec);
        return new AnalyticsEvent("segment_missed", b);
    }

    public static AnalyticsEvent androidAutoConnected() {
        return new AnalyticsEvent("android_auto_session", new Bundle());
    }

    public static AnalyticsEvent mutePaused() {
        return new AnalyticsEvent("mute_pause", new Bundle());
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 100 ? s.substring(0, 100) : s;
    }
}
