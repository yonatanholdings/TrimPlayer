package de.danoeh.antennapod.ui.screen.playback.audio;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetBehavior;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.ui.appstartintent.MainActivityStarter;
import de.danoeh.antennapod.ui.appstartintent.MediaButtonStarter;
import de.danoeh.antennapod.ui.appstartintent.OnlineFeedviewActivityStarter;
import de.danoeh.antennapod.ui.chapters.ChapterUtils;
import de.danoeh.antennapod.ui.episodes.PlaybackSpeedUtils;
import de.danoeh.antennapod.ui.episodes.TimeSpeedConverter;
import de.danoeh.antennapod.ui.screen.playback.MediaPlayerErrorDialog;
import de.danoeh.antennapod.ui.screen.playback.PlayButton;
import de.danoeh.antennapod.ui.screen.playback.SleepTimerDialog;
import de.danoeh.antennapod.ui.screen.playback.TranscriptDialogFragment;
import de.danoeh.antennapod.ui.screen.playback.VariableSpeedDialog;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Collections;
import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.ui.common.Converter;
import de.danoeh.antennapod.ui.screen.feed.preferences.SkipPreferenceDialog;
import de.danoeh.antennapod.event.FavoritesEvent;
import de.danoeh.antennapod.event.PlayerErrorEvent;
import de.danoeh.antennapod.event.UnreadItemsUpdateEvent;
import de.danoeh.antennapod.event.playback.BufferUpdateEvent;
import de.danoeh.antennapod.event.playback.PlaybackPositionEvent;
import de.danoeh.antennapod.event.playback.PlaybackServiceEvent;
import de.danoeh.antennapod.event.playback.SleepTimerUpdatedEvent;
import de.danoeh.antennapod.event.playback.SpeedChangedEvent;
import de.danoeh.antennapod.ui.episodeslist.FeedItemMenuHandler;
import de.danoeh.antennapod.model.feed.Chapter;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.Playable;
import de.danoeh.antennapod.playback.cast.CastEnabledActivity;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Shows the audio player.
 */
