package de.danoeh.antennapod.ui.screen.playback.audio;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;
import java.util.Locale;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.databinding.SegmentListDialogBinding;
import de.danoeh.antennapod.event.TrimSegmentsEditedEvent;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.playback.service.trim.TrimClient;
import de.danoeh.antennapod.playback.service.trim.TrimSegmentCache;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * "Trimmed in this episode" bottom sheet — the Android implementation of the
 * design's episode.jsx segment list (Scope A, local). Lists the episode's trim
 * segments; tapping one opens {@link EditSegmentDialog}, and "Mark a skip we
 * missed" seeds a new segment at the current playback position.
 *
 * <p>Refreshes live on {@link TrimSegmentsEditedEvent} so edits/removals made in
 * the child sheet are reflected without reopening.
 */
public class SegmentListDialog extends BottomSheetDialogFragment {
    private static final String ARG_GUID = "guid";
    private static final String ARG_URL = "url";
    private static final String ARG_DURATION = "duration";
    private static final float DEFAULT_NEW_SEGMENT_SEC = 30f;

    private SegmentListDialogBinding viewBinding;
    private PlaybackController controller;
    private Disposable mediaWarmup;
    /** True once the controller's media is cached off-thread, so the "mark
     *  missing" position read doesn't trigger a main-thread DB read (crash). */
    private boolean mediaReady;
    private String guid;
    private String episodeUrl;
    private float episodeDuration;

    public static SegmentListDialog newInstance(String episodeGuid, String episodeUrl,
                                                float episodeDurationSec) {
        SegmentListDialog dialog = new SegmentListDialog();
        Bundle args = new Bundle();
        args.putString(ARG_GUID, episodeGuid);
        args.putString(ARG_URL, episodeUrl);
        args.putFloat(ARG_DURATION, episodeDurationSec);
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        guid = requireArguments().getString(ARG_GUID);
        episodeUrl = requireArguments().getString(ARG_URL);
        episodeDuration = requireArguments().getFloat(ARG_DURATION);
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
        // Warm media off the main thread so markMissing()'s getPosition() doesn't
        // trigger a main-thread DB read (see EditSegmentDialog for the rationale).
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
        viewBinding = SegmentListDialogBinding.inflate(inflater, container, false);
        viewBinding.markMissingButton.setOnClickListener(v -> markMissing());
        renderRows();
        return viewBinding.getRoot();
    }

    private void renderRows() {
        if (viewBinding == null) {
            return;
        }
        viewBinding.segmentListContainer.removeAllViews();
        List<TrimClient.Segment> segs = TrimSegmentCache.get(requireContext(), guid);
        int count = segs == null ? 0 : segs.size();
        viewBinding.segmentListCount.setText(String.valueOf(count));

        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        if (count == 0) {
            viewBinding.segmentListSubtitle.setText(R.string.trim_segment_list_empty);
            return;
        }
        viewBinding.segmentListSubtitle.setText(R.string.trim_segment_list_subtitle);
        for (TrimClient.Segment seg : segs) {
            View row = inflater.inflate(R.layout.segment_list_row,
                    viewBinding.segmentListContainer, false);
            bindRow(row, seg, dark);
            row.setOnClickListener(v -> openEdit(seg, false));
            viewBinding.segmentListContainer.addView(row);
        }
    }

    private void bindRow(View row, TrimClient.Segment seg, boolean dark) {
        ImageView icon = row.findViewById(R.id.segmentIcon);
        TextView label = row.findViewById(R.id.segmentLabel);
        TextView range = row.findViewById(R.id.segmentRange);

        int solid = BoundaryEditor.SegmentColors.solid(seg.type, dark);
        int region = BoundaryEditor.SegmentColors.region(seg.type, dark);
        icon.setBackgroundTintList(ColorStateList.valueOf(region));
        icon.setImageTintList(ColorStateList.valueOf(solid));

        label.setText(labelFor(seg.type));
        range.setText(getString(R.string.trim_segment_row_range,
                fmtTime(seg.start), fmtTime(seg.end), fmtDuration(seg.end - seg.start)));
    }

    private void openEdit(TrimClient.Segment seg, boolean isNew) {
        EditSegmentDialog.newInstance(guid, episodeUrl, seg, episodeDuration, isNew)
                .show(hostFragmentManager(), "EditSegmentDialog");
    }

    private void markMissing() {
        float pos = (controller != null && mediaReady) ? controller.getPosition() / 1000f : 0f;
        TrimClient.Segment seg = new TrimClient.Segment();
        seg.start = Math.max(0f, pos);
        seg.end = episodeDuration > 0
                ? Math.min(episodeDuration, seg.start + DEFAULT_NEW_SEGMENT_SEC)
                : seg.start + DEFAULT_NEW_SEGMENT_SEC;
        seg.type = "ad";
        openEdit(seg, true);
    }

    /** Show child sheets on the player fragment's manager (not this dialog's own)
     *  so they survive this sheet dismissing and reach a persistent view. */
    private FragmentManager hostFragmentManager() {
        Fragment host = getParentFragment();
        return host != null ? host.getChildFragmentManager()
                : requireActivity().getSupportFragmentManager();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    @SuppressWarnings("unused")
    public void onTrimSegmentsEdited(TrimSegmentsEditedEvent event) {
        renderRows();
    }

    private String labelFor(String type) {
        switch (type == null ? "ad" : type.toLowerCase(Locale.ROOT)) {
            case "intro":
                return getString(R.string.trim_label_intro);
            case "outro":
                return getString(R.string.trim_label_outro);
            case "ad":
            default:
                return getString(R.string.trim_label_ad);
        }
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
