package de.danoeh.antennapod.ui.screen.playlist;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.Playlist;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Subscribe-time destination picker: "Where should new episodes go?" — Inbox
 * (the stock behavior), the Queue, an existing playlist, or a new one. Any pick
 * subscribes and runs {@code onSubscribed}; a non-Inbox pick also creates the
 * synced auto-add rule (cutoff = now, so the show's backlog never floods in).
 * Dismissing without picking cancels the subscribe entirely.
 */
public final class SubscribeDestinationDialog {
    private static final String TAG = "SubscribeDestDialog";

    private SubscribeDestinationDialog() {
    }

    public static void show(@NonNull Context context, @NonNull Feed feed,
                            @NonNull Runnable onSubscribed) {
        Observable.fromCallable(DBReader::getPlaylists)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(playlists -> showChooser(context, feed, playlists, onSubscribed),
                        error -> {
                            Log.e(TAG, Log.getStackTraceString(error));
                            onSubscribed.run(); // never block subscribing on a picker failure
                        });
    }

    private static void showChooser(Context context, Feed feed, List<Playlist> playlists,
                                    Runnable onSubscribed) {
        List<String> labels = new ArrayList<>();
        List<Playlist> targets = new ArrayList<>(); // parallel; null = Inbox
        labels.add(context.getString(R.string.trim_subscribe_dest_inbox));
        targets.add(null);
        for (Playlist playlist : playlists) {
            labels.add(playlist.isDefault()
                    ? context.getString(R.string.queue_label) : playlist.getName());
            targets.add(playlist);
        }
        labels.add(context.getString(R.string.add_playlist_label));

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.trim_subscribe_dest_title)
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                    if (which == labels.size() - 1) {
                        // New playlist: name it, then subscribe with a rule on it.
                        PlaylistNameDialog.show(context, R.string.add_playlist_label, null,
                                name -> createAndSubscribe(context, feed, name, onSubscribed));
                        return;
                    }
                    Playlist target = targets.get(which);
                    if (target != null) {
                        installRule(feed, target.getId());
                    }
                    onSubscribed.run();
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    private static void createAndSubscribe(Context context, Feed feed, String name,
                                           Runnable onSubscribed) {
        Observable.fromCallable(() -> DBWriter.createPlaylist(name).get())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(playlistId -> {
                    installRule(feed, playlistId);
                    onSubscribed.run();
                }, error -> {
                    Log.e(TAG, Log.getStackTraceString(error));
                    onSubscribed.run();
                });
    }

    /** Create the auto-add rule AND seed the show's newest episode into the
     *  playlist right away — the rule alone (cutoff = now) leaves the playlist
     *  visibly empty until the show's next release, which reads as broken. */
    private static void installRule(Feed feed, long playlistId) {
        DBWriter.addPlaylistAutoFeed(playlistId, feed.getId(), System.currentTimeMillis());
        de.danoeh.antennapod.model.feed.FeedItem newest = feed.getMostRecentItem();
        if (newest == null || newest.getMedia() == null) {
            for (de.danoeh.antennapod.model.feed.FeedItem item : feed.getItems()) {
                if (item.getMedia() != null) {
                    newest = item;
                    break;
                }
            }
        }
        if (newest != null && newest.getMedia() != null) {
            DBWriter.addPlaylistItems(playlistId, newest);
        }
    }
}
