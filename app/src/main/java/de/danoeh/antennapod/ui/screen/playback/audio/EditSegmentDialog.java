package de.danoeh.antennapod.ui.screen.playback.audio;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.snackbar.Snackbar;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.Locale;

import de.danoeh.antennapod.databinding.EditSegmentDialogBinding;
import de.danoeh.antennapod.event.TrimSegmentsEditedEvent;
import de.danoeh.antennapod.event.playback.PlaybackPositionEvent;
import de.danoeh.antennapod.playback.base.PlayerStatus;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.playback.service.PlaybackService;
import de.danoeh.antennapod.playback.service.trim.TrimClient;
import de.danoeh.antennapod.playback.service.trim.TrimReportClient;
import de.danoeh.antennapod.playback.service.trim.TrimSegmentCache;
import de.danoeh.antennapod.R;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Local segment-edit bottom sheet — the Android implementation of the design's
 * {@code EditSegmentFlow} (report.jsx), Scope A (local only). Lets the listener
 * drag a skip's edges, nudge them by ½s, relabel its type, or remove it.
 *
 * <p>Edits are written to {@link TrimSegmentCache} and broadcast via
 * {@link TrimSegmentsEditedEvent} so the running episode's auto-skip picks them
 * up immediately. No network, no voting — those were Scope B.
 */
public class EditSegmentDialog extends BottomSheetDialogFragment {
    private static final String ARG_GUID = "guid";
    private static final String ARG_URL = "url";
    private static final String ARG_ID = "id";
    private static final String ARG_START = "start";
    private static final String ARG_END = "end";
    private static final String ARG_TYPE = "type";
    private static final String ARG_DURATION = "duration";
    private static final String ARG_IS_NEW = "is_new";

    private static final float NUDGE_STEP = 0.5f;
    private static final float MIN_GAP = 1f;
    private static final int POSITIVE_DELTA_COLOR = 0xFF2E7D32;

    private EditSegmentDialogBinding viewBinding;
    private PlaybackController controller;
    private Disposable mediaWarmup;
    /** True once the controller's media is cached off-thread, so main-thread
     *  seekTo/getPosition won't trigger a DB read (which hard-crashes the app). */
    private boolean mediaReady;
    /** True while a preview is playing the segment, so we can auto-stop at its
     *  end instead of letting playback run away past the region being edited. */
    private boolean previewing;
    /** Armed once the playhead has been seen inside the segment, so a stale
     *  pre-seek position report can't trip the auto-stop immediately. */
    private boolean previewEntered;

    private String guid;
    private String episodeUrl;
    private String segmentId;
    private float origStart;
    private float origEnd;
    private String origType;
    private float episodeDuration;
    private boolean isNewSegment;

    private float winStart;
    private float winEnd;
    private float boundStart;
    private float boundEnd;
    private String type;

    public static EditSegmentDialog newInstance(String episodeGuid, String episodeUrl,
                                                TrimClient.Segment segment,
                                                float episodeDurationSec, boolean isNew) {
        EditSegmentDialog dialog = new EditSegmentDialog();
        Bundle args = new Bundle();
        args.putString(ARG_GUID, episodeGuid);
        args.putString(ARG_URL, episodeUrl);
        args.putString(ARG_ID, segment.stableId());
        args.putFloat(ARG_START, segment.start);
        args.putFloat(ARG_END, segment.end);
        args.putString(ARG_TYPE, segment.type != null ? segment.type : "ad");
        args.putFloat(ARG_DURATION, episodeDurationSec);
        args.putBoolean(ARG_IS_NEW, isNew);
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = requireArguments();
        guid = args.getString(ARG_GUID);
        episodeUrl = args.getString(ARG_URL);
        segmentId = args.getString(ARG_ID);
        origStart = args.getFloat(ARG_START);
        origEnd = args.getFloat(ARG_END);
        origType = args.getString(ARG_TYPE, "ad");
        episodeDuration = args.getFloat(ARG_DURATION);
        isNewSegment = args.getBoolean(ARG_IS_NEW, false);

        boundStart = origStart;
        boundEnd = origEnd;
        type = origType;

        // Zoomed window: pad each side by half the segment length, clamped 6..20s,
        // matching the design's FineTuneStep/EditSegmentFlow window math.
        float pad = Math.max(6f, Math.min(20f, (origEnd - origStart) * 0.5f));
        winStart = Math.max(0f, origStart - pad);
        winEnd = episodeDuration > 0 ? Math.min(episodeDuration, origEnd + pad) : origEnd + pad;
    }

