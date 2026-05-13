package de.danoeh.antennapod.net.discovery;

import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import io.reactivex.rxjava3.core.Single;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves Spotify podcast URLs (open.spotify.com/show/... or /episode/...) to their
 * publicly distributed RSS feed by extracting the show name from the Spotify HTML page
 * and querying iTunes for a matching podcast.
 *
 * Spotify-exclusive content has no public RSS feed, so resolution will fail for those.
 * For non-exclusive shows that exist on iTunes, the first matching feed is returned.
 */
public class SpotifyPodcastSearcher implements PodcastSearcher {
    private static final Pattern PATTERN_SPOTIFY_URL = Pattern.compile(
            "(?i)https?://open\\.spotify\\.com/(?:[a-z-]+/)?(show|episode)/[A-Za-z0-9]+.*");
    // Episode pages: og:audio:artist holds the show name (the actual podcast title).
    private static final Pattern PATTERN_OG_AUDIO_ARTIST = Pattern.compile(
            "<meta[^>]+property=\"og:audio:artist\"[^>]+content=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);
    // Show pages: og:title holds the show name directly.
    private static final Pattern PATTERN_OG_TITLE = Pattern.compile(
            "<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);

    private final ItunesPodcastSearcher itunes = new ItunesPodcastSearcher();

    @Override
    public Single<List<PodcastSearchResult>> search(String query) {
        return itunes.search(query);
    }

    @Override
    public Single<String> lookupUrl(String url) {
        return Single.fromCallable(() -> {
            String showName = fetchShowNameFromSpotify(url);
            if (showName == null || showName.isEmpty()) {
                throw new IOException("Could not extract show name from Spotify page");
            }
            String feedUrl = findFeedUrlForShow(showName);
            if (feedUrl == null) {
                throw new FeedUrlNotFoundException("", showName);
            }
            return feedUrl;
        });
    }

    @Override
    public boolean urlNeedsLookup(String url) {
        return url != null && PATTERN_SPOTIFY_URL.matcher(url).matches();
    }

    @Override
    public String getName() {
        return "Spotify";
    }

    private static String fetchShowNameFromSpotify(String spotifyUrl) throws IOException {
        OkHttpClient client = AntennapodHttpClient.getHttpClient();
        Request request = new Request.Builder()
                .url(spotifyUrl)
                // Spotify serves a stripped-down page to bot UAs; pose as a real browser.
                .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                                + "Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Spotify HTTP " + response.code());
            }
            ResponseBody body = response.body();
            String html = body != null ? body.string() : "";
            Matcher m = PATTERN_OG_AUDIO_ARTIST.matcher(html);
            if (m.find()) {
                return decodeHtmlEntities(m.group(1));
            }
            m = PATTERN_OG_TITLE.matcher(html);
            if (m.find()) {
                return decodeHtmlEntities(m.group(1));
            }
            return null;
        }
    }

    private static String decodeHtmlEntities(String s) {
        return s.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private String findFeedUrlForShow(String query) throws IOException {
        try {
            List<PodcastSearchResult> results = itunes.search(query).blockingGet();
            if (results == null || results.isEmpty()) {
                return null;
            }
            for (PodcastSearchResult r : results) {
                if (r.feedUrl != null && r.title != null && r.title.equalsIgnoreCase(query)) {
                    return r.feedUrl;
                }
            }
            for (PodcastSearchResult r : results) {
                if (r.feedUrl != null) {
                    return r.feedUrl;
                }
            }
            return null;
        } catch (RuntimeException e) {
            throw new IOException(e);
        }
    }
}
