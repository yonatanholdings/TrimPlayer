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
                    for (Feed feed : syncable) {
                        boolean want = selected.contains(feed.getId());
                        boolean have = ruleFeedIds.contains(feed.getId());
                        if (want && !have) {
                            DBWriter.addPlaylistAutoFeed(playlistId, feed.getId(), now);
                        } else if (!want && have) {
                            DBWriter.removePlaylistAutoFeed(playlistId, feed.getId());
                        }
                    }
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }
}