    @Override
    public void onStart() {
        super.onStart();
        controller = new PlaybackController(getActivity()) {
            @Override
            public void loadMediaInfo() {
            }

            // Drive the preview icon from the real status callback. This fires
            // synchronously on every PLAYING/PAUSED/PREPARING/STOPPED transition,
            // unlike PlaybackPositionEvent which the service stops emitting the
            // moment playback pauses (so the icon used to freeze on "pause").
            @Override
            protected void updatePlayButtonShowsPlay(boolean showPlay) {
                if (viewBinding != null) {
                    viewBinding.previewButton.setIconResource(
                            showPlay ? R.drawable.ic_play_24dp : R.drawable.ic_pause);
                }
            }
        };
        controller.init();
        EventBus.getDefault().register(this);

        // Warm the controller's media off the main thread. getMedia() lazily
        // reads the DB on first call; doing that on the main thread (e.g. from a
        // preview tap before the service finishes binding) hard-crashes on the
        // app's main-thread-I/O guard. Caching it here makes later seekTo/
        // getPosition calls safe.
        mediaWarmup = Single.fromCallable(() -> controller.getMedia() != null)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ready -> mediaReady = ready, error -> mediaReady = false);
    }

    /** End a preview: stop watching for the auto-stop and re-arm the service's
     *  auto-skip (which we suppressed while auditioning the segment). */
    private void stopPreview() {
        previewing = false;
        PlaybackService.trimSegmentEditPreviewActive = false;
    }

    @Override
    public void onStop() {
        super.onStop();
        // Safety net: never leave auto-skip suppressed once the sheet is gone.
        PlaybackService.trimSegmentEditPreviewActive = false;
        if (mediaWarmup != null) {
            mediaWarmup.dispose();
            mediaWarmup = null;
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
        viewBinding = EditSegmentDialogBinding.inflate(inflater, container, false);

        viewBinding.boundaryEditor.setWindow(winStart, winEnd);
        // Let handles reach anywhere in the episode (the window auto-pans); fall
        // back to a generous cap when the duration is unknown.
        float limitEnd = episodeDuration > 0 ? episodeDuration : origEnd + 600f;
        viewBinding.boundaryEditor.setLimits(0f, limitEnd);
        viewBinding.boundaryEditor.setBounds(boundStart, boundEnd);
        viewBinding.boundaryEditor.setType(type);
        viewBinding.boundaryEditor.setOnBoundsChangeListener((start, end) -> {
            boundStart = start;
            boundEnd = end;
            refresh();
        });
        viewBinding.boundaryEditor.setOnScrubListener(seconds -> {
            if (controller != null && mediaReady) {
                // Manual scrub takes over: drop the preview auto-stop so it
                // doesn't pause out from under the user. Auto-skip stays
                // suppressed (still cleared on pause/close) so a scrub through
                // the segment doesn't get skipped either.
                previewing = false;
                controller.seekTo(Math.round(seconds * 1000));
            }
        });

        viewBinding.closeButton.setOnClickListener(v -> dismiss());

        viewBinding.previewButton.setOnClickListener(v -> {
            if (controller == null || !mediaReady) {
                return;
            }
            if (controller.getStatus() == PlayerStatus.PLAYING) {
                stopPreview();
                controller.playPause(); // pause
            } else {
                // Start the preview from the segment start and arm the auto-stop.
                // Suppress the service's auto-skip first so the segment we're
                // auditioning plays through instead of being skipped past.
                PlaybackService.trimSegmentEditPreviewActive = true;
                controller.seekTo(Math.round(boundStart * 1000));
                previewing = true;
                previewEntered = false;
                controller.playPause(); // play
            }
        });
        viewBinding.jumpToStartButton.setOnClickListener(v -> {
            if (controller != null && mediaReady) {
                controller.seekTo(Math.round(boundStart * 1000));
            }
        });

        // Nudge rows.
        viewBinding.nudgeStart.nudgeLabel.setText(R.string.trim_edit_start);
        viewBinding.nudgeEnd.nudgeLabel.setText(R.string.trim_edit_end);
        viewBinding.nudgeStart.nudgeMinus.setOnClickListener(v -> {
            viewBinding.boundaryEditor.nudgeStart(-NUDGE_STEP);
        });
        viewBinding.nudgeStart.nudgePlus.setOnClickListener(v -> {
            viewBinding.boundaryEditor.nudgeStart(NUDGE_STEP);
        });
        viewBinding.nudgeEnd.nudgeMinus.setOnClickListener(v -> {
            viewBinding.boundaryEditor.nudgeEnd(-NUDGE_STEP);
        });
        viewBinding.nudgeEnd.nudgePlus.setOnClickListener(v -> {
            viewBinding.boundaryEditor.nudgeEnd(NUDGE_STEP);
        });

        // Label chips.
        selectChipForType(type);
        viewBinding.labelChipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            type = typeForChecked(group.getCheckedChipId());
            viewBinding.boundaryEditor.setType(type);
            refresh();
        });

        viewBinding.saveButton.setOnClickListener(v -> save());
        viewBinding.notASkipButton.setOnClickListener(v -> remove());

        refresh();
        return viewBinding.getRoot();
    }

    private void selectChipForType(String t) {
        switch (t == null ? "ad" : t.toLowerCase(Locale.ROOT)) {
            case "intro":
                viewBinding.chipIntro.setChecked(true);
                break;
            case "outro":
                viewBinding.chipOutro.setChecked(true);
                break;
            case "ad":
            default:
                viewBinding.chipAd.setChecked(true);
                break;
        }
    }

    private String typeForChecked(int checkedId) {
        if (checkedId == R.id.chipIntro) {
            return "intro";
        } else if (checkedId == R.id.chipOutro) {
            return "outro";
        }
        return "ad";
    }

    /** Pull the live bounds from the editor, then repaint the readouts + Save state. */
    private void refresh() {
        boundStart = viewBinding.boundaryEditor.getBoundStart();
        boundEnd = viewBinding.boundaryEditor.getBoundEnd();

        viewBinding.nudgeStart.nudgeTime.setText(fmtTime(boundStart));
        viewBinding.nudgeEnd.nudgeTime.setText(fmtTime(boundEnd));
        setDelta(viewBinding.nudgeStart.nudgeDelta, boundStart - origStart);
        setDelta(viewBinding.nudgeEnd.nudgeDelta, boundEnd - origEnd);
        viewBinding.lengthChip.setText(fmtDuration(boundEnd - boundStart));

        viewBinding.saveButton.setEnabled(isChanged());
    }

    private boolean isChanged() {
        boolean moved = Math.abs(boundStart - origStart) > 0.05f
                || Math.abs(boundEnd - origEnd) > 0.05f;
        return moved || !type.equalsIgnoreCase(origType);
    }

    private void setDelta(android.widget.TextView view, float delta) {
        if (Math.abs(delta) < 0.05f) {
            view.setText("");
            return;
        }
        String sign = delta < 0 ? "−" : "+";
        view.setText(String.format(Locale.getDefault(), "%s%.1fs", sign, Math.abs(delta)));
        view.setTextColor(delta < 0
                ? de.danoeh.antennapod.ui.common.ThemeUtils.getColorFromAttr(requireContext(), R.attr.colorError)
                : POSITIVE_DELTA_COLOR);
    }

    private void save() {
        TrimClient.Segment edited = new TrimClient.Segment();
        edited.id = segmentId;
        edited.start = boundStart;
        edited.end = boundEnd;
        edited.type = type;
        TrimSegmentCache.putSegment(requireContext().getApplicationContext(), guid, edited);
        EventBus.getDefault().post(new TrimSegmentsEditedEvent(guid));

        // Crowd-report the correction (Scope B). A brand-new segment is a 'missing'
        // report; a moved existing segment is 'adjust'; an unchanged save (relabel
        // only) is a 'confirm'. Local cache already updated above, so this is best-effort.
        boolean moved = Math.abs(boundStart - origStart) > 0.05f
                || Math.abs(boundEnd - origEnd) > 0.05f;
        String action = isNewSegment ? "missing" : (moved ? "adjust" : "confirm");
        Float newStart = "confirm".equals(action) ? null : boundStart;
        Float newEnd = "confirm".equals(action) ? null : boundEnd;
        TrimReportClient.report(requireContext().getApplicationContext(), episodeUrl, guid,
                origStart, origEnd, type, action, newStart, newEnd);

        toastAndDismiss(R.string.trim_edit_saved);
    }

    private void remove() {
        TrimSegmentCache.removeSegment(requireContext().getApplicationContext(), guid, segmentId);
        EventBus.getDefault().post(new TrimSegmentsEditedEvent(guid));
        TrimReportClient.report(requireContext().getApplicationContext(), episodeUrl, guid,
                origStart, origEnd, origType, "remove", null, null);
        toastAndDismiss(R.string.trim_edit_removed);
    }

    private void toastAndDismiss(int msgRes) {
        View parent = getParentFragment() != null && getParentFragment().getView() != null
                ? getParentFragment().getView() : null;
        if (parent != null) {
            // TrimSnackbar, not Snackbar.make: a plain snackbar lands behind the
            // expanded player sheet and is never seen.
            de.danoeh.antennapod.ui.view.TrimSnackbar.showOverPlayer(parent, getString(msgRes),
                    null, R.drawable.ic_content_cut, R.color.trim_badge_done, Snackbar.LENGTH_SHORT);
        }
        dismiss();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void onPlaybackPosition(PlaybackPositionEvent event) {
        if (viewBinding == null) {
            return;
        }
        float pos = event.getPosition() / 1000f;
        viewBinding.boundaryEditor.setPlayhead(pos);

        // Auto-stop the preview at the segment end so playback stays on the
        // region being edited instead of running away toward the episode end.
        // Arm only after the playhead has entered the segment, so a stale
        // pre-seek position can't pause us before playback even starts.
        if (previewing) {
            if (pos < boundEnd - 0.05f) {
                previewEntered = true;
            } else if (previewEntered && pos >= boundEnd) {
                stopPreview();
                if (controller != null && controller.getStatus() == PlayerStatus.PLAYING) {
                    controller.playPause(); // pause at the segment end
                }
            }
        }

        // Denominator is the episode length (stable) rather than the window end,
        // which now moves as the editor auto-pans.
        float total = episodeDuration > 0 ? episodeDuration : winEnd;
        viewBinding.previewPosition.setText(
                String.format(Locale.getDefault(), "%s / %s", fmtTime(pos), fmtTime(total)));
        // The play/pause icon is driven by updatePlayButtonShowsPlay (status
        // callback), not here — position events stop arriving once paused.
    }

    private static String fmtTime(float seconds) {
        int s = Math.max(0, Math.round(seconds));
        return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60);
    }

    private static String fmtDuration(float seconds) {
        int s = Math.max(0, Math.round(seconds));
        if (s < 60) {
            return s + "s";
        }
        int m = s / 60;
        int ss = s % 60;
        return ss > 0 ? String.format(Locale.getDefault(), "%dm %ds", m, ss)
                : String.format(Locale.getDefault(), "%dm", m);
    }
}
