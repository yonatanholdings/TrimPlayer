package de.danoeh.antennapod.ui.screen.playlist;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.Playlist;
import de.danoeh.antennapod.ui.common.Converter;

/**
 * Renders the playlist cards on the {@link PlaylistsFragment} grid: a 2x2 cover
 * collage (single full-bleed cover when the playlist draws on one show, a tinted
 * monogram tile while empty), episode count over a scrim, a play button, and the
 * name + total listening time below.
 */
public class PlaylistListAdapter extends RecyclerView.Adapter<PlaylistListAdapter.Holder> {

    public interface OnPlaylistActionListener {
        void onPlaylistClicked(Playlist playlist);

        void onPlaylistPlayClicked(Playlist playlist);

        void onPlaylistOverflowClicked(Playlist playlist, View anchor);
    }

    // Monogram tile palette for empty playlists — indexed by name hash so a
    // playlist keeps its color, and different playlists tend to differ.
    private static final int[] TILE_COLORS = {
            0xFF7C5CFF, 0xFF4F8CFF, 0xFF00A896, 0xFFFF6B6B, 0xFFB4654A, 0xFF9C27B0,
    };

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
        View view = LayoutInflater.from(context).inflate(R.layout.playlist_card, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Playlist playlist = playlists.get(position);
        // The default playlist IS the queue: fixed localized name, pinned first.
        holder.name.setText(playlist.isDefault()
                ? context.getString(R.string.queue_label) : playlist.getName());
        holder.countBadge.setText(context.getResources().getQuantityString(
                R.plurals.num_episodes, playlist.getEpisodeCount(), playlist.getEpisodeCount()));
        if (playlist.getTotalDurationMs() > 0) {
            // Compact form ("1.6 hours") — the card column is narrow.
            holder.meta.setText(Converter.shortLocalizedDuration(
                    context, playlist.getTotalDurationMs() / 1000));
            holder.meta.setVisibility(View.VISIBLE);
        } else {
            holder.meta.setText(context.getString(R.string.playlist_empty_meta_label));
            holder.meta.setVisibility(View.VISIBLE);
        }
        bindCollage(holder, playlist);
        holder.playButton.setVisibility(playlist.getEpisodeCount() > 0 ? View.VISIBLE : View.GONE);
        holder.itemView.setOnClickListener(v -> listener.onPlaylistClicked(playlist));
        holder.playButton.setOnClickListener(v -> listener.onPlaylistPlayClicked(playlist));
        holder.overflow.setOnClickListener(v -> listener.onPlaylistOverflowClicked(playlist, v));
    }

    private void bindCollage(Holder holder, Playlist playlist) {
        List<String> covers = playlist.getCoverUrls();
        boolean empty = covers.isEmpty();
        holder.placeholderBg.setVisibility(empty ? View.VISIBLE : View.GONE);
        holder.placeholderInitial.setVisibility(empty ? View.VISIBLE : View.GONE);
        holder.coverFull.setVisibility(!empty && covers.size() == 1 ? View.VISIBLE : View.GONE);
        holder.collageGrid.setVisibility(covers.size() >= 2 ? View.VISIBLE : View.GONE);
        if (empty) {
            String name = playlist.isDefault()
                    ? context.getString(R.string.queue_label)
                    : playlist.getName() == null ? "" : playlist.getName().trim();
            // floorMod: hashCode() can be Integer.MIN_VALUE, where Math.abs stays negative.
            int color = TILE_COLORS[Math.floorMod(
                    name.toLowerCase(Locale.ROOT).hashCode(), TILE_COLORS.length)];
            holder.placeholderBg.setBackgroundColor(color);
            holder.placeholderInitial.setText(name.isEmpty()
                    ? "♪" : name.substring(0, 1).toUpperCase(Locale.ROOT));
            return;
        }
        if (covers.size() == 1) {
            loadCover(holder.coverFull, covers.get(0));
            return;
        }
        // 2-3 covers repeat around the 2x2 grid so no cell is ever blank.
        for (int i = 0; i < holder.gridCells.length; i++) {
            loadCover(holder.gridCells[i], covers.get(i % covers.size()));
        }
    }

    private void loadCover(ImageView view, String url) {
        Glide.with(view)
                .load(url)
                .placeholder(new android.graphics.drawable.ColorDrawable(Color.DKGRAY))
                .centerCrop()
                .dontAnimate()
                .into(view);
    }

    @Override
    public int getItemCount() {
        return playlists.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView meta;
        final TextView countBadge;
        final ImageView overflow;
        final View playButton;
        final View placeholderBg;
        final TextView placeholderInitial;
        final View collageGrid;
        final ImageView coverFull;
        final ImageView[] gridCells;

        Holder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.playlist_name);
            meta = itemView.findViewById(R.id.playlist_meta);
            countBadge = itemView.findViewById(R.id.episode_count_badge);
            overflow = itemView.findViewById(R.id.playlist_overflow);
            playButton = itemView.findViewById(R.id.play_button);
            placeholderBg = itemView.findViewById(R.id.placeholder_bg);
            placeholderInitial = itemView.findViewById(R.id.placeholder_initial);
            collageGrid = itemView.findViewById(R.id.collage_grid);
            coverFull = itemView.findViewById(R.id.cover_full);
            gridCells = new ImageView[] {
                    itemView.findViewById(R.id.cover1),
                    itemView.findViewById(R.id.cover2),
                    itemView.findViewById(R.id.cover3),
                    itemView.findViewById(R.id.cover4),
            };
        }
    }
}
