package de.danoeh.antennapod.ui.screen.playlist;

import android.content.Context;
import android.util.Log;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.actionbutton.ItemActionButton;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.schedulers.Schedulers;

import org.greenrobot.eventbus.EventBus;

/**
 * Starts playback of a named playlist: it marks the playlist as the active playback context (so the
 * playback service auto-advances through the same playlist) and plays the first playable episode.
 */
public final class PlaylistPlayer {
    private static final String TAG = "PlaylistPlayer";

    private PlaylistPlayer() {
    }

    /** Plays the whole playlist starting from its first playable episode. */
    public static void play(Context context, long playlistId) {
        playFrom(context, playlistId, 0);
    }

    /**
     * Plays a playlist starting at {@code startItemId} (its own row's play button). Passing
     * {@code 0} starts from the first playable episode.
     */
    public static void playFrom(Context context, long playlistId, long startItemId) {
        Maybe.fromCallable(() -> {
            for (FeedItem item : DBReader.getPlaylistItems(playlistId)) {
                if (item.getMedia() == null) {
                    continue;
                }
                if (startItemId == 0 || item.getId() == startItemId) {
                    return item;
                }
            }
            return null;
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(item -> {
                    PlaybackPreferences.setActivePlaylistId(playlistId);
                    ItemActionButton.forItem(item).onClick(context);
                }, error -> Log.e(TAG, Log.getStackTraceString(error)),
                        () -> EventBus.getDefault().post(
                                new MessageEvent(context.getString(R.string.playlist_empty_label))));
    }
}