public class AudioPlayerFragment extends Fragment implements
        ChapterSeekBar.OnSeekBarChangeListener, MaterialToolbar.OnMenuItemClickListener {
    public static final String TAG = "AudioPlayerFragment";
    public static final int POS_COVER = 0;
    public static final int POS_DESCRIPTION = 1;
    private static final int NUM_CONTENT_FRAGMENTS = 2;

    private ImageButton butPlaybackSpeed;
    private TextView txtvPlaybackSpeed;
    private ViewPager2 pager;
    private TextView txtvPosition;
    private TextView txtvLength;
    private ChapterSeekBar sbPosition;
    /** Trim segments currently overlaid on the seek bar, kept so a long-press
     *  can resolve which segment was touched and open the edit sheet. */
    private java.util.List<de.danoeh.antennapod.playback.service.trim.TrimClient.Segment> lastSegments;
    /** Fraction [0..1] of the last down-touch on the seek bar. */
    private float lastSeekTouchFraction = -1;
    private ImageButton butRev;
    private TextView txtvRev;
    private PlayButton butPlay;
    private ImageButton butFF;
    private TextView txtvFF;
    private ImageButton butSkip;
    private MaterialToolbar toolbar;
    private ProgressBar progressIndicator;
    private CardView cardViewSeek;
    private TextView txtvSeek;

    private PlaybackController controller;
    private Disposable disposable;
    private boolean showTimeLeft;
    private boolean seekedToChapterStart = false;
    private int currentChapterIndex = -1;
    private int duration;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        View root = inflater.inflate(R.layout.audioplayer_fragment, container, false);
        root.setOnTouchListener((v, event) -> true); // Avoid clicks going through player to fragments below
        toolbar = root.findViewById(R.id.toolbar);
        toolbar.setTitle("");
        toolbar.setNavigationOnClickListener(v ->
                ((MainActivity) getActivity()).getBottomSheet().setState(BottomSheetBehavior.STATE_COLLAPSED));
        toolbar.setOnMenuItemClickListener(this);

        ExternalPlayerFragment externalPlayerFragment = new ExternalPlayerFragment();
        getChildFragmentManager().beginTransaction()
                .replace(R.id.playerFragment, externalPlayerFragment, ExternalPlayerFragment.TAG)
                .commit();

        butPlaybackSpeed = root.findViewById(R.id.butPlaybackSpeed);
        txtvPlaybackSpeed = root.findViewById(R.id.txtvPlaybackSpeed);
        sbPosition = root.findViewById(R.id.sbPosition);
        txtvPosition = root.findViewById(R.id.txtvPosition);
        txtvLength = root.findViewById(R.id.txtvLength);
        butRev = root.findViewById(R.id.butRev);
        txtvRev = root.findViewById(R.id.txtvRev);
        butPlay = root.findViewById(R.id.butPlay);
        butFF = root.findViewById(R.id.butFF);
        txtvFF = root.findViewById(R.id.txtvFF);
        butSkip = root.findViewById(R.id.butSkip);
        progressIndicator = root.findViewById(R.id.progLoading);
        cardViewSeek = root.findViewById(R.id.cardViewSeek);
        txtvSeek = root.findViewById(R.id.txtvSeek);

        setupLengthTextView();
        setupControlButtons();
        butPlaybackSpeed.setOnClickListener(v -> new VariableSpeedDialog().show(getChildFragmentManager(), null));
        sbPosition.setOnSeekBarChangeListener(this);
        setupSegmentEditEntry();

        pager = root.findViewById(R.id.pager);
        pager.setAdapter(new AudioPlayerPagerAdapter(this));
        // Required for getChildAt(int) in ViewPagerBottomSheetBehavior to return the correct page
        pager.setOffscreenPageLimit((int) NUM_CONTENT_FRAGMENTS);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                pager.post(() -> {
                    if (getActivity() != null) {
                        // By the time this is posted, the activity might be closed again.
                        ((MainActivity) getActivity()).getBottomSheet().updateScrollingChild();
                    }
                });
            }
        });

        return root;
    }

    private void setChapterDividers(Playable media) {
        if (media == null) {
            return;
        }

        float[] dividerPos = null;

        if (media.getChapters() != null && !media.getChapters().isEmpty()) {
            List<Chapter> chapters = media.getChapters();
            dividerPos = new float[chapters.size()];

            for (int i = 0; i < chapters.size(); i++) {
                dividerPos[i] = chapters.get(i).getStart() / (float) duration;
            }
        }

        sbPosition.setDividerPos(dividerPos);
        setSegmentMarkers(media);
    }

    /** Overlay trim segments (intros/outros/ads) on the seek bar. Reads the
     *  same cache PlaybackService populates so this works as soon as segments
     *  land — either from the warm-path cache hit on episode load or from the
     *  /analyze polling response (see {@link #onTrimSegmentsUnlocked}). */
    private void setSegmentMarkers(Playable media) {
        lastSegments = null;
        // The duration field is seeded from controller.getDuration(), which routes
        // to the live player and returns INVALID_TIME until the episode finishes
        // preparing. On the warm-cache path nothing re-runs this once it resolves,
        // so an early call would clear the overlay permanently. Fall back to the
        // media's stored (feed/DB) duration, which is stable from load time.
        int effectiveDuration = duration > 0 ? duration : (media != null ? media.getDuration() : 0);
        if (effectiveDuration <= 0 || !(media instanceof de.danoeh.antennapod.model.feed.FeedMedia)) {
            Log.d(TAG, "setSegmentMarkers: no valid duration (field=" + duration
                    + ", media=" + (media != null ? media.getDuration() : -1) + ") - clearing overlay");
            sbPosition.setSegments(null, null);
            return;
        }
        de.danoeh.antennapod.model.feed.FeedMedia fm =
                (de.danoeh.antennapod.model.feed.FeedMedia) media;
        if (fm.getItem() == null) {
            sbPosition.setSegments(null, null);
            return;
        }
        String guid = fm.getItem().getItemIdentifier();
        java.util.List<de.danoeh.antennapod.playback.service.trim.TrimClient.Segment> segs =
                de.danoeh.antennapod.playback.service.trim.TrimSegmentCache.get(requireContext(), guid);
        if (segs == null || segs.isEmpty()) {
            Log.d(TAG, "setSegmentMarkers: cache empty for guid=" + guid + " - clearing overlay");
            sbPosition.setSegments(null, null);
            return;
        }
        lastSegments = segs;
        float[] starts = new float[segs.size()];
        float[] ends = new float[segs.size()];
        for (int i = 0; i < segs.size(); i++) {
            de.danoeh.antennapod.playback.service.trim.TrimClient.Segment s = segs.get(i);
            // Clamp to [0,1]: a backend segment can run past the real episode end
            // (seen on a Mel Robbins outro), which would otherwise draw the marker
            // past the seek bar.
            starts[i] = Math.max(0f, Math.min(1f, (float) (s.start * 1000.0 / effectiveDuration)));
            ends[i] = Math.max(0f, Math.min(1f, (float) (s.end * 1000.0 / effectiveDuration)));
        }
        Log.d(TAG, "setSegmentMarkers: applied " + segs.size() + " segments (effDuration="
                + effectiveDuration + ", field=" + duration + ")");
        sbPosition.setSegments(starts, ends);
    }

    /** Long-press a trim segment on the seek bar to open the local edit sheet.
     *  We record the down-touch fraction (an OnTouchListener that returns false
     *  so normal seeking is unaffected) and, on long-press, resolve which
     *  segment sits under that point. */
    @SuppressLint("ClickableViewAccessibility")
    private void setupSegmentEditEntry() {
        sbPosition.setLongClickable(true);
        sbPosition.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                int usable = sbPosition.getWidth() - sbPosition.getPaddingLeft() - sbPosition.getPaddingRight();
                if (usable > 0) {
                    lastSeekTouchFraction = Math.max(0f, Math.min(1f,
                            (event.getX() - sbPosition.getPaddingLeft()) / usable));
                }
            }
            return false;
        });
        sbPosition.setOnLongClickListener(v -> openSegmentEditAtTouch());
    }

    private boolean openSegmentEditAtTouch() {
        if (lastSegments == null || lastSegments.isEmpty() || duration <= 0 || lastSeekTouchFraction < 0
                || controller == null
                || !(controller.getMedia() instanceof de.danoeh.antennapod.model.feed.FeedMedia)) {
            return false;
        }
        de.danoeh.antennapod.model.feed.FeedMedia fm =
                (de.danoeh.antennapod.model.feed.FeedMedia) controller.getMedia();
        if (fm.getItem() == null) {
            return false;
        }
        float touchedSec = lastSeekTouchFraction * duration / 1000f;
        de.danoeh.antennapod.playback.service.trim.TrimClient.Segment hit = null;
        for (de.danoeh.antennapod.playback.service.trim.TrimClient.Segment s : lastSegments) {
            if (touchedSec >= s.start && touchedSec < s.end) {
                hit = s;
                break;
            }
        }
        if (hit == null) {
            return false;
        }
        EditSegmentDialog.newInstance(fm.getItem().getItemIdentifier(), fm.getStreamUrl(),
                        hit, duration / 1000f, false)
                .show(getChildFragmentManager(), "EditSegmentDialog");
        return true;
    }

    private void setupControlButtons() {
        butRev.setOnClickListener(v -> {
            if (controller != null) {
                int curr = controller.getPosition();
                controller.seekTo(curr - UserPreferences.getRewindSecs() * 1000);
            }
        });
        butRev.setOnLongClickListener(v -> {
            SkipPreferenceDialog.showSkipPreference(getContext(),
                    SkipPreferenceDialog.SkipDirection.SKIP_REWIND, txtvRev);
            return true;
        });
        butPlay.setOnClickListener(v -> {
            if (controller != null) {
                controller.init();
                controller.playPause();
            }
        });
        butFF.setOnClickListener(v -> {
            if (controller != null) {
                int curr = controller.getPosition();
                controller.seekTo(curr + UserPreferences.getFastForwardSecs() * 1000);
            }
        });
        butFF.setOnLongClickListener(v -> {
            SkipPreferenceDialog.showSkipPreference(getContext(),
                    SkipPreferenceDialog.SkipDirection.SKIP_FORWARD, txtvFF);
            return false;
        });
        butSkip.setOnClickListener(v -> getActivity().sendBroadcast(
                MediaButtonStarter.createIntent(getContext(), KeyEvent.KEYCODE_MEDIA_NEXT)));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onUnreadItemsUpdate(UnreadItemsUpdateEvent event) {
        if (controller == null) {
            return;
        }
        updatePosition(new PlaybackPositionEvent(controller.getPosition(),
                controller.getDuration()));
    }

    /** A local segment edit changed the cache — repaint the seek-bar overlay. */
    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void onTrimSegmentsEdited(de.danoeh.antennapod.event.TrimSegmentsEditedEvent event) {
        if (controller != null && controller.getMedia() != null) {
            setSegmentMarkers(controller.getMedia());
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlaybackServiceChanged(PlaybackServiceEvent event) {
        if (event.action == PlaybackServiceEvent.Action.SERVICE_SHUT_DOWN) {
            ((MainActivity) getActivity()).getBottomSheet().setState(BottomSheetBehavior.STATE_COLLAPSED);
        }
    }

    private void setupLengthTextView() {
        showTimeLeft = UserPreferences.shouldShowRemainingTime();
        txtvLength.setOnClickListener(v -> {
            if (controller == null) {
                return;
            }
            showTimeLeft = !showTimeLeft;
            UserPreferences.setShowRemainTimeSetting(showTimeLeft);
            updatePosition(new PlaybackPositionEvent(controller.getPosition(),
                    controller.getDuration()));
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void updatePlaybackSpeedButton(SpeedChangedEvent event) {
        String speedStr = new DecimalFormat("0.00").format(event.getNewSpeed());
        txtvPlaybackSpeed.setText(speedStr);
    }

    private void loadMediaInfo(boolean includingChapters) {
        if (disposable != null) {
            disposable.dispose();
        }
        disposable = Maybe.<Playable>create(emitter -> {
            Playable media = controller.getMedia();
            if (media != null) {
                if (includingChapters) {
                    ChapterUtils.loadChapters(media, getContext(), false);
                }
                emitter.onSuccess(media);
            } else {
                emitter.onComplete();
            }
        })
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(media -> {
            updateUi(media);
            if (media.getChapters() == null && !includingChapters) {
                loadMediaInfo(true);
            }
        }, error -> Log.e(TAG, Log.getStackTraceString(error)),
            () -> updateUi(null));
    }

    private PlaybackController newPlaybackController() {
        return new PlaybackController(getActivity()) {
            @Override
            protected void updatePlayButtonShowsPlay(boolean showPlay) {
                butPlay.setIsShowPlay(showPlay);
            }

            @Override
            public void loadMediaInfo() {
                AudioPlayerFragment.this.loadMediaInfo(false);
            }

            @Override
            public void onPlaybackEnd() {
                ((MainActivity) getActivity()).getBottomSheet().setState(BottomSheetBehavior.STATE_COLLAPSED);
            }
        };
    }

    private void updateUi(Playable media) {
        if (controller == null || media == null) {
            return;
        }
        duration = controller.getDuration();
        updatePosition(new PlaybackPositionEvent(media.getPosition(), media.getDuration()));
        updatePlaybackSpeedButton(new SpeedChangedEvent(PlaybackSpeedUtils.getCurrentPlaybackSpeed(media)));
        setChapterDividers(media);
        setupOptionsMenu(media);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void onTrimSegmentsUnlocked(
            de.danoeh.antennapod.event.TrimSegmentsUnlockedEvent event) {
        // Segments just landed from a mid-playback /analyze response — refresh
        // the overlay without waiting for the next loadMediaInfo tick.
        if (controller != null) {
            setSegmentMarkers(controller.getMedia());
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void sleepTimerUpdate(SleepTimerUpdatedEvent event) {
        if (event.isOver()) {
            toolbar.getMenu().findItem(R.id.set_sleeptimer_item).setVisible(true);
            toolbar.getMenu().findItem(R.id.disable_sleeptimer_item).setVisible(false);
        }
        if (event.isCancelled() || event.wasJustEnabled() || event.isOver()) {
            AudioPlayerFragment.this.loadMediaInfo(false);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        controller = newPlaybackController();
        controller.init();
        loadMediaInfo(false);
        EventBus.getDefault().register(this);
        txtvRev.setText(NumberFormat.getInstance().format(UserPreferences.getRewindSecs()));
        txtvFF.setText(NumberFormat.getInstance().format(UserPreferences.getFastForwardSecs()));
    }

    @Override
    public void onStop() {
        super.onStop();
        controller.release();
        controller = null;
        progressIndicator.setVisibility(View.GONE); // Controller released; we will not receive buffering updates
        EventBus.getDefault().unregister(this);
        if (disposable != null) {
            disposable.dispose();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void bufferUpdate(BufferUpdateEvent event) {
        if (event.hasStarted()) {
            progressIndicator.setVisibility(View.VISIBLE);
        } else if (event.hasEnded()) {
            progressIndicator.setVisibility(View.GONE);
        } else if (controller != null && controller.isStreaming()) {
            sbPosition.setSecondaryProgress((int) (event.getProgress() * sbPosition.getMax()));
        } else {
            sbPosition.setSecondaryProgress(0);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void updatePosition(PlaybackPositionEvent event) {
        if (controller == null || txtvPosition == null || txtvLength == null || sbPosition == null) {
            return;
        }

        TimeSpeedConverter converter = new TimeSpeedConverter(controller.getCurrentPlaybackSpeedMultiplier());
        int currentPosition = converter.convert(event.getPosition());
        int duration = converter.convert(event.getDuration());
        int remainingTime = converter.convert(Math.max(event.getDuration() - event.getPosition(), 0));
        @Nullable Playable media = controller.getMedia();
        if (media != null) {
            currentChapterIndex = Chapter.getAfterPosition(media.getChapters(), currentPosition);
        }
        Log.d(TAG, "currentPosition " + Converter.getDurationStringLong(currentPosition));
        if (currentPosition == Playable.INVALID_TIME || duration == Playable.INVALID_TIME) {
            Log.w(TAG, "Could not react to position observer update because of invalid time");
            return;
        }
        // updateUi() seeds the duration field from controller.getDuration(), which is
        // INVALID_TIME until the player prepares. Once a valid live duration arrives,
        // backfill the field and re-apply the chapter/segment overlays — setChapterDividers
        // (and setSegmentMarkers) had bailed on the invalid duration with no other retry on
        // the warm-cache path. Fires at most once per loaded episode.
        if (AudioPlayerFragment.this.duration <= 0 && event.getDuration() > 0 && media != null) {
            AudioPlayerFragment.this.duration = event.getDuration();
            setChapterDividers(media);
        }
        txtvPosition.setText(Converter.getDurationStringLong(currentPosition));
        txtvPosition.setContentDescription(getString(R.string.position,
                Converter.getDurationStringLocalized(getContext(), currentPosition)));
        showTimeLeft = UserPreferences.shouldShowRemainingTime();
        if (showTimeLeft) {
            txtvLength.setContentDescription(getString(R.string.remaining_time,
                    Converter.getDurationStringLocalized(getContext(), remainingTime)));
            txtvLength.setText(((remainingTime > 0) ? "-" : "") + Converter.getDurationStringLong(remainingTime));
        } else {
            txtvLength.setContentDescription(getString(R.string.chapter_duration,
                    Converter.getDurationStringLocalized(getContext(), duration)));
            txtvLength.setText(Converter.getDurationStringLong(duration));
        }

        if (!sbPosition.isPressed()) {
            float progress = ((float) event.getPosition()) / event.getDuration();
            sbPosition.setProgress((int) (progress * sbPosition.getMax()));
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void favoritesChanged(FavoritesEvent event) {
        AudioPlayerFragment.this.loadMediaInfo(false);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void mediaPlayerError(PlayerErrorEvent event) {
        MediaPlayerErrorDialog.show(getActivity(), event);
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (controller == null || txtvLength == null) {
            return;
        }

        if (fromUser) {
            float prog = progress / ((float) seekBar.getMax());
            TimeSpeedConverter converter = new TimeSpeedConverter(controller.getCurrentPlaybackSpeedMultiplier());
            int position = converter.convert((int) (prog * controller.getDuration()));
            int newChapterIndex = Chapter.getAfterPosition(controller.getMedia().getChapters(), position);
            if (newChapterIndex > -1) {
                if (!sbPosition.isPressed() && currentChapterIndex != newChapterIndex) {
                    currentChapterIndex = newChapterIndex;
                    position = (int) controller.getMedia().getChapters().get(currentChapterIndex).getStart();
                    seekedToChapterStart = true;
                    controller.seekTo(position);
                    updateUi(controller.getMedia());
                    sbPosition.highlightCurrentChapter();
                }
                txtvSeek.setText(controller.getMedia().getChapters().get(newChapterIndex).getTitle()
                                + "\n" + Converter.getDurationStringLong(position));
            } else {
                txtvSeek.setText(Converter.getDurationStringLong(position));
            }
        } else if (duration != controller.getDuration()) {
            updateUi(controller.getMedia());
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        // interrupt position Observer, restart later
        cardViewSeek.setScaleX(.8f);
        cardViewSeek.setScaleY(.8f);
        cardViewSeek.animate()
                .setInterpolator(new FastOutSlowInInterpolator())
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(200)
                .start();
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        if (controller != null) {
            if (seekedToChapterStart) {
                seekedToChapterStart = false;
            } else {
                float prog = seekBar.getProgress() / ((float) seekBar.getMax());
                controller.seekTo((int) (prog * controller.getDuration()));
            }
        }
        cardViewSeek.setScaleX(1f);
        cardViewSeek.setScaleY(1f);
        cardViewSeek.animate()
                .setInterpolator(new FastOutSlowInInterpolator())
                .alpha(0f).scaleX(.8f).scaleY(.8f)
                .setDuration(200)
                .start();
    }

    public void setupOptionsMenu(Playable media) {
        if (toolbar.getMenu().size() == 0) {
            toolbar.inflateMenu(R.menu.mediaplayer);
        }
        if (controller == null) {
            return;
        }
        boolean isFeedMedia = media instanceof FeedMedia;
        toolbar.getMenu().findItem(R.id.open_feed_item).setVisible(isFeedMedia);
        if (isFeedMedia) {
            FeedItemMenuHandler.onPrepareMenu(toolbar.getMenu(),
                    Collections.singletonList(((FeedMedia) media).getItem()));
        }

        toolbar.getMenu().findItem(R.id.set_sleeptimer_item).setVisible(!controller.sleepTimerActive());
        toolbar.getMenu().findItem(R.id.disable_sleeptimer_item).setVisible(controller.sleepTimerActive());

        // Trim segment list: always available on a podcast episode. The sheet lists
        // any detected segments AND offers "Mark a skip we missed", so it's useful
        // even before analysis or when nothing was found. The only requirement is a
        // FeedMedia with an item — its guid (itemIdentifier) keys the segment cache;
        // non-podcast media has no guid to attach edits to.
        boolean isPodcastEpisode = isFeedMedia && ((FeedMedia) media).getItem() != null;
        toolbar.getMenu().findItem(R.id.trim_segments_item).setVisible(isPodcastEpisode);

        // Bookmarks need a stored FeedItem id to attach to, so they share the
        // podcast-episode gate with the segment list.
        toolbar.getMenu().findItem(R.id.add_bookmark_item).setVisible(isPodcastEpisode);
        toolbar.getMenu().findItem(R.id.bookmarks_item).setVisible(isPodcastEpisode);

        // Volume boost: an explicit per-podcast control, available whenever a
        // podcast episode is playing on a device that supports the LoudnessEnhancer.
        // Complements the volume-up-at-max key gesture (which only works while the
        // app is in the foreground).
        boolean canBoost = isFeedMedia
                && de.danoeh.antennapod.model.feed.VolumeAdaptionSetting.isBoostSupported();
        toolbar.getMenu().findItem(R.id.volume_boost_item).setVisible(canBoost);

        ((CastEnabledActivity) getActivity()).requestCastButton(toolbar.getMenu());
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        if (controller == null) {
            return false;
        }
        Playable media = controller.getMedia();
        if (media == null) {
            return false;
        }

        final @Nullable FeedItem feedItem = (media instanceof FeedMedia) ? ((FeedMedia) media).getItem() : null;
        if (feedItem != null && FeedItemMenuHandler.onMenuItemClicked(this, item.getItemId(), feedItem)) {
            return true;
        }

        final int itemId = item.getItemId();
        if (itemId == R.id.disable_sleeptimer_item || itemId == R.id.set_sleeptimer_item) {
            new SleepTimerDialog().show(getChildFragmentManager(), "SleepTimerDialog");
            return true;
        } else if (itemId == R.id.transcript_item) {
            new TranscriptDialogFragment().show(
                    getActivity().getSupportFragmentManager(), TranscriptDialogFragment.TAG);
            return true;
        } else if (itemId == R.id.trim_segments_item) {
            if (feedItem != null) {
                String url = media instanceof FeedMedia ? ((FeedMedia) media).getStreamUrl() : null;
                SegmentListDialog.newInstance(feedItem.getItemIdentifier(), url,
                                media.getDuration() / 1000f)
                        .show(getChildFragmentManager(), "SegmentListDialog");
            }
            return true;
        } else if (itemId == R.id.add_bookmark_item) {
            if (feedItem != null) {
                int position = Math.max(0, controller.getPosition());
                de.danoeh.antennapod.storage.database.DBWriter.addBookmark(feedItem.getId(), position, null);
                // Same design as the segment-skip confirmation (PlaybackService's
                // "Skipped ad · saved 30s"): a plain toast, which also renders
                // above the expanded player sheet without any z-order games.
                android.widget.Toast.makeText(getContext(),
                        getString(R.string.bookmark_added_at, Converter.getDurationStringLong(position)),
                        android.widget.Toast.LENGTH_SHORT).show();
            }
            return true;
        } else if (itemId == R.id.bookmarks_item) {
            if (feedItem != null) {
                BookmarkListDialog.newInstance(feedItem.getId())
                        .show(getChildFragmentManager(), "BookmarkListDialog");
            }
            return true;
        } else if (itemId == R.id.volume_boost_item) {
            if (media instanceof FeedMedia && ((FeedMedia) media).getItem() != null
                    && ((FeedMedia) media).getItem().getFeed() != null) {
                showVolumeBoostDialog(((FeedMedia) media).getItem().getFeed());
            }
            return true;
        } else if (itemId == R.id.open_feed_item) {
            if (feedItem != null) {
                openFeed(feedItem.getFeed());
            }
            return true;
        }
        return false;
    }

    /** Per-podcast volume-boost picker. Writes the feed's volumeAdaptionSetting and
     *  posts {@link VolumeAdaptionChangedEvent} so PlaybackService applies it to the
     *  running player — the same path the volume-up gesture and feed settings use. */
    private void showVolumeBoostDialog(Feed feed) {
        if (feed == null || feed.getPreferences() == null) {
            return;
        }
        de.danoeh.antennapod.model.feed.VolumeAdaptionSetting[] options = {
            de.danoeh.antennapod.model.feed.VolumeAdaptionSetting.OFF,
            de.danoeh.antennapod.model.feed.VolumeAdaptionSetting.LIGHT_BOOST,
            de.danoeh.antennapod.model.feed.VolumeAdaptionSetting.MEDIUM_BOOST,
            de.danoeh.antennapod.model.feed.VolumeAdaptionSetting.HEAVY_BOOST,
        };
        String[] labels = {
            getString(R.string.volume_boost_off),
            getString(R.string.volume_boost_light),
            getString(R.string.volume_boost_medium),
            getString(R.string.volume_boost_heavy),
        };
        de.danoeh.antennapod.model.feed.VolumeAdaptionSetting currentRaw =
                feed.getPreferences().getVolumeAdaptionSetting();
        int checked = 0;
        for (int i = 0; i < options.length; i++) {
            if (options[i] == currentRaw) {
                checked = i;
                break;
            }
        }
        final long feedId = feed.getId();
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.volume_boost_menu)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    de.danoeh.antennapod.model.feed.VolumeAdaptionSetting chosen = options[which];
                    feed.getPreferences().setVolumeAdaptionSetting(chosen);
                    io.reactivex.rxjava3.core.Completable.fromAction(() ->
                                    de.danoeh.antennapod.storage.database.DBWriter
                                            .setFeedPreferences(feed.getPreferences()))
                            .subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
                            .subscribe();
                    EventBus.getDefault().post(
                            new de.danoeh.antennapod.event.settings.VolumeAdaptionChangedEvent(
                                    chosen, feedId));
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    private void openFeed(Feed feed) {
        if (feed == null) {
            return;
        }
        if (feed.getState() == Feed.STATE_NOT_SUBSCRIBED) {
            startActivity(new OnlineFeedviewActivityStarter(getContext(), feed.getDownloadUrl()).getIntent());
        } else {
            new MainActivityStarter(getContext()).withOpenFeed(feed.getId()).withClearTop().start();
        }
    }

    public void fadePlayerToToolbar(float slideOffset) {
        float playerFadeProgress = Math.max(0.0f, Math.min(0.2f, slideOffset - 0.2f)) / 0.2f;
        View player = getView().findViewById(R.id.playerFragment);
        player.setAlpha(1 - playerFadeProgress);
        player.setVisibility(playerFadeProgress > 0.99f ? View.INVISIBLE : View.VISIBLE);
        float toolbarFadeProgress = Math.max(0.0f, Math.min(0.2f, slideOffset - 0.6f)) / 0.2f;
        toolbar.setAlpha(toolbarFadeProgress);
        toolbar.setVisibility(toolbarFadeProgress < 0.01f ? View.INVISIBLE : View.VISIBLE);
    }

    private static class AudioPlayerPagerAdapter extends FragmentStateAdapter {
        private static final String TAG = "AudioPlayerPagerAdapter";

        public AudioPlayerPagerAdapter(@NonNull Fragment fragment) {
            super(fragment);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            Log.d(TAG, "getItem(" + position + ")");

            switch (position) {
                case POS_COVER:
                    return new CoverFragment();
                default:
                case POS_DESCRIPTION:
                    return new ItemDescriptionFragment();
            }
        }

        @Override
        public int getItemCount() {
            return NUM_CONTENT_FRAGMENTS;
        }
    }

    public void scrollToPage(int page, boolean smoothScroll) {
        if (pager == null) {
            return;
        }

        pager.setCurrentItem(page, smoothScroll);

        Fragment visibleChild = getChildFragmentManager().findFragmentByTag("f" + POS_DESCRIPTION);
        if (visibleChild instanceof ItemDescriptionFragment) {
            ((ItemDescriptionFragment) visibleChild).scrollToTop();
        }
    }

    public void scrollToPage(int page) {
        scrollToPage(page, false);
    }
}
