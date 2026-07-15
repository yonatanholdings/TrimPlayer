package de.danoeh.antennapod.ui.screen.playlist;

import android.content.Context;
import android.util.Log;
import android.widget.EditText;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.Playlist;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import org.greenrobot.eventbus.EventBus;

/**
 * Lets the user add an episode to one of their named playlists, or create a new playlist on the fly.
 */
public class AddToPlaylistDialog {
    private static final String TAG = "AddToPlaylistDialog";

    private AddToPlaylistDialog() {
    }

    public static void show(@NonNull Context context, @NonNull FeedItem item) {
        Observable.fromCallable(DBReader::getPlaylists)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(playlists -> showChooser(context, item, playlists),
                        error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private static void showChooser(Context context, FeedItem item, List<Playlist> playlists) {
        final List<CharSequence> labels = new ArrayList<>();
        labels.add(context.getString(R.string.add_playlist_label));
        for (Playlist playlist : playlists) {
            labels.add(playlist.getName());
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.add_to_playlist_label)
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                    if (which == 0) {
                        showCreateAndAdd(context, item);
                    } else {
                        Playlist playlist = playlists.get(which - 1);
                        DBWriter.addPlaylistItems(playlist.getId(), item);
                        EventBus.getDefault().post(new MessageEvent(
                                context.getString(R.string.added_to_playlist_label, playlist.getName())));
                    }
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    private static void showCreateAndAdd(Context context, FeedItem item) {
        final EditText input = new EditText(context);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint(R.string.playlist_name_hint);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.add_playlist_label)
                .setView(wrapInput(context, input))
                .setPositiveButton(R.string.confirm_label, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        return;
                    }
                    createPlaylistAndAdd(context, name, item);
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    /** Wraps an input view in a padded container so it sits nicely inside the dialog body. */
    private static android.view.View wrapInput(Context context, EditText input) {
        int padding = (int) (16 * context.getResources().getDisplayMetrics().density);
        android.widget.FrameLayout container = new android.widget.FrameLayout(context);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(input);
        return container;
    }

    private static void createPlaylistAndAdd(Context context, String name, FeedItem item) {
        Observable.fromCallable(() -> DBWriter.createPlaylist(name).get())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(playlistId -> {
                    DBWriter.addPlaylistItems(playlistId, item);
                    EventBus.getDefault().post(new MessageEvent(
                            context.getString(R.string.added_to_playlist_label, name)));
                }, error -> Log.e(TAG, Log.getStackTraceString(error)));
    }
}
