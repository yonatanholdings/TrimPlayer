package de.danoeh.antennapod.ui.share;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.databinding.ShareEpisodeDialogBinding;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.ui.common.Converter;

public class ShareDialog extends BottomSheetDialogFragment {
    private static final String ARGUMENT_FEED_ITEM = "feedItem";
    private static final String PREF_NAME = "ShareDialog";
    private static final String PREF_SHARE_EPISODE_START_AT = "prefShareEpisodeStartAt";

    public ShareDialog() {
        // Empty constructor required for DialogFragment
    }

    public static ShareDialog newInstance(FeedItem item) {
        Bundle arguments = new Bundle();
        arguments.putSerializable(ARGUMENT_FEED_ITEM, item);
        ShareDialog dialog = new ShareDialog();
        dialog.setArguments(arguments);
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        if (getArguments() == null) {
            return null;
        }
        FeedItem item = (FeedItem) getArguments().getSerializable(ARGUMENT_FEED_ITEM);
        ShareEpisodeDialogBinding viewBinding = ShareEpisodeDialogBinding.inflate(inflater);

        viewBinding.episodeTitle.setText(item.getTitle());

        SharedPreferences prefs = getContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        viewBinding.sharePositionCheckbox.setChecked(prefs.getBoolean(PREF_SHARE_EPISODE_START_AT, false));
        // The checkbox only exists when there's a playback position to share; its
        // label shows that position (e.g. "Share current position (00:12:34)").
        boolean hasPosition = item.getMedia() != null && item.getMedia().getPosition() > 0;
        viewBinding.sharePositionCheckbox.setVisibility(hasPosition ? View.VISIBLE : View.GONE);
        if (hasPosition) {
            viewBinding.sharePositionCheckbox.setText(getString(R.string.share_dialog_start_position,
                    Converter.getDurationStringLong(item.getMedia().getPosition())));
        }
        viewBinding.sharePositionCheckbox.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(PREF_SHARE_EPISODE_START_AT, isChecked).apply());

        // Primary: share the TrimPlayer episode link (opens the app, or the web
        // player for recipients without it). Text is resolved at click time so it
        // reflects the current position-checkbox state.
        viewBinding.shareLinkButton.setOnClickListener(v -> {
            ShareUtils.shareLink(getContext(), ShareUtils.getSocialFeedItemShareText(
                    getContext(), item, viewBinding.sharePositionCheckbox.isChecked(), false));
            dismiss();
        });

        // Secondary: share the downloaded audio file itself — only meaningful when
        // the episode is actually downloaded.
        if (item.getMedia() != null && item.getMedia().isDownloaded()) {
            viewBinding.shareAudioFileButton.setOnClickListener(v -> {
                ShareUtils.shareFeedItemFile(getContext(), item.getMedia());
                dismiss();
            });
        } else {
            viewBinding.shareAudioFileButton.setVisibility(View.GONE);
        }

        return viewBinding.getRoot();
    }
}
