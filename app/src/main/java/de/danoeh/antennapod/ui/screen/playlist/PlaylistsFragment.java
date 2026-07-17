package de.danoeh.antennapod.ui.screen.playlist;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.event.PlaylistEvent;
import de.danoeh.antennapod.model.feed.Playlist;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.ui.common.ConfirmationDialog;
import de.danoeh.antennapod.ui.view.EmptyViewHandler;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * Top-level drawer screen listing the user's named playlists. From here they can create, rename,
 * delete and play playlists, and open a playlist to see/edit its episodes.
 */
public class PlaylistsFragment extends Fragment implements PlaylistListAdapter.OnPlaylistActionListener {
    public static final String TAG = "PlaylistsFragment";

    private PlaylistListAdapter adapter;
    private ProgressBar progressBar;
    private EmptyViewHandler emptyView;
    private Disposable disposable;

    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.playlists_fragment, container, false);

        MaterialToolbar toolbar = root.findViewById(R.id.toolbar);
        boolean displayUpArrow = getParentFragmentManager().getBackStackEntryCount() != 0;
        ((MainActivity) requireActivity()).setupToolbarToggle(toolbar, displayUpArrow);

        RecyclerView recyclerView = root.findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new GridLayoutManager(getContext(), 2));
        adapter = new PlaylistListAdapter(getContext(), this);
        recyclerView.setAdapter(adapter);

        progressBar = root.findViewById(R.id.progressBar);
        progressBar.setVisibility(View.VISIBLE);

        emptyView = new EmptyViewHandler(getContext());
        emptyView.attachToRecyclerView(recyclerView);
        emptyView.setIcon(R.drawable.ic_playlist_music);
        emptyView.setTitle(R.string.no_playlists_head_label);
        emptyView.setMessage(R.string.no_playlists_label);
        emptyView.updateAdapter(adapter);

        root.findViewById(R.id.create_playlist_button).setOnClickListener(v -> showCreateDialog());
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        loadPlaylists();
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
        if (disposable != null) {
            disposable.dispose();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void onPlaylistEvent(PlaylistEvent event) {
        loadPlaylists();
    }

    private void loadPlaylists() {
        if (disposable != null) {
            disposable.dispose();
        }
        disposable = Observable.fromCallable(DBReader::getPlaylistsWithCovers)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(playlists -> {
                    progressBar.setVisibility(View.GONE);
                    adapter.setPlaylists(playlists);
                }, error -> progressBar.setVisibility(View.GONE));
    }

    @Override
    public void onPlaylistClicked(Playlist playlist) {
        ((MainActivity) requireActivity()).loadChildFragment(
                PlaylistFragment.newInstance(playlist.getId(), playlist.getName()));
    }

    @Override
    public void onPlaylistPlayClicked(Playlist playlist) {
        PlaylistPlayer.play(requireContext(), playlist.getId());
    }

    @Override
    public void onPlaylistOverflowClicked(Playlist playlist, View anchor) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        popup.getMenu().add(0, 0, 0, R.string.play_label);
        popup.getMenu().add(0, 1, 1, R.string.rename_playlist_label);
        popup.getMenu().add(0, 2, 2, R.string.remove_playlist_label);
        popup.setOnMenuItemClickListener(menuItem -> {
            switch (menuItem.getItemId()) {
                case 0:
                    PlaylistPlayer.play(requireContext(), playlist.getId());
                    return true;
                case 1:
                    showRenameDialog(playlist);
                    return true;
                case 2:
                    showDeleteDialog(playlist);
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    private void showCreateDialog() {
        PlaylistNameDialog.show(requireContext(), R.string.add_playlist_label, null,
                DBWriter::createPlaylist);
    }

    private void showRenameDialog(Playlist playlist) {
        PlaylistNameDialog.show(requireContext(), R.string.rename_playlist_label,
                playlist.getName(), name -> DBWriter.renamePlaylist(playlist.getId(), name));
    }

    private void showDeleteDialog(Playlist playlist) {
        new ConfirmationDialog(getActivity(), R.string.remove_playlist_label,
                getString(R.string.remove_playlist_confirmation_msg, playlist.getName())) {
            @Override
            public void onConfirmButtonPressed(android.content.DialogInterface dialog) {
                DBWriter.removePlaylist(playlist.getId());
            }
        }.createNewDialog().show();
    }
}
