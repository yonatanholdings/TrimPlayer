package de.danoeh.antennapod.net.discovery;

import android.util.Log;
import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import io.reactivex.rxjava3.core.Single;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
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
    private static final String TAG = "SpotifyPodcastSearcher";
    // Captures the page kind ("show" or "episode") for branching post-scrape.
    private static final Pattern PATTERN_SPOTIFY_URL = Pattern.compile(
            "(?i)https?://open\\.spotify\\.com/(?:[a-z-]+/)?(show|episode)/[A-Za-z0-9]+.*");
    private static final Pattern PATTERN_OG_TITLE = Pattern.compile(
            "<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);
    // On episode pages this is "<show name> · Episode" (middle dot U+00B7).
    private static final Pattern PATTERN_OG_DESCRIPTION = Pattern.compile(
            "<meta[^>]+property=\"og:description\"[^>]+content=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_HTML_TITLE = Pattern.compile(
            "<title[^>]*>([^<]+)</title>",
            Pattern.CASE_INSENSITIVE);
    // Strip Spotify's " · Episode" / " · Podcast" suffix from og:description to
    // recover just the show name.
    private static final Pattern PATTERN_SHOW_SUFFIX = Pattern.compile(
            "\\s*\\u00B7\\s*(Episode|Podcast)\\s*$",
            Pattern.CASE_INSENSITIVE);
    // " | Podcast on Spotify" trailer on the <title> tag.
    private static final Pattern PATTERN_SPOTIFY_TITLE_SUFFIX = Pattern.compile(
            "\\s*\\|[^|]*Spotify\\s*$",
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
        // Spotify serves a 302 to spotify.app.link (a Branch deep link) when the
        // UA looks mobile, which gives us no useful HTML. Appending nd=1 ("no
        // detect") suppresses the redirect and serves the real page.
        String fetchUrl = appendQueryParam(spotifyUrl, "nd", "1");
        OkHttpClient client = AntennapodHttpClient.getHttpClient();
        Request request = new Request.Builder()
                .url(fetchUrl)
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

            String ogTitle = firstGroup(PATTERN_OG_TITLE, html);
            String ogDescription = firstGroup(PATTERN_OG_DESCRIPTION, html);
            String htmlTitle = firstGroup(PATTERN_HTML_TITLE, html);

            boolean isEpisode = spotifyUrl.toLowerCase(Locale.ROOT).contains("/episode/");

            if (isEpisode) {
                // Episode page schema (current as of 2026-05):
                //   og:title       = episode title
                //   og:description = "<show name> · Episode"
                //   <title>        = "<episode> - <show> | Podcast on Spotify"
                String showName = stripShowSuffix(ogDescription);
                if (showName == null) {
                    // og:description missing or in unexpected form — fall back to
                    // pulling the show out of <title>.
                    showName = extractShowFromHtmlTitle(htmlTitle);
                }
                if (ogTitle != null) {
                    EpisodeTitleCache.put(spotifyUrl, ogTitle);
                }
                Log.i(TAG, "Spotify episode page: show=\"" + showName + "\" episode=\"" + ogTitle
                        + "\" (og:description=\"" + ogDescription + "\" <title>=\"" + htmlTitle + "\")");
                return showName != null ? showName : ogTitle;
            }
            // Show pages: og:title is the show name; nothing to cache.
            Log.i(TAG, "Spotify show page: show=\"" + ogTitle + "\"");
            return ogTitle;
        }
    }

    /** Strip Spotify's " · Episode" / " · Podcast" trailer from og:description
     *  to recover the bare show name. Returns null if the input doesn't look
     *  like that format. */
    static String stripShowSuffix(String ogDescription) {
        if (ogDescription == null) {
            return null;
        }
        Matcher m = PATTERN_SHOW_SUFFIX.matcher(ogDescription);
        if (!m.find()) {
            return null;
        }
        String show = ogDescription.substring(0, m.start()).trim();
        return show.isEmpty() ? null : show;
    }

    /** Parse "<episode> - <show> | Podcast on Spotify" and return the show. */
    static String extractShowFromHtmlTitle(String htmlTitle) {
        if (htmlTitle == null) {
            return null;
        }
        String t = htmlTitle.trim();
        Matcher m = PATTERN_SPOTIFY_TITLE_SUFFIX.matcher(t);
        if (m.find()) {
            t = t.substring(0, m.start()).trim();
        }
        // Split on the LAST " - " — episode titles can contain " - " too, but
        // the show name is always at the tail.
        int sep = t.lastIndexOf(" - ");
        if (sep <= 0) {
            return null;
        }
        String show = t.substring(sep + 3).trim();
        return show.isEmpty() ? null : show;
    }

    /** Append a query parameter to a URL, preserving any existing query string. */
    static String appendQueryParam(String url, String key, String value) {
        String sep = url.indexOf('?') >= 0 ? "&" : "?";
        return url + sep + key + "=" + value;
    }

    private static String firstGroup(Pattern pattern, String html) {
        Matcher m = pattern.matcher(html);
        return m.find() ? decodeHtmlEntities(m.group(1)).trim() : null;
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
