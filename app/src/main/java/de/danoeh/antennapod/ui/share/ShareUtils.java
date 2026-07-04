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
     * Builds an episode deep link on {@code app.trimplayer.com} (the web-player
     * host), or {@code null} if the feed URL is unknown.
     *
     * <p>The host is deliberately {@code app.trimplayer.com}, not the marketing
     * site {@code trimplayer.com}: both verify the app via Digital Asset Links, so
     * an <i>installed</i> app still opens the link directly (see the manifest
     * App-Links filters) — but a recipient <i>without</i> the app now lands on the
     * web player instead of the marketing page.
     *
     * <p>Two param sets ride the one URL:
     * <ul>
     *   <li>App receiver ({@link OnlineFeedViewActivity}): {@code url} = feed to
     *       resolve/subscribe, {@code episode} = exact episode key (GUID, or the
     *       media URL when the feed omits GUIDs), {@code web} = episode webpage.</li>
     *   <li>Web guest player (App.tsx / AnonymousView, no account needed):
     *       {@code ep} = the enclosure/audio URL to play, plus {@code guid},
     *       {@code t} (title), {@code p} (podcast) and {@code i} (show icon).</li>
     * </ul>
     */
    private static String trimPlayerDeepLink(FeedItem item) {
        if (item.getFeed() == null || item.getFeed().getDownloadUrl() == null) {
            return null;
        }
        StringBuilder link = new StringBuilder("https://app.trimplayer.com/deeplink/subscribe?url=")
                .append(encode(item.getFeed().getDownloadUrl()));
        String episodeId = episodeIdentifier(item);
        if (episodeId != null) {
            link.append("&episode=").append(encode(episodeId));
        }
        if (item.getLinkWithFallback() != null && !item.getLinkWithFallback().isEmpty()) {
            link.append("&web=").append(encode(item.getLinkWithFallback()));
        }
        // Web guest-player params: let a recipient without the app play the exact
        // episode straight away. Needs the audio (enclosure) URL — a GUID isn't
        // playable in the browser — so only emit these when we have media.
        if (item.getMedia() != null && item.getMedia().getDownloadUrl() != null) {
            link.append("&ep=").append(encode(item.getMedia().getDownloadUrl()));
            if (item.getItemIdentifier() != null && !item.getItemIdentifier().isEmpty()) {
                link.append("&guid=").append(encode(item.getItemIdentifier()));
            }
            if (item.getTitle() != null) {
                link.append("&t=").append(encode(item.getTitle()));
            }
            if (item.getFeed().getTitle() != null) {
                link.append("&p=").append(encode(item.getFeed().getTitle()));
            }
            if (item.getFeed().getImageUrl() != null) {
                link.append("&i=").append(encode(item.getFeed().getImageUrl()));
            }
        }
        return link.toString();
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
