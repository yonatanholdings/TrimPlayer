package de.danoeh.antennapod.ui.screen.playlist;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.util.Pair;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.checkbox.MaterialCheckBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * Bottom sheet that manages an episode's playlist membership in one place: every
 * playlist is a row with a checkmark (tap to add/remove — several playlists at
 * once is fine), and "New playlist" creates one (with suggestion chips) and files
 * the episode into it immediately.
 */
public class AddToPlaylistDialog {
    private static final String TAG = "AddToPlaylistDialog";

    private AddToPlaylistDialog() {
    }

    public static void show(@NonNull Context context, @NonNull FeedItem item) {
        Observable.fromCallable(() ->
                        new Pair<>(DBReader.getPlaylists(), DBReader.getPlaylistIdsForItem(item.getId())))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(data -> showSheet(context, item, data.first, data.second),
                        error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private static void showSheet(Context context, FeedItem item,
                                  List<Playlist> playlists, Set<Long> memberIds) {
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        View content = LayoutInflater.from(context).inflate(R.layout.add_to_playlist_sheet, null);
        dialog.setContentView(content);

        TextView subtitle = content.findViewById(R.id.sheet_subtitle);
        subtitle.setText(item.getTitle());

        RecyclerView list = content.findViewById(R.id.playlist_list);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(new RowAdapter(context, item, playlists, memberIds));

        content.findViewById(R.id.create_row).setOnClickListener(v -> {
            dialog.dismiss();
            PlaylistNameDialog.show(context, R.string.add_playlist_label, null,
                    name -> createPlaylistAndAdd(context, name, item));
        });

        // No custom playlist yet: offer one-tap "create + add" suggestion chips
        // so filing the first episode into a Running playlist is a single tap.
        boolean hasCustom = false;
        for (Playlist playlist : playlists) {
            if (!playlist.isDefault()) {
                hasCustom = true;
                break;
            }
        }
        if (!hasCustom) {
            com.google.android.material.chip.ChipGroup chips =
                    content.findViewById(R.id.suggestion_chips);
            chips.setVisibility(View.VISIBLE);
            int[] suggestions = {
                    R.string.playlist_suggestion_running,
                    R.string.playlist_suggestion_driving,
                    R.string.playlist_suggestion_commute,
            };
            for (int labelRes : suggestions) {
                com.google.android.material.chip.Chip chip =
                        new com.google.android.material.chip.Chip(context);
                chip.setText(labelRes);
                chip.setOnClickListener(v -> {
                    dialog.dismiss();
                    createPlaylistAndAdd(context, chip.getText().toString(), item);
                });
                chips.addView(chip);
            }
        }
        dialog.show();
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

    private static class RowAdapter extends RecyclerView.Adapter<RowAdapter.RowHolder> {
        private final Context context;
        private final FeedItem item;
        private final List<Playlist> playlists;
        private final Set<Long> memberIds;
        // Per-playlist count offset from toggles made inside this sheet, so the
        // row's "N episodes" stays truthful without re-querying the DB.
        private final List<Integer> countDelta;

        RowAdapter(Context context, FeedItem item, List<Playlist> playlists, Set<Long> memberIds) {
            this.context = context;
            this.item = item;
            this.playlists = playlists;
            this.memberIds = new HashSet<>(memberIds);
            this.countDelta = new ArrayList<>();
            for (int i = 0; i < playlists.size(); i++) {
                countDelta.add(0);
            }
        }

        @NonNull
        @Override
        public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RowHolder(LayoutInflater.from(context)
                    .inflate(R.layout.add_to_playlist_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RowHolder holder, int position) {
            Playlist playlist = playlists.get(position);
            boolean isMember = memberIds.contains(playlist.getId());
            int count = playlist.getEpisodeCount() + countDelta.get(position);
            // The Queue rides the same sheet, pinned first by the cursor's ordering.
            holder.name.setText(playlist.isDefault()
                    ? de.danoeh.antennapod.storage.preferences.UserPreferences.getTrimUpNextTitle(
                        context.getString(R.string.trim_up_next_label)) : playlist.getName());
            holder.count.setText(context.getResources().getQuantityString(
                    R.plurals.num_episodes, count, count));
            holder.check.setChecked(isMember);
            holder.icon.setImageResource(R.drawable.ic_playlist_music);
            holder.itemView.setOnClickListener(v -> toggle(holder, position));
        }

        private void toggle(RowHolder holder, int position) {
            Playlist playlist = playlists.get(position);
            boolean nowMember = !memberIds.contains(playlist.getId());
            if (nowMember) {
                memberIds.add(playlist.getId());
                countDelta.set(position, countDelta.get(position) + 1);
                DBWriter.addPlaylistItems(playlist.getId(), item);
                EventBus.getDefault().post(new MessageEvent(
                        context.getString(R.string.added_to_playlist_label, playlist.getName())));
            } else {
                memberIds.remove(playlist.getId());
                countDelta.set(position, countDelta.get(position) - 1);
                DBWriter.removePlaylistItem(playlist.getId(), item.getId());
                EventBus.getDefault().post(new MessageEvent(
                        context.getString(R.string.removed_from_playlist_label, playlist.getName())));
            }
            notifyItemChanged(position);
        }

        @Override
        public int getItemCount() {
            return playlists.size();
        }

        static class RowHolder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name;
            final TextView count;
            final MaterialCheckBox check;

            RowHolder(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.row_icon);
                name = itemView.findViewById(R.id.row_name);
                count = itemView.findViewById(R.id.row_count);
                check = itemView.findViewById(R.id.row_check);
            }
        }
    }
}
