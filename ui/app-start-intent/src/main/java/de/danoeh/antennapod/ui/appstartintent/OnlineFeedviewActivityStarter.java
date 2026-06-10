package de.danoeh.antennapod.ui.appstartintent;

import android.content.Context;
import android.content.Intent;

public class OnlineFeedviewActivityStarter {
    public static final String INTENT = "de.danoeh.antennapod.intents.ONLINE_FEEDVIEW";
    public static final String ARG_FEEDURL = "arg.feedurl";
    public static final String ARG_WAS_MANUAL_URL = "manual_url";
    /** RSS GUID (or enclosure URL) of an episode to jump straight to after the
     *  feed is added as a not-subscribed preview. Used by the onboarding curated
     *  rail to drop a brand-new listener directly onto a known-good demo episode. */
    public static final String ARG_EPISODE = "arg.episode";
    private final Intent intent;

    public OnlineFeedviewActivityStarter(Context context, String feedUrl) {
        intent = new Intent(INTENT);
        intent.setPackage(context.getPackageName());
        intent.putExtra(ARG_FEEDURL, feedUrl);
    }

    public OnlineFeedviewActivityStarter withManualUrl() {
        intent.putExtra(ARG_WAS_MANUAL_URL, true);
        return this;
    }

    /** Route directly to this episode (RSS GUID or enclosure URL) once the feed
     *  is parsed, instead of showing the feed's episode list. */
    public OnlineFeedviewActivityStarter withEpisode(String episodeId) {
        if (episodeId != null && !episodeId.isEmpty()) {
            intent.putExtra(ARG_EPISODE, episodeId);
        }
        return this;
    }

    public Intent getIntent() {
        return intent;
    }
}
