package de.danoeh.antennapod.ui.screen.playlist;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.util.Pair;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Multi-choice picker of the user's subscriptions: checked shows auto-add their
 * new episodes to the playlist. Only episodes published after a show is checked
 * are auto-added (the rule's creation time is its cutoff), so checking a show
 * never floods the playlist with its backlog.
 */
public final class AutoAddShowsDialog {
    private static final String TAG = "AutoAddShowsDialog";

    private AutoAddShowsDialog() {
    }

    public static void show(@NonNull Context context, long playlistId) {
        Observable.fromCallable(() ->
                        new Pair<>(DBReader.getFeedList(), DBReader.getPlaylistAutoFeedIds(playlistId)))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(data -> showChooser(context, playlistId, data.first, data.second),
                        error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    /** Same chooser for the Queue (= the default playlist); resolves its id off-main. */
    public static void showForQueue(@NonNull Context context) {
        Observable.fromCallable(DBReader::getDefaultPlaylistId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(defaultId -> show(context, defaultId),
                        error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private static void showChooser(Context context, long playlistId,
                                    List<Feed> feeds, Set<Long> ruleFeedIds) {
        List<Feed> syncable = new ArrayList<>();
        for (Feed feed : feeds) {
            if (feed.getDownloadUrl() != null && !feed.getDownloadUrl().isEmpty()) {
                syncable.add(feed);
            }
        }
        if (syncable.isEmpty()) {
            new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.trim_auto_add_label)
                    .setMessage(R.string.trim_auto_add_no_subscriptions)
                    .setPositiveButton(R.string.confirm_label, null)
                    .show();
            return;
        }
        String[] titles = new String[syncable.size()];
        boolean[] checked = new boolean[syncable.size()];
        for (int i = 0; i < syncable.size(); i++) {
            titles[i] = syncable.get(i).getTitle();
            checked[i] = ruleFeedIds.contains(syncable.get(i).getId());
        }
        Set<Long> selected = new HashSet<>(ruleFeedIds);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.trim_auto_add_label)
                .setMultiChoiceItems(titles, checked, (dialog, which, isChecked) -> {
                    long feedId = syncable.get(which).getId();
                    if (isChecked) {
                        selected.add(feedId);
                    } else {
                        selected.remove(feedId);
                    }
                })
                .setPositiveButton(R.string.confirm_label, (dialog, which) -> {
                    long now = System.currentTimeMillis();
                    List<Feed> newlyWatched = new ArrayList<>();
                    for (Feed feed : syncable) {
                        boolean want = selected.contains(feed.getId());
                        boolean have = ruleFeedIds.contains(feed.getId());
                        if (want && !have) {
                            DBWriter.addPlaylistAutoFeed(playlistId, feed.getId(), now);
                            newlyWatched.add(feed);
                        } else if (!want && have) {
                            DBWriter.removePlaylistAutoFeed(playlistId, feed.getId());
                        }
                    }
                    if (!newlyWatched.isEmpty()) {
                        offerUnplayedBackfill(context, playlistId, newlyWatched);
                    }
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    /** After new shows were checked: offer to also pull their EXISTING unplayed
     *  episodes into the playlist now (one-shot; the rule itself only catches
     *  episodes published from now on). The added items sync like any manual
     *  add, so other devices and the web receive them as playlist items. */
    private static void offerUnplayedBackfill(Context context, long playlistId,
                                              List<Feed> newlyWatched) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.trim_auto_add_backfill_title)
                .setMessage(context.getResources().getQuantityString(
                        R.plurals.trim_auto_add_backfill_message,
                        newlyWatched.size(), newlyWatched.size()))
                .setPositiveButton(R.string.trim_auto_add_backfill_yes, (d, w) ->
                        backfillUnplayed(playlistId, newlyWatched))
                .setNegativeButton(R.string.trim_auto_add_backfill_no, null)
                .show();
    }

    private static void backfillUnplayed(long playlistId, List<Feed> feeds) {
        Observable.fromCallable(() -> doBackfillUnplayed(playlistId, feeds))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(count -> Log.d(TAG, "backfilled " + count + " unplayed episode(s)"),
                        error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private static int doBackfillUnplayed(long playlistId, List<Feed> feeds) throws Exception {
        List<FeedItem> toAdd = new ArrayList<>();
        for (Feed feed : feeds) {
            // Oldest first so the backlog lands in listening order.
            for (FeedItem item : DBReader.getFeedItemList(feed,
                    new FeedItemFilter(FeedItemFilter.UNPLAYED),
                    SortOrder.DATE_OLD_NEW, 0, Integer.MAX_VALUE)) {
                if (item.getMedia() != null) {
                    toAdd.add(item);
                }
            }
        }
        if (!toAdd.isEmpty()) {
            DBWriter.addPlaylistItems(playlistId, toAdd.toArray(new FeedItem[0])).get();
        }
        return toAdd.size();
    }
}
