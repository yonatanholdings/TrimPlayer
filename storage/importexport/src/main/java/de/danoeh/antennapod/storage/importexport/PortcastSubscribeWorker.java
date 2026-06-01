package de.danoeh.antennapod.storage.importexport;

import android.Manifest;
import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
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
    private static final String WORK_ID = "de.danoeh.antennapod.PortcastSubscribe";

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
        updateNotification(done, total);

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
            updateNotification(done, total);
        }

        PortcastImporter.clearPendingFeeds(ctx);
        FeedUpdateManager.getInstance().runOnce(ctx);
        PortcastStateWorker.enqueue(ctx);
        notificationManager.cancel(R.id.notification_id_portcast_import);
        return Result.success();
    }

    private void subscribeOne(Context ctx, PortcastImporter.PortFeed pf) {
        Feed feed = new Feed(pf.feedUrl, null, pf.title);
        feed.setItems(Collections.emptyList());
        Feed persisted = FeedDatabaseWriter.updateFeed(ctx, feed, false);
        if (persisted == null || persisted.getPreferences() == null) {
            return;
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
        if (applyExtensions(prefs, pf.extensions)) {
            dirty = true;
        }
        if (dirty) {
            DBWriter.setFeedPreferences(prefs);
        }
    }

    /** Apply our `com.trimplayer.*` extension namespaces if present. Returns whether anything was applied. */
    private static boolean applyExtensions(FeedPreferences prefs, JSONObject ext) {
        if (ext == null) return false;
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
        if (name == null || name.isEmpty()) return null;
        try {
            return Enum.valueOf(cls, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
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
