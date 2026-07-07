package de.danoeh.antennapod.ui.screen.saved;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.bottomsheet.BottomSheetBehavior;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.event.BookmarksChangedEvent;
import de.danoeh.antennapod.model.feed.Bookmark;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.playback.service.PlaybackServiceStarter;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.ui.common.Converter;
import de.danoeh.antennapod.ui.view.EmptyViewHandler;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Bookmarks tab of the "Saved" screen: every bookmark across all episodes,
 * newest first. Tapping a bookmark starts (or restarts) its episode at the
 * bookmarked position — the same seed-position-then-start recipe as opening
 * a shared-position link.
 */
public class BookmarksTabFragment extends Fragment {
    public static final String TAG = "BookmarksTabFragment";

    private BookmarksAdapter adapter;
    private EmptyViewHandler emptyView;
    private ProgressBar progressBar;
    private Disposable loadDisposable;
    private Disposable playDisposable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.bookmarks_tab_fragment, container, false);
        RecyclerView recyclerView = root.findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new BookmarksAdapter();
        recyclerView.setAdapter(adapter);
        progressBar = root.findViewById(R.id.progressBar);

        emptyView = new EmptyViewHandler(getContext());
        emptyView.attachToRecyclerView(recyclerView);
        emptyView.setIcon(R.drawable.ic_bookmark);
        emptyView.setTitle(R.string.no_bookmarks_head_label);
        emptyView.setMessage(R.string.no_bookmarks_label);
        emptyView.updateAdapter(adapter);
        emptyView.hide();
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        loadBookmarks();
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
        if (loadDisposable != null) {
            loadDisposable.dispose();
        }
        if (playDisposable != null) {
            playDisposable.dispose();
        }
    }

    private void loadBookmarks() {
        if (loadDisposable != null) {
            loadDisposable.dispose();
        }
        loadDisposable = Single.fromCallable(DBReader::getAllBookmarksWithItems)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(bookmarks -> {
                    progressBar.setVisibility(View.GONE);
                    adapter.update(bookmarks);
                }, error -> {
                    progressBar.setVisibility(View.GONE);
                    Log.e(TAG, Log.getStackTraceString(error));
                });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void onBookmarksChanged(BookmarksChangedEvent event) {
        loadBookmarks();
    }

    /** Seed the bookmark position onto the episode (with a fresh last-played stamp
     *  so sync LWW and smart-resume don't undo it — same recipe as opening a
     *  shared-position link), then start playback and expand the player. */
    private void playAt(DBReader.BookmarkWithItem row) {
        FeedMedia media = row.item.getMedia();
        if (media == null) {
            return;
        }
        if (playDisposable != null) {
            playDisposable.dispose();
        }
        playDisposable = Single.fromCallable(() -> {
            long now = System.currentTimeMillis();
            media.setPosition(row.bookmark.getPosition());
            media.setLastPlayedTimeStatistics(now);
            media.setLastPlayedTimeHistory(new Date(now));
            DBWriter.setFeedMediaPlaybackInformation(media).get();
            return media;
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(seededMedia -> {
                    new PlaybackServiceStarter(requireContext(), seededMedia)
                            .callEvenIfRunning(true)
                            .shouldStreamThisTime(!seededMedia.isDownloaded())
                            .start();
                    ((MainActivity) getActivity()).getBottomSheet()
                            .setState(BottomSheetBehavior.STATE_EXPANDED);
                }, error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private class BookmarksAdapter extends RecyclerView.Adapter<BookmarkHolder> {
        private List<DBReader.BookmarkWithItem> rows = new ArrayList<>();

        void update(List<DBReader.BookmarkWithItem> newRows) {
            rows = newRows;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public BookmarkHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.saved_bookmark_row, parent, false);
            return new BookmarkHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull BookmarkHolder holder, int position) {
            holder.bind(rows.get(position));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private class BookmarkHolder extends RecyclerView.ViewHolder {
        private final ImageView cover;
        private final TextView title;
        private final TextView detail;

        BookmarkHolder(View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.bookmarkCover);
            title = itemView.findViewById(R.id.bookmarkEpisodeTitle);
            detail = itemView.findViewById(R.id.bookmarkDetail);
        }

        void bind(DBReader.BookmarkWithItem row) {
            FeedItem item = row.item;
            Bookmark bookmark = row.bookmark;
            title.setText(item.getTitle());
            String time = Converter.getDurationStringLong(bookmark.getPosition());
            detail.setText(bookmark.getNote().isEmpty()
                    ? time : getString(R.string.bookmark_row_detail, time, bookmark.getNote()));
            Glide.with(cover)
                    .load(item.getImageLocation())
                    .apply(new RequestOptions()
                            .placeholder(R.color.light_gray)
                            .fitCenter()
                            .dontAnimate())
                    .into(cover);
            itemView.setOnClickListener(v -> playAt(row));
            itemView.findViewById(R.id.bookmarkEditButton).setOnClickListener(v ->
                    BookmarkNoteDialog.show(requireContext(), bookmark));
            itemView.findViewById(R.id.bookmarkDeleteButton).setOnClickListener(v ->
                    DBWriter.deleteBookmark(bookmark.getId(), bookmark.getFeedItemId()));
        }
    }
}
