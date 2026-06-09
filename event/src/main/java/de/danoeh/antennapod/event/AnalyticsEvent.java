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
     * The very first time auto-trim actually skips a segment for this install — the
     * activation-completion signal: the user pressed play AND <em>felt</em> the trim,
     * which is the whole pitch. Fires once per install. {@code segmentType} is the kind
     * of segment that landed it (intro | ad | outro).
     */
    public static AnalyticsEvent firstTrimObserved(String segmentType) {
        Bundle b = new Bundle();
        b.putString("segment_type", segmentType != null ? segmentType : "unknown");
        return new AnalyticsEvent("first_trim_observed", b);
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

    /**
     * The first-run onboarding "Bring your podcasts" screen was shown. Top of the
     * activation funnel — pairs with {@link #onboardingImportChoice} and the
     * existing {@link #importCompleted} to measure what share of new installs leave
     * onboarding with a populated library.
     */
    public static AnalyticsEvent onboardingImportShown() {
        return new AnalyticsEvent("onboarding_import_shown", new Bundle());
    }

    /**
     * Which path the user picked on the onboarding screen. {@code choice} is one of
     * spotify | podcast_addict | antennapod | portcast | opml | skip.
     */
    public static AnalyticsEvent onboardingImportChoice(String choice) {
        Bundle b = new Bundle();
        b.putString("choice", choice != null ? choice : "unknown");
        return new AnalyticsEvent("onboarding_import_choice", b);
    }

    /**
     * An onboarding import was actually enqueued (the success screen is shown).
     * Closes the onboarding funnel — shown -&gt; choice -&gt; succeeded — and is the
     * cleanest signal of whether onboarding converts, since for OPML/Spotify the
     * {@link #importCompleted} finishes in a different screen.
     */
    public static AnalyticsEvent onboardingImportSucceeded(String source, int subscriptionsCount) {
        Bundle b = new Bundle();
        b.putString("import_source", source != null ? source : "unknown");
        if (subscriptionsCount >= 0) {
            b.putInt("subscriptions_added", subscriptionsCount);
        }
        return new AnalyticsEvent("onboarding_import_succeeded", b);
    }

    /** User tapped through the onboarding success screen to Home. A near-zero dwell
     *  before this fires means the success / auto-trim message isn't landing. */
    public static AnalyticsEvent onboardingSuccessCtaTapped(String source) {
        Bundle b = new Bundle();
        b.putString("import_source", source != null ? source : "unknown");
        return new AnalyticsEvent("onboarding_success_cta_tapped", b);
    }

    /**
     * The new user pressed play from the post-import "first play" nudge on Home — the
     * true activation event. Without this we can't see whether onboarding ever leads to
     * a play. {@code source} is the import they came from.
     */
    public static AnalyticsEvent onboardingFirstPlayStarted(String source) {
        Bundle b = new Bundle();
        b.putString("import_source", source != null ? source : "unknown");
        return new AnalyticsEvent("onboarding_first_play_started", b);
    }

    /**
     * The user exported their library to a file — the proof that "your data is yours,
     * leave anytime" is real, not just claimed. Makes the data-portability pillar a
     * measurable metric, not only a capability. {@code format} is one of
     * portcast | opml | database | html | favorites.
     */
    public static AnalyticsEvent exportCompleted(String format) {
        Bundle b = new Bundle();
        b.putString("export_format", format != null ? format : "unknown");
        return new AnalyticsEvent("export_completed", b);
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
