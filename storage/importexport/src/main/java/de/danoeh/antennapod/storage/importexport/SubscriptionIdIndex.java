package de.danoeh.antennapod.storage.importexport;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/**
 * Persists the mapping from PortCast {@code subscriptionId} (a stable,
 * source-independent identifier carried in the document) to the local
 * feed URL we created for it.
 *
 * <p>Used by the PortCast importer to short-circuit re-imports: if a
 * subscription's {@code subscriptionId} is already in this index, the show
 * is "already following" and we can skip resolution for that row (the
 * subscribe worker's existing URL-based dedupe in
 * {@code FeedDatabaseWriter.updateFeed} then merges with the existing
 * Feed). Critical for Spotify-sourced imports where re-running the
 * resolver can produce a slightly different feed URL canonicalization
 * (http/https, trailing slash, feedburner redirect target), which would
 * otherwise create duplicate subscriptions.
 *
 * <p>We persist the feed URL (not Feed.id) because the importer only needs
 * something to set {@code PortFeed.feedUrl} to; the database id buys
 * nothing downstream and would require an extra lookup. The downside is
 * that if a feed's RSS URL changes after the original import, the cached
 * URL is stale — but that's a rare event and the failure mode is benign
 * (importer falls back to URL-based dedupe in the subscribe worker).
 *
 * <p>SharedPreferences-backed; one file per app install. Wiped on uninstall
 * via Android's normal data lifecycle — same expectations as the rest of
 * {@link PortcastImporter}'s stash.
 */
public final class SubscriptionIdIndex {

    private static final String PREFS_NAME = "portcast_subscription_id_index";

    private SubscriptionIdIndex() {}

    /** Returns null if the id has never been imported, or is null/empty. */
    @Nullable
    public static String lookup(Context context, @Nullable String subscriptionId) {
        if (subscriptionId == null || subscriptionId.isEmpty()) {
            return null;
        }
        return prefs(context).getString(subscriptionId, null);
    }

    public static void put(Context context, @Nullable String subscriptionId, @Nullable String feedUrl) {
        if (subscriptionId == null || subscriptionId.isEmpty()) {
            return;
        }
        if (feedUrl == null || feedUrl.isEmpty()) {
            return;
        }
        prefs(context).edit().putString(subscriptionId, feedUrl).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
