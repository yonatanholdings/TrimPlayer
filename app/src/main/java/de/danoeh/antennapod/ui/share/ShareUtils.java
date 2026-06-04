package de.danoeh.antennapod.ui.share;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ShareCompat;
import androidx.core.content.FileProvider;

import de.danoeh.antennapod.ui.common.Converter;
import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;

/** Utility methods for sharing data */
public class ShareUtils {
    private static final String TAG = "ShareUtils";
    private static final int ABBREVIATE_MAX_LENGTH = 50;

    private ShareUtils() {
    }

    public static void shareLink(@NonNull Context context, @NonNull String text) {
        Intent intent = new ShareCompat.IntentBuilder(context)
                .setType("text/plain")
                .setText(text)
                .setChooserTitle(R.string.share_url_label)
                .createChooserIntent();
        context.startActivity(intent);
    }

    public static void shareFeedLink(Context context, Feed feed) {
        String text = feed.getTitle() + "\n\n" + feed.getDownloadUrl();
        shareLink(context, text);
    }

    public static boolean hasLinkToShare(FeedItem item) {
        return item.getLinkWithFallback() != null;
    }

    public static String getSocialFeedItemShareText(Context context, FeedItem item,
                                                    boolean withPosition, boolean abbreviate) {
        String text = item.getFeed().getTitle() + ": ";

        if (abbreviate && item.getTitle().length() > ABBREVIATE_MAX_LENGTH) {
            text += item.getTitle().substring(0, ABBREVIATE_MAX_LENGTH) + "…";
        } else {
            text += item.getTitle();
        }

        if (item.getMedia() != null && withPosition) {
            text += "\n" + context.getResources().getString(R.string.share_starting_position_label) + ": ";
            text +=  Converter.getDurationStringLong(item.getMedia().getPosition());
        }

        // A single TrimPlayer deep link replaces the inherited "Episode webpage"
        // and "Media file" links: it opens the exact episode in the app (and falls
        // back to the web for non-users). OnlineFeedViewActivity extracts it from
        // the shared text again on the receiving side.
        String deepLink = trimPlayerDeepLink(item);
        if (deepLink != null) {
            text += "\n\n" + context.getResources().getString(R.string.share_dialog_trimplayer_branding) + "\n";
            if (abbreviate && deepLink.length() > ABBREVIATE_MAX_LENGTH) {
                text += deepLink.substring(0, ABBREVIATE_MAX_LENGTH) + "…";
            } else {
                text += deepLink;
            }
        }
        return text;
    }

    /**
     * Builds a {@code https://trimplayer.com/deeplink/subscribe?url=…&episode=…}
     * link for the episode, or {@code null} if the feed URL is unknown. The
     * {@code url} param lets the receiving TrimPlayer resolve the show; the
     * {@code episode} param (the RSS GUID, falling back to the media URL) lets it
     * open the exact episode instead of dropping the user on the feed list.
     */
    private static String trimPlayerDeepLink(FeedItem item) {
        if (item.getFeed() == null || item.getFeed().getDownloadUrl() == null) {
            return null;
        }
        String link = "https://trimplayer.com/deeplink/subscribe?url=" + encode(item.getFeed().getDownloadUrl());
        String episodeId = episodeIdentifier(item);
        if (episodeId != null) {
            link += "&episode=" + encode(episodeId);
        }
        return link;
    }

    /** RSS GUID is the most stable cross-device episode key; fall back to the
     *  enclosure URL when a feed omits guids. */
    private static String episodeIdentifier(FeedItem item) {
        if (item.getItemIdentifier() != null && !item.getItemIdentifier().isEmpty()) {
            return item.getItemIdentifier();
        }
        if (item.getMedia() != null && item.getMedia().getDownloadUrl() != null) {
            return item.getMedia().getDownloadUrl();
        }
        return null;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    public static void shareFeedItemFile(Context context, FeedMedia media) {
        Uri fileUri = FileProvider.getUriForFile(context, context.getString(R.string.provider_authority),
                new File(media.getLocalFileUrl()));

        new ShareCompat.IntentBuilder(context)
                .setType(media.getMimeType())
                .addStream(fileUri)
                .setChooserTitle(R.string.share_file_label)
                .startChooser();

        Log.e(TAG, "shareFeedItemFile called");
    }
}
