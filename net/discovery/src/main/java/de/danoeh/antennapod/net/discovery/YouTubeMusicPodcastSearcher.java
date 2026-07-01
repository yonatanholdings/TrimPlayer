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
 * Resolves YouTube Music / YouTube podcast URLs (music.youtube.com, www.youtube.com,
 * youtu.be) to a publicly distributed RSS feed by extracting the show name from the
 * page HTML and querying iTunes for a match.
 *
 * <p>YouTube-exclusive podcasts have no public RSS, so resolution will fail for those.
 * For shows that also publish via Apple Podcasts/iTunes, the first matching feed is returned.
 */
public class YouTubeMusicPodcastSearcher implements PodcastSearcher {
    // Match podcast-like YouTube URLs across all three share hosts.
    // youtu.be is always a single video id; we follow the redirect to find out what it is.
    private static final Pattern PATTERN_YOUTUBE_URL = Pattern.compile(
            "(?i)https?://("
                    + "(?:(?:music|www|m)\\.)?youtube\\.com/(?:podcast/|playlist\\?|watch\\?)"
                    + "|youtu\\.be/"
                    + ")\\S+");

    // JSON-LD on podcast episode pages: "partOfSeries":{...,"name":"Show Name"}.
    private static final Pattern PATTERN_PART_OF_SERIES = Pattern.compile(
            "\"partOfSeries\"\\s*:\\s*\\{[^}]*?\"name\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);
    // Show pages: og:title holds the show name directly.
    private static final Pattern PATTERN_OG_TITLE = Pattern.compile(
            "<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);
    // Fallback for pages without og tags.
    private static final Pattern PATTERN_META_TITLE = Pattern.compile(
            "<meta[^>]+name=\"title\"[^>]+content=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);

    private final ItunesPodcastSearcher itunes = new ItunesPodcastSearcher();

    @Override
    public Single<List<PodcastSearchResult>> search(String query) {
        return itunes.search(query);
    }

    @Override
    public Single<String> lookupUrl(String url) {
        return Single.fromCallable(() -> {
            String showName = fetchShowNameFromYouTube(url);
            if (showName == null || showName.isEmpty()) {
                throw new IOException("Could not extract show name from YouTube page");
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
        return url != null && PATTERN_YOUTUBE_URL.matcher(url).matches();
    }

    @Override
    public String getName() {
        return "YouTube Music";
    }

    private static String fetchShowNameFromYouTube(String youtubeUrl) throws IOException {
        OkHttpClient client = AntennapodHttpClient.getHttpClient();
        Request request = new Request.Builder()
                .url(youtubeUrl)
                // YouTube serves a stripped-down page to bot UAs; pose as a real browser.
                .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                                + "Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("YouTube HTTP " + response.code());
            }
            ResponseBody body = response.body();
            String html = body != null ? body.string() : "";

            String ogTitle = null;
            Matcher ogMatcher = PATTERN_OG_TITLE.matcher(html);
            if (ogMatcher.find()) {
                ogTitle = decodeHtmlEntities(ogMatcher.group(1));
            }
            String strippedOgTitle = stripYouTubeSuffix(ogTitle);

            // Stash the full stripped og:title as a deep-link hint regardless of
            // which show-name branch we take below. OnlineFeedViewActivity does
            // substring fuzzy-matching against FeedItem titles, which handles
            // both "<show> | <episode>" and bare "<episode>" hint forms.
            EpisodeTitleCache.put(youtubeUrl, strippedOgTitle);

            // YouTube Music podcast pages: structured JSON-LD with parent series.
            Matcher m = PATTERN_PART_OF_SERIES.matcher(html);
            if (m.find()) {
                return decodeHtmlEntities(m.group(1));
            }

            // YouTube watch pages for podcast episodes: the channel name is
            // baked into og:title as "<show> | <episode> - YouTube". Splitting
            // on " | " gives iTunes a much cleaner search string than the full
            // episode-y title (which previously still resolved by accident).
            if (strippedOgTitle != null) {
                int sep = strippedOgTitle.indexOf(" | ");
                if (sep > 0) {
                    String showName = strippedOgTitle.substring(0, sep).trim();
                    if (!showName.isEmpty()) {
                        return showName;
                    }
                }
                return strippedOgTitle;
            }

            m = PATTERN_META_TITLE.matcher(html);
            if (m.find()) {
                return decodeHtmlEntities(m.group(1));
            }
            return null;
        }
    }

    private static String stripYouTubeSuffix(String ogTitle) {
        if (ogTitle == null) {
            return null;
        }
        String t = ogTitle.trim();
        if (t.endsWith(" - YouTube")) {
            t = t.substring(0, t.length() - " - YouTube".length()).trim();
        }
        return t.isEmpty() ? null : t;
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
