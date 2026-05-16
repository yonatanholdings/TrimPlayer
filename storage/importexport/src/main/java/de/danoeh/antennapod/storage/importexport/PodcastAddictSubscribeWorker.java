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

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.net.download.serviceinterface.FeedUpdateManager;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.database.FeedDatabaseWriter;
import de.danoeh.antennapod.ui.notifications.NotificationUtils;
import de.danoeh.antennapod.storage.importexport.R;

/**
 * Background worker that subscribes feeds from a Podcast Addict import and
 * applies per-feed preferences (playback speed, intro/outro skip, tags). Posts
 * a progress notification while running so the user knows the import is alive,
 * and is free to use the rest of the app immediately after dismissing the
 * import dialog.
 *
 * <p>Runs as a one-shot worker. When all feeds are subscribed it kicks
 * {@link FeedUpdateManager#runOnce(Context)} to fetch each RSS feed, then
 * enqueues {@link PodcastAddictStateWorker} which resolves the imported
 * currently-playing episode, queue entries, and episode states as feeds
 * finish refreshing (it retries on a 30s cadence).
 */
public class PodcastAddictSubscribeWorker extends Worker {
    private static final String TAG = "PASubscribeWorker";
    private static final String WORK_ID = "de.danoeh.antennapod.PodcastAddictSubscribe";

    public static void enqueue(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(PodcastAddictSubscribeWorker.class)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_ID, ExistingWorkPolicy.REPLACE, request);
    }

    private final NotificationManagerCompat notificationManager;

    public PodcastAddictSubscribeWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
        notificationManager = NotificationManagerCompat.from(context);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        List<PodcastAddictImporter.PaFeed> feeds = PodcastAddictImporter.loadPendingFeeds(ctx);
        if (feeds.isEmpty()) {
            return Result.success();
        }

        Log.d(TAG, "Subscribing " + feeds.size() + " feeds in the background");
        int done = 0;
        int total = feeds.size();
        updateNotification(done, total);

        for (PodcastAddictImporter.PaFeed paFeed : feeds) {
            if (isStopped()) {
                Log.d(TAG, "Cancelled at " + done + "/" + total);
                return Result.success();
            }
            try {
                subscribeOne(ctx, paFeed);
            } catch (Exception e) {
                Log.e(TAG, "Subscribe failed for " + paFeed.url, e);
            }
            done++;
            updateNotification(done, total);
        }

        // Subscribes are done — clear the stash so a future import can't pick
        // them up again, kick the feed refresh, and hand off to the state worker.
        PodcastAddictImporter.clearPendingFeeds(ctx);
        FeedUpdateManager.getInstance().runOnce(ctx);
        PodcastAddictStateWorker.enqueue(ctx);

        // The state worker is responsible for the "Applying episode states…"
        // ongoing notification on its own; we drop ours here.
        notificationManager.cancel(R.id.notification_id_pa_import);

        return Result.success();
    }

    /** Subscribe one feed + apply its per-podcast preferences. */
    private void subscribeOne(Context ctx, PodcastAddictImporter.PaFeed paFeed) {
        Feed feed = new Feed(paFeed.url, null, paFeed.title);
        feed.setItems(Collections.emptyList());
        Feed persisted = FeedDatabaseWriter.updateFeed(ctx, feed, false);
        if (persisted == null || persisted.getPreferences() == null) {
            return;
        }
        FeedPreferences prefs = persisted.getPreferences();
        boolean dirty = false;

        if (paFeed.playbackSpeed > 0.0f) {
            prefs.setFeedPlaybackSpeed(paFeed.playbackSpeed);
            dirty = true;
        }
        if (paFeed.skipIntroSec > 0) {
            prefs.setFeedSkipIntro(paFeed.skipIntroSec);
            dirty = true;
        }
        if (paFeed.skipOutroSec > 0) {
            prefs.setFeedSkipEnding(paFeed.skipOutroSec);
            dirty = true;
        }
        // PA's category column is a slash-separated list. Split into tags so
        // PA users keep their organizational grouping.
        if (paFeed.category != null && !paFeed.category.trim().isEmpty()
                && !"Virtual podcast".equalsIgnoreCase(paFeed.category.trim())) {
            Set<String> tags = new HashSet<>(prefs.getTags());
            boolean tagsChanged = false;
            for (String raw : paFeed.category.split("/")) {
                String tag = raw.trim();
                if (!tag.isEmpty() && tags.add(tag)) {
                    tagsChanged = true;
                }
            }
            if (tagsChanged) {
                prefs.getTags().clear();
                prefs.getTags().addAll(tags);
                dirty = true;
            }
        }
        if (dirty) {
            DBWriter.setFeedPreferences(prefs);
        }
    }

    private void updateNotification(int done, int total) {
        if (ContextCompat.checkSelfPermission(getApplicationContext(),
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        notificationManager.notify(R.id.notification_id_pa_import, createNotification(done, total));
    }

    private Notification createNotification(int done, int total) {
        Context ctx = getApplicationContext();
        String text = ctx.getString(R.string.podcast_addict_import_subscribing,
                Math.min(done + 1, total), total);
        return new NotificationCompat.Builder(ctx, NotificationUtils.CHANNEL_ID_REFRESHING)
                .setContentTitle(ctx.getString(R.string.podcast_addict_import_notification_title))
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
                new ForegroundInfo(R.id.notification_id_pa_import, createNotification(0, 1)));
    }
}
