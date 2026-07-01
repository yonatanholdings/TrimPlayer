package de.danoeh.antennapod.storage.importexport;

import android.Manifest;
import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.model.feed.VolumeAdaptionSetting;
import de.danoeh.antennapod.net.download.serviceinterface.FeedUpdateManager;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.database.FeedDatabaseWriter;
import de.danoeh.antennapod.ui.notifications.NotificationUtils;

/**
 * Subscribes feeds from a PortCast import and applies per-feed preferences,
 * then chains {@link PortcastStateWorker} to resolve episode states + queue
 * after the feed refresh.
 *
 * Counterpart of {@link PodcastAddictSubscribeWorker}; they intentionally
 * share structure so the maintenance burden is symmetric.
 */
public class PortcastSubscribeWorker extends Worker {
    private static final String TAG = "PortcastSubscribeWorker";
    /** Unique-work name; public so the in-app status banner can observe it. */
    public static final String WORK_ID = "de.danoeh.antennapod.PortcastSubscribe";
    /**
     * {@code setProgressAsync} keys read by the import status banner.
     */
    public static final String PROGRESS_CURRENT = "current";
    public static final String PROGRESS_TOTAL = "total";

    public static void enqueue(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(PortcastSubscribeWorker.class)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_ID, ExistingWorkPolicy.REPLACE, request);
    }

    private final NotificationManagerCompat notificationManager;

    public PortcastSubscribeWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        notificationManager = NotificationManagerCompat.from(context);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        List<PortcastImporter.PortFeed> feeds = PortcastImporter.loadPendingFeeds(ctx);
        if (feeds.isEmpty()) {
            // No feeds to subscribe — kick the state worker directly so any
            // queue/state targeting already-subscribed feeds still applies.
            PortcastStateWorker.enqueue(ctx);
            return Result.success();
        }

        Log.d(TAG, "Subscribing " + feeds.size() + " feeds in the background");
        int done = 0;
        int total = feeds.size();
        updateProgress(done, total);

        for (PortcastImporter.PortFeed pf : feeds) {
            if (isStopped()) {
                Log.d(TAG, "Cancelled at " + done + "/" + total);
                return Result.success();
            }
            try {
                subscribeOne(ctx, pf);
            } catch (Exception e) {
                Log.e(TAG, "Subscribe failed for " + pf.feedUrl, e);
            }
            done++;
            updateProgress(done, total);
        }

        PortcastImporter.clearPendingFeeds(ctx);
        FeedUpdateManager.getInstance().runOnce(ctx);
        PortcastStateWorker.enqueue(ctx);
        notificationManager.cancel(R.id.notification_id_portcast_import);
        return Result.success();
    }

    private void subscribeOne(Context ctx, PortcastImporter.PortFeed pf) {
        // Reuse an already-subscribed feed when the incoming URL matches one
        // under normalization, so a re-import never creates a duplicate. We
        // match on the URL ourselves rather than letting FeedDatabaseWriter
        // dedupe, because its match key is Feed#getIdentifyingValue, which
        // prefers an Atom feed's feedIdentifier over its download URL (set on
        // every refresh from the feed's <id>, see Atom parser). Once a feed has
        // been refreshed, updateFeed would therefore miss a URL-only match and
        // add a second row. SubscriptionIdIndex covers resolver-sourced
        // (Spotify) feeds; this covers plain-RSS, empty-subscriptionId, and
        // post-reinstall re-imports.
        Feed persisted = findExistingFeedByUrl(pf.feedUrl);
        if (persisted == null) {
            Feed feed = new Feed(pf.feedUrl, null, pf.title);
            feed.setItems(Collections.emptyList());
            persisted = FeedDatabaseWriter.updateFeed(ctx, feed, false);
        }
        if (persisted == null || persisted.getPreferences() == null) {
            return;
        }
        // Importing a show means subscribing to it. A newly-created feed already
        // defaults to STATE_SUBSCRIBED, but a *reused* feed may have been added
        // earlier as a non-subscribed online preview — promote it, or the
        // NonSubscribedFeedsCleaner would later garbage-collect the very feed we
        // just imported.
        if (persisted.getState() != Feed.STATE_SUBSCRIBED) {
            try {
                DBWriter.setFeedState(ctx, persisted, Feed.STATE_SUBSCRIBED).get();
            } catch (Exception e) {
                Log.e(TAG, "Failed to mark imported feed subscribed: " + pf.feedUrl, e);
            }
        }
        // Persist the subscriptionId → feedUrl mapping so future re-imports
        // can short-circuit resolution and avoid creating duplicates from
        // tiny URL canonicalization differences.
        SubscriptionIdIndex.put(ctx, pf.subscriptionId, persisted.getDownloadUrl());
        FeedPreferences prefs = persisted.getPreferences();
        boolean dirty = false;

        if (pf.playbackSpeed > 0.0f) {
            prefs.setFeedPlaybackSpeed(pf.playbackSpeed);
            dirty = true;
        }
        if (pf.skipIntroSec > 0) {
            prefs.setFeedSkipIntro(pf.skipIntroSec);
            dirty = true;
        }
        if (pf.skipOutroSec > 0) {
            prefs.setFeedSkipEnding(pf.skipOutroSec);
            dirty = true;
        }
        if (!pf.tags.isEmpty()) {
            Set<String> merged = new HashSet<>(prefs.getTags());
            boolean tagsChanged = merged.addAll(pf.tags);
            if (tagsChanged) {
                prefs.getTags().clear();
                prefs.getTags().addAll(merged);
                dirty = true;
            }
        }
        if (pf.notificationsEnabled != null
                && prefs.getShowEpisodeNotification() != pf.notificationsEnabled) {
            prefs.setShowEpisodeNotification(pf.notificationsEnabled);
            dirty = true;
        }
        if (applyExtensions(prefs, pf.extensions)) {
            dirty = true;
        }
        if (dirty) {
            DBWriter.setFeedPreferences(prefs);
        }
    }

    /**
     * Find an already-subscribed feed whose download URL matches {@code incomingUrl}
     * under {@link PortcastImporter#normalizeFeedUrl} (absorbing scheme, {@code www.},
     * and trailing-slash drift), or null if none. Returned feeds come straight from
     * {@link DBReader#getFeedList()} with preferences populated, so the caller can
     * apply per-feed prefs to the existing subscription. Matching the URL directly
     * (not {@link Feed#getIdentifyingValue()}) is deliberate — see {@link #subscribeOne}.
     */
    @Nullable
    private static Feed findExistingFeedByUrl(String incomingUrl) {
        if (incomingUrl == null || incomingUrl.isEmpty()) {
            return null;
        }
        String key = PortcastImporter.normalizeFeedUrl(incomingUrl);
        if (key.isEmpty()) {
            return null;
        }
        for (Feed existing : DBReader.getFeedList()) {
            String url = existing.getDownloadUrl();
            if (url != null && PortcastImporter.normalizeFeedUrl(url).equals(key)) {
                return existing;
            }
        }
        return null;
    }

    /** Apply our `com.trimplayer.*` extension namespaces if present. Returns whether anything was applied. */
    private static boolean applyExtensions(FeedPreferences prefs, JSONObject ext) {
        if (ext == null) {
            return false;
        }
        boolean dirty = false;
        JSONObject skips = ext.optJSONObject("com.trimplayer.skips");
        if (skips != null) {
            prefs.setTrimSkipIntros(skips.optBoolean("trimSkipIntros", prefs.isTrimSkipIntros()));
            prefs.setTrimSkipAds(skips.optBoolean("trimSkipAds", prefs.isTrimSkipAds()));
            prefs.setTrimSkipOutros(skips.optBoolean("trimSkipOutros", prefs.isTrimSkipOutros()));
            dirty = true;
        }
        JSONObject skipSilence = ext.optJSONObject("com.trimplayer.feedSkipSilence");
        if (skipSilence != null) {
            String mode = skipSilence.optString("mode", "");
            FeedPreferences.SkipSilence value = parseEnum(FeedPreferences.SkipSilence.class, mode);
            if (value != null) {
                prefs.setFeedSkipSilence(value);
                dirty = true;
            }
        }
        JSONObject va = ext.optJSONObject("com.trimplayer.volumeAdaption");
        if (va != null) {
            String setting = va.optString("setting", "");
            VolumeAdaptionSetting value = parseEnum(VolumeAdaptionSetting.class, setting);
            if (value != null) {
                prefs.setVolumeAdaptionSetting(value);
                dirty = true;
            }
        }
        JSONObject ad = ext.optJSONObject("com.trimplayer.autoDeleteAction");
        if (ad != null) {
            String action = ad.optString("action", "");
            FeedPreferences.AutoDeleteAction value =
                    parseEnum(FeedPreferences.AutoDeleteAction.class, action);
            if (value != null) {
                prefs.setAutoDeleteAction(value);
                dirty = true;
            }
        }
        JSONObject ne = ext.optJSONObject("com.trimplayer.newEpisodesAction");
        if (ne != null) {
            String action = ne.optString("action", "");
            FeedPreferences.NewEpisodesAction value =
                    parseEnum(FeedPreferences.NewEpisodesAction.class, action);
            if (value != null) {
                prefs.setNewEpisodesAction(value);
                dirty = true;
            }
        }
        return dirty;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> cls, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            return Enum.valueOf(cls, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Publish both the system notification and the WorkManager progress that
     *  drives the in-app import status banner. */
    private void updateProgress(int done, int total) {
        setProgressAsync(new Data.Builder()
                .putInt(PROGRESS_CURRENT, done)
                .putInt(PROGRESS_TOTAL, total)
                .build());
        updateNotification(done, total);
    }

    private void updateNotification(int done, int total) {
        if (ContextCompat.checkSelfPermission(getApplicationContext(),
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        notificationManager.notify(R.id.notification_id_portcast_import,
                createNotification(done, total));
    }

    private Notification createNotification(int done, int total) {
        Context ctx = getApplicationContext();
        String text = ctx.getString(R.string.portcast_import_subscribing,
                Math.min(done + 1, total), total);
        return new NotificationCompat.Builder(ctx, NotificationUtils.CHANNEL_ID_REFRESHING)
                .setContentTitle(ctx.getString(R.string.portcast_import_notification_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(total, done, false)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
    }

    @NonNull
    @Override
    public ListenableFuture<ForegroundInfo> getForegroundInfoAsync() {
        return Futures.immediateFuture(
                new ForegroundInfo(R.id.notification_id_portcast_import, createNotification(0, 1)));
    }
}
