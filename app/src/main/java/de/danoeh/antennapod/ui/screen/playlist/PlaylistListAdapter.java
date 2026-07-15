package de.danoeh.antennapod.ui.screen.playlist;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.Playlist;

/**
 * Renders the list of named playlists on the {@link PlaylistsFragment} screen.
 */
public class PlaylistListAdapter extends RecyclerView.Adapter<PlaylistListAdapter.Holder> {

    public interface OnPlaylistActionListener {
        void onPlaylistClicked(Playlist playlist);

        void onPlaylistOverflowClicked(Playlist playlist, View anchor);
    }

    private final Context context;
    private final OnPlaylistActionListener listener;
    private final List<Playlist> playlists = new ArrayList<>();

    public PlaylistListAdapter(Context context, OnPlaylistActionListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setPlaylists(List<Playlist> newPlaylists) {
        playlists.clear();
        playlists.addAll(newPlaylists);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.playlist_list_item, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Playlist playlist = playlists.get(position);
        holder.name.setText(playlist.getName());
        holder.count.setText(context.getResources().getQuantityString(
                R.plurals.num_episodes, playlist.getEpisodeCount(), playlist.getEpisodeCount()));
        holder.itemView.setOnClickListener(v -> listener.onPlaylistClicked(playlist));
        holder.overflow.setOnClickListener(v -> listener.onPlaylistOverflowClicked(playlist, v));
    }

    @Override
    public int getItemCount() {
        return playlists.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView count;
        final ImageView overflow;

        Holder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.playlist_name);
            count = itemView.findViewById(R.id.playlist_count);
            overflow = itemView.findViewById(R.id.playlist_overflow);
        }
    }
}
