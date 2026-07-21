package de.danoeh.antennapod.ui.screen.playlist;

import android.os.Bundle;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Collections;
import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.actionbutton.ItemActionButton;
import de.danoeh.antennapod.actionbutton.PlayActionButton;
import de.danoeh.antennapod.actionbutton.PlayLocalActionButton;
import de.danoeh.antennapod.actionbutton.StreamActionButton;
import de.danoeh.antennapod.event.PlaylistEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.ui.MenuItemUtils;
import de.danoeh.antennapod.ui.common.ConfirmationDialog;
import de.danoeh.antennapod.ui.common.Converter;
import de.danoeh.antennapod.ui.screen.feed.ItemSortDialog;
import de.danoeh.antennapod.ui.swipeactions.SwipeActions;
import de.danoeh.antennapod.ui.episodeslist.EpisodeItemListAdapter;
import de.danoeh.antennapod.ui.episodeslist.EpisodeItemViewHolder;
import de.danoeh.antennapod.ui.episodeslist.EpisodesListFragment;
import de.danoeh.antennapod.ui.episodeslist.FeedItemMenuHandler;

import android.widget.EditText;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * Shows the episodes of a single named playlist, in playlist order. Reuses the shared episode-list
 * machinery; adds a "Play" action that starts the whole playlist (so playback follows the same
 * playlist to the end) and per-episode "Remove from playlist".
 */
public class PlaylistFragment extends EpisodesListFragment {
    public static final String TAG = "PlaylistFragment";
    private static final String ARG_PLAYLIST_ID = "playlistId";
    private static final String ARG_PLAYLIST_NAME = "playlistName";

    private long playlistId;
    private String playlistName;
    private int lastLoadedCount;

