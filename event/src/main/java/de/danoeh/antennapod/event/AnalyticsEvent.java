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

    /**
     * User kicked off a library import. {@code source} is the app they're
     * coming from: opml | podcast_addict | portcast | spotify | database.
     * Pairs with {@link #importCompleted} so completion rate is reportable
     * by source (the mobile half of the website's switch-page funnel).
     */
    public static AnalyticsEvent importStarted(String source) {
        Bundle b = new Bundle();
        b.putString("import_source", source != null ? source : "unknown");
        return new AnalyticsEvent("import_started", b);
    }

    /**
     * A library import finished writing subscriptions. {@code subscriptionsAdded}
     * is omitted when the path can't cheaply count them (e.g. a full database
     * restore) — pass a negative value to skip it.
     */
    public static AnalyticsEvent importCompleted(String source, int subscriptionsAdded) {
        Bundle b = new Bundle();
        b.putString("import_source", source != null ? source : "unknown");
        if (subscriptionsAdded >= 0) {
            b.putInt("subscriptions_added", subscriptionsAdded);
        }
        return new AnalyticsEvent("import_completed", b);
    }

    /**
     * In-app analogue of the website's install-intent {@code play_click}, fired
     * once on first launch so the conversion event name is shared across the web
     * and app GA4 streams. {@code page_type}/{@code position} are constants here
     * (there is no page or button inside the app); {@code source} is "app" until
     * the Play Install Referrer (GH-0.4) is wired to supply the real channel.
     */
    public static AnalyticsEvent playClick(String source) {
        Bundle b = new Bundle();
        b.putString("source", source != null ? source : "app");
        b.putString("page_type", "app");
        b.putString("position", "first_open");
        return new AnalyticsEvent("play_click", b);
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
