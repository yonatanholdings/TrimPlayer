package de.danoeh.antennapod.ui.screen.playback.audio;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.databinding.BookmarkListDialogBinding;
import de.danoeh.antennapod.event.BookmarksChangedEvent;
import de.danoeh.antennapod.model.feed.Bookmark;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.ui.common.Converter;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Bottom sheet listing the current episode's bookmarks. Tapping a bookmark
 * seeks to its position; each row also offers note editing and deletion, and
 * a button bookmarks the current playback position.
 *
 * <p>Refreshes live on {@link BookmarksChangedEvent}, which DBWriter posts
 * after every bookmark write.
 */
public class BookmarkListDialog extends BottomSheetDialogFragment {
    private static final String ARG_FEED_ITEM_ID = "feedItemId";

    private BookmarkListDialogBinding viewBinding;
    private PlaybackController controller;
    private Disposable mediaWarmup;
    private Disposable loadDisposable;
    /** True once the controller's media is cached off-thread, so position reads
     *  and seeks don't trigger a main-thread DB read (see SegmentListDialog). */
    private boolean mediaReady;
    private long feedItemId;

    public static BookmarkListDialog newInstance(long feedItemId) {
        BookmarkListDialog dialog = new BookmarkListDialog();
        Bundle args = new Bundle();
        args.putLong(ARG_FEED_ITEM_ID, feedItemId);
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        feedItemId = requireArguments().getLong(ARG_FEED_ITEM_ID);
    }

    @Override
    public void onStart() {
        super.onStart();
        controller = new PlaybackController(getActivity()) {
            @Override
            public void loadMediaInfo() {
            }
        };
        controller.init();
        EventBus.getDefault().register(this);
        mediaWarmup = Single.fromCallable(() -> controller.getMedia() != null)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ready -> mediaReady = ready, error -> mediaReady = false);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mediaWarmup != null) {
            mediaWarmup.dispose();
            mediaWarmup = null;
        }
        if (loadDisposable != null) {
            loadDisposable.dispose();
            loadDisposable = null;
        }
        if (controller != null) {
            controller.release();
            controller = null;
        }
        EventBus.getDefault().unregister(this);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        viewBinding = BookmarkListDialogBinding.inflate(inflater, container, false);
        viewBinding.addBookmarkButton.setOnClickListener(v -> addAtCurrentPosition());
        loadBookmarks();
        return viewBinding.getRoot();
    }

    private void loadBookmarks() {
        if (loadDisposable != null) {
            loadDisposable.dispose();
        }
        loadDisposable = Single.fromCallable(() -> DBReader.getBookmarks(feedItemId))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::renderRows, error -> { });
    }

    private void renderRows(List<Bookmark> bookmarks) {
        if (viewBinding == null) {
            return;
        }
        viewBinding.bookmarkListContainer.removeAllViews();
        viewBinding.bookmarkListCount.setText(String.valueOf(bookmarks.size()));
        if (bookmarks.isEmpty()) {
            viewBinding.bookmarkListSubtitle.setText(R.string.bookmark_list_empty);
            return;
        }
        viewBinding.bookmarkListSubtitle.setText(R.string.bookmark_list_subtitle);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (Bookmark bookmark : bookmarks) {
            View row = inflater.inflate(R.layout.bookmark_list_row,
                    viewBinding.bookmarkListContainer, false);
            bindRow(row, bookmark);
            viewBinding.bookmarkListContainer.addView(row);
        }
    }

    private void bindRow(View row, Bookmark bookmark) {
        TextView position = row.findViewById(R.id.bookmarkPosition);
        TextView note = row.findViewById(R.id.bookmarkNote);
        position.setText(Converter.getDurationStringLong(bookmark.getPosition()));
        note.setText(bookmark.getNote().isEmpty()
                ? getString(R.string.bookmark_no_note) : bookmark.getNote());
        row.setOnClickListener(v -> jumpTo(bookmark));
        row.findViewById(R.id.bookmarkEditButton).setOnClickListener(v ->
                de.danoeh.antennapod.ui.screen.saved.BookmarkNoteDialog.show(requireContext(), bookmark));
        row.findViewById(R.id.bookmarkDeleteButton).setOnClickListener(v ->
                DBWriter.deleteBookmark(bookmark.getId(), feedItemId));
    }

    private void jumpTo(Bookmark bookmark) {
        if (controller != null && mediaReady) {
            controller.seekTo(bookmark.getPosition());
            dismiss();
        }
    }

    private void addAtCurrentPosition() {
        if (controller == null || !mediaReady) {
            return;
        }
        DBWriter.addBookmark(feedItemId, Math.max(0, controller.getPosition()), null);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void onBookmarksChanged(BookmarksChangedEvent event) {
        if (event.feedItemId == feedItemId) {
            loadBookmarks();
        }
    }
}