    public static PlaylistFragment newInstance(long playlistId, String playlistName) {
        PlaylistFragment fragment = new PlaylistFragment();
        Bundle args = new Bundle();
        args.putLong(ARG_PLAYLIST_ID, playlistId);
        args.putString(ARG_PLAYLIST_NAME, playlistName);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        playlistId = getArguments().getLong(ARG_PLAYLIST_ID);
        playlistName = getArguments().getString(ARG_PLAYLIST_NAME);
    }

    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View root = super.onCreateView(inflater, container, savedInstanceState);
        toolbar.inflateMenu(R.menu.playlist);
        toolbar.setTitle(playlistName);
        emptyView.setIcon(R.drawable.ic_playlist_music);
        emptyView.setTitle(R.string.no_playlists_head_label);
        emptyView.setMessage(R.string.playlist_empty_label);
        // A playlist is not tied to a feed refresh.
        swipeRefreshLayout.setEnabled(false);
        // Replace the base swipe helper with a drag-enabled one so the play order
        // can be rearranged with the row's drag handle (like the queue).
        swipeActions.detach();
        swipeActions = new PlaylistSwipeActions();
        swipeActions.setFilter(getFilter());
        swipeActions.attachTo(recyclerView);
        return root;
    }

    @Override
    protected void updateToolbar() {
        super.updateToolbar();
        // "8 episodes · 6h 40m" under the playlist name.
        long totalMs = 0;
        for (FeedItem item : episodes) {
            if (item.getMedia() != null) {
                totalMs += item.getMedia().getDuration();
            }
        }
        String subtitle = getResources().getQuantityString(
                R.plurals.num_episodes, episodes.size(), episodes.size());
        if (totalMs > 0) {
            subtitle += " · " + Converter.getDurationStringLocalized(requireContext(), totalMs);
        }
        toolbar.setSubtitle(subtitle);
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        final int id = item.getItemId();
        if (id == R.id.play_playlist_item) {
            PlaylistPlayer.play(requireContext(), playlistId);
            return true;
        } else if (id == R.id.playlist_sort_item) {
            PlaylistSortDialog.newInstance(playlistId).show(getChildFragmentManager().beginTransaction(), "SortDialog");
            return true;
        } else if (id == R.id.auto_add_shows_item) {
            AutoAddShowsDialog.show(requireContext(), playlistId);
            return true;
        } else if (id == R.id.rename_playlist_item) {
            showRenameDialog();
            return true;
        } else if (id == R.id.clear_playlist_item) {
            showClearDialog();
            return true;
        } else if (id == R.id.remove_playlist_item) {
            showDeleteDialog();
            return true;
        }
        return super.onMenuItemClick(item);
    }

    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.remove_from_playlist_item) {
            FeedItem selected = listAdapter.getLongPressedItem();
            if (selected != null) {
                DBWriter.removePlaylistItem(playlistId, selected.getId());
            }
            return true;
        }
        return super.onContextItemSelected(item);
    }

    @NonNull
    @Override
    protected List<FeedItem> loadData() {
        List<FeedItem> items = DBReader.getPlaylistItems(playlistId);
        lastLoadedCount = items.size();
        return items;
    }

    @NonNull
    @Override
    protected List<FeedItem> loadMoreData(int page) {
        return Collections.emptyList();
    }

    @Override
    protected int loadTotalItemCount() {
        return lastLoadedCount;
    }

    @Override
    protected FeedItemFilter getFilter() {
        return new FeedItemFilter();
    }

    @Override
    protected String getFragmentTag() {
        return TAG;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void onPlaylistEvent(PlaylistEvent event) {
        if (event.playlistId == 0 || event.playlistId == playlistId) {
            loadItems();
        }
    }

    @Override
    protected EpisodeItemListAdapter createListAdapter() {
        return new EpisodeItemListAdapter(getActivity()) {
            @Override
            public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
                super.onCreateContextMenu(menu, v, menuInfo);
                if (!inActionMode()) {
                    menu.findItem(R.id.multi_select).setVisible(true);
                    // In a playlist, offer "remove from playlist" instead of "add to playlist".
                    MenuItem remove = menu.findItem(R.id.remove_from_playlist_item);
                    if (remove != null) {
                        remove.setVisible(true);
                    }
                    MenuItem add = menu.findItem(R.id.add_to_playlist_item);
                    if (add != null) {
                        add.setVisible(false);
                    }
                }
                MenuItemUtils.setOnClickListeners(menu, PlaylistFragment.this::onContextItemSelected);
            }

            @Override
            protected void onSelectedItemsUpdated() {
                super.onSelectedItemsUpdated();
                FeedItemMenuHandler.onPrepareMenu(floatingSelectMenu.getMenu(), getSelectedItems());
                floatingSelectMenu.updateItemVisibility();
            }

            @Override
            @android.annotation.SuppressLint("ClickableViewAccessibility")
            protected void afterBindViewHolder(EpisodeItemViewHolder holder, int pos) {
                super.afterBindViewHolder(holder, pos);
                // Drag handle rearranges the play order (mirrors the queue).
                if (inActionMode()) {
                    holder.dragHandle.setVisibility(View.GONE);
                    holder.dragHandle.setOnTouchListener(null);
                } else {
                    holder.dragHandle.setVisibility(View.VISIBLE);
                    holder.dragHandle.setOnTouchListener((v, event) -> {
                        if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                            swipeActions.startDrag(holder);
                        }
                        return false;
                    });
                }
                final FeedItem item = getItem(pos);
                if (item == null || item.getMedia() == null) {
                    return;
                }
                // Playing an episode from within a playlist makes that playlist the active playback
                // context, so playback continues with the next episode in the same playlist.
                holder.secondaryActionButton.setOnClickListener(v -> {
                    ItemActionButton button = ItemActionButton.forItem(item);
                    if (button instanceof PlayActionButton
                            || button instanceof StreamActionButton
                            || button instanceof PlayLocalActionButton) {
                        PlaybackPreferences.setActivePlaylistId(playlistId);
                    }
                    button.onClick(v.getContext());
                });
            }
        };
    }

    private void showRenameDialog() {
        final EditText input = new EditText(requireContext());
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(playlistName);
        input.setSelection(playlistName == null ? 0 : playlistName.length());
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout container = new android.widget.FrameLayout(requireContext());
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(input);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.rename_playlist_label)
                .setView(container)
                .setPositiveButton(R.string.confirm_label, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        playlistName = name;
                        toolbar.setTitle(name);
                        DBWriter.renamePlaylist(playlistId, name);
                    }
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    private void showClearDialog() {
        new ConfirmationDialog(getActivity(), R.string.clear_playlist_label,
                getString(R.string.clear_playlist_confirmation_msg, playlistName)) {
            @Override
            public void onConfirmButtonPressed(android.content.DialogInterface dialog) {
                DBWriter.setPlaylistItems(playlistId, Collections.emptyList());
            }
        }.createNewDialog().show();
    }

    private void showDeleteDialog() {
        new ConfirmationDialog(getActivity(), R.string.remove_playlist_label,
                getString(R.string.remove_playlist_confirmation_msg, playlistName)) {
            @Override
            public void onConfirmButtonPressed(android.content.DialogInterface dialog) {
                DBWriter.removePlaylist(playlistId);
                getParentFragmentManager().popBackStack();
            }
        }.createNewDialog().show();
    }

    /** Drag-to-reorder for the play order (same pattern as the queue): moves are
     *  mirrored in memory while dragging, then persisted once on drop. */
    private class PlaylistSwipeActions extends SwipeActions {
        private int dragFrom = -1;
        private int dragTo = -1;

        PlaylistSwipeActions() {
            super(ItemTouchHelper.UP | ItemTouchHelper.DOWN, PlaylistFragment.this, TAG);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView,
                              @NonNull RecyclerView.ViewHolder viewHolder,
                              @NonNull RecyclerView.ViewHolder target) {
            int from = viewHolder.getBindingAdapterPosition();
            int to = target.getBindingAdapterPosition();
            if (from < 0 || to < 0 || from >= episodes.size() || to >= episodes.size()) {
                return false;
            }
            if (dragFrom == -1) {
                dragFrom = from;
            }
            dragTo = to;
            episodes.add(to, episodes.remove(from));
            listAdapter.notifyItemMoved(from, to);
            return true;
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return false;
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView,
                              @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            if (dragFrom != -1 && dragTo != -1 && dragFrom != dragTo) {
                // Persist the new order; the resulting PlaylistEvent reloads us
                // (and the sync worker pushes it to the account).
                DBWriter.setPlaylistItems(playlistId, new java.util.ArrayList<>(episodes));
            }
            dragFrom = dragTo = -1;
        }
    }

    /** One-shot re-sort of the playlist's episodes, reusing the queue's sort orders.
     *  Unlike the queue there is no "keep sorted" mode: this reorders once, and the
     *  playlist's manual drag order takes over again from there. */
    public static class PlaylistSortDialog extends ItemSortDialog {
        private static final String ARG_PLAYLIST_ID = "playlistId";

        public static PlaylistSortDialog newInstance(long playlistId) {
            Bundle bundle = new Bundle();
            bundle.putLong(ARG_PLAYLIST_ID, playlistId);
            PlaylistSortDialog dialog = new PlaylistSortDialog();
            dialog.setArguments(bundle);
            return dialog;
        }

        @Override
        protected void onAddItem(int title, SortOrder ascending, SortOrder descending, boolean ascendingIsDefault) {
            if (ascending == SortOrder.EPISODE_FILENAME_A_Z || ascending == SortOrder.SIZE_SMALL_LARGE) {
                return;
            }
            super.onAddItem(title, ascending, descending, ascendingIsDefault);
        }

        @Override
        protected void onSelectionChanged() {
            super.onSelectionChanged();
            DBWriter.reorderPlaylist(getArguments().getLong(ARG_PLAYLIST_ID), sortOrder, true);
        }
    }
}
