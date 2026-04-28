package de.danoeh.antennapod.storage.importexport;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;

public class PodcastAddictStateWorker extends Worker {
    private static final String TAG = "PAStateWorker";
    private static final String WORK_ID = "de.danoeh.antennapod.PodcastAddictStateApply";
    private static final int MAX_ATTEMPTS = 20; // 30 s linear backoff → retries at 30s, 60s, ..., 9.5 min; typically done in < 2 min

    /**
     * Enqueue the worker with no initial delay. If episodes aren't in the DB yet
     * (feeds still refreshing) it will retry every 30 seconds (linear backoff),
     * up to MAX_ATTEMPTS times.
     */
    public static void enqueue(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(PodcastAddictStateWorker.class)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_ID, ExistingWorkPolicy.REPLACE, request);
    }

    public PodcastAddictStateWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        List<PodcastAddictImporter.EpisodeState> states =
                PodcastAddictImporter.loadEpisodeStates(getApplicationContext());
        if (states.isEmpty()) {
            return Result.success();
        }

        Log.d(TAG, "Attempt " + (getRunAttemptCount() + 1) + "/" + MAX_ATTEMPTS
                + " (" + states.size() + " states pending)");

        // Build lookup maps
        Map<String, PodcastAddictImporter.EpisodeState> byGuid = new HashMap<>();
        Map<String, PodcastAddictImporter.EpisodeState> byUrl = new HashMap<>();
        for (PodcastAddictImporter.EpisodeState state : states) {
            if (state.guid != null && !state.guid.isEmpty()) {
                byGuid.put(state.guid, state);
            }
            if (state.downloadUrl != null && !state.downloadUrl.isEmpty()) {
                byUrl.put(state.downloadUrl, state);
            }
        }

        // Try to apply states to all episodes currently in the DB
        List<PodcastAddictImporter.EpisodeState> remaining = new ArrayList<>(states);
        List<Feed> feeds = DBReader.getFeedList();
        for (Feed feed : feeds) {
            List<FeedItem> items = DBReader.getFeedItemList(feed, FeedItemFilter.unfiltered(),
                    SortOrder.DATE_NEW_OLD, 0, Integer.MAX_VALUE);
            for (FeedItem item : items) {
                PodcastAddictImporter.EpisodeState state = findState(item, byGuid, byUrl);
                if (state == null) {
                    continue;
                }
                applyState(item, state);
                remaining.remove(state);
                // Remove from maps so we don't match the same state twice
                if (state.guid != null) byGuid.remove(state.guid);
                if (state.downloadUrl != null) byUrl.remove(state.downloadUrl);
            }
        }

        int applied = states.size() - remaining.size();
        Log.d(TAG, "Applied " + applied + " states, " + remaining.size() + " still pending");

        if (remaining.isEmpty()) {
            PodcastAddictImporter.clearEpisodeStates(getApplicationContext());
            Log.d(TAG, "All states applied — done");
            return Result.success();
        }

        if (getRunAttemptCount() >= MAX_ATTEMPTS - 1) {
            // Give up after MAX_ATTEMPTS — clear remaining to avoid retrying forever
            Log.d(TAG, "Max attempts reached, giving up on " + remaining.size() + " unmatched states");
            PodcastAddictImporter.clearEpisodeStates(getApplicationContext());
            return Result.success();
        }

        // Save only the unmatched states and retry
        try {
            PodcastAddictImporter.saveEpisodeStates(getApplicationContext(), remaining);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save remaining states", e);
        }
        return Result.retry();
    }

    private PodcastAddictImporter.EpisodeState findState(FeedItem item,
            Map<String, PodcastAddictImporter.EpisodeState> byGuid,
            Map<String, PodcastAddictImporter.EpisodeState> byUrl) {
        String guid = item.getItemIdentifier();
        if (guid != null && byGuid.containsKey(guid)) {
            return byGuid.get(guid);
        }
        if (item.getMedia() != null) {
            String url = item.getMedia().getDownloadUrl();
            if (url != null && byUrl.containsKey(url)) {
                return byUrl.get(url);
            }
        }
        return null;
    }

    private void applyState(FeedItem item, PodcastAddictImporter.EpisodeState state) {
        if (state.played) {
            DBWriter.markItemPlayed(item, FeedItem.PLAYED, false);

            if (item.getMedia() != null) {
                FeedMedia media = item.getMedia();
                if (state.durationMs > 0 && media.getDuration() == 0) {
                    media.setDuration((int) state.durationMs);
                }
                int playedMs = state.durationMs > 0 ? (int) state.durationMs : media.getDuration();
                media.setPlayedDuration(playedMs);
                // Only attribute to a month in the chart if we have a real playback date.
                // Falling back to now would pile all imported history into the current month.
                if (state.playbackDateMs > 0) {
                    media.setLastPlayedTimeStatistics(state.playbackDateMs);
                    media.setLastPlayedTimeHistory(new Date(state.playbackDateMs));
                } else {
                    media.setLastPlayedTimeHistory(new Date(System.currentTimeMillis()));
                }
                media.setPosition(0);
                DBWriter.setFeedMediaPlaybackInformation(media);

                // Only record speed savings when we have a real playback date.
                // Using System.currentTimeMillis() as a fallback would pile all imported
                // history into the current month, inflating the Time Saved chart.
                if (state.playbackSpeed > 1.0f && state.playbackDateMs > 0) {
                    int durationMs = media.getDuration() > 0 ? media.getDuration() : (int) state.durationMs;
                    int speedSavedMs = (int) (durationMs * (1.0 - 1.0 / state.playbackSpeed));
                    if (speedSavedMs > 0) {
                        DBWriter.recordSkipEvent(item.getId(), "speed", speedSavedMs, state.playbackDateMs);
                    }
                }
            }
        } else if (state.positionMs > 0 && item.getMedia() != null) {
            FeedMedia media = item.getMedia();
            media.setPosition(state.positionMs);
            if (state.durationMs > 0 && media.getDuration() == 0) {
                media.setDuration((int) state.durationMs);
            }
            if (media.getLastPlayedTimeHistory() == null) {
                long playbackDate = state.playbackDateMs > 0
                        ? state.playbackDateMs : System.currentTimeMillis();
                media.setLastPlayedTimeHistory(new Date(playbackDate));
                if (state.playbackDateMs > 0) {
                    media.setLastPlayedTimeStatistics(state.playbackDateMs);
                }
            }
            DBWriter.setFeedMediaPlaybackInformation(media);
        }

        if (state.favorite) {
            DBWriter.addFavoriteItem(item);
        }
    }
}
