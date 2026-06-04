package de.danoeh.antennapod.net.discovery;

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
 * Resolves SoundCloud track URLs (soundcloud.com/&lt;channel&gt;/&lt;track&gt;) to a publicly
 * distributed RSS feed by extracting the show name from the SoundCloud HTML page
 * and querying iTunes for a match.
 *
 * SoundCloud-exclusive tracks (regular music, non-podcast content) won't have an
 * iTunes feed, so resolution will fail for those. For shows that also publish via
 * Apple Podcasts/iTunes, the first matching feed is returned.
 */
public class SoundCloudPodcastSearcher implements PodcastSearcher {
    private static final Pattern PATTERN_SOUNDCLOUD_URL = Pattern.compile(
            "(?i)https?://(?:(?:www|m)\\.)?soundcloud\\.com/[^/\\s]+/\\S+");
    // SoundCloud's mobile "Share" produces on.soundcloud.com short links
    // (e.g. https://on.soundcloud.com/AbC123). They 302 to the canonical
    // soundcloud.com/<user>/<track> page, which the OkHttp client follows, so
    // the og:title extraction below still works once we agree to look them up.
    private static final Pattern PATTERN_SOUNDCLOUD_SHORT_URL = Pattern.compile(
            "(?i)https?://on\\.soundcloud\\.com/\\S+");
    private static final Pattern PATTERN_OG_TITLE = Pattern.compile(
            "<meta[^>]+property=\"og:title\"[^>]+content=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_HTML_TITLE = Pattern.compile(
            "<title>([^<]+)</title>",
            Pattern.CASE_INSENSITIVE);

    private static final String SC_TITLE_SUFFIX = "| Listen online for free on SoundCloud";
    private static final String SC_STREAM_EPISODE_PREFIX = "Stream episode ";
    private static final String SC_STREAM_PREFIX = "Stream ";
    private static final String SC_PODCAST_SUFFIX = " podcast";
    private static final String BY_SEPARATOR = " by ";

    private final ItunesPodcastSearcher itunes = new ItunesPodcastSearcher();

    @Override
    public Single<List<PodcastSearchResult>> search(String query) {
        return itunes.search(query);
    }

    @Override
    public Single<String> lookupUrl(String url) {
        return Single.fromCallable(() -> {
            String showName = fetchShowNameFromSoundCloud(url);
            if (showName == null || showName.isEmpty()) {
                throw new IOException("Could not extract show name from SoundCloud page");
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
        return url != null
                && (PATTERN_SOUNDCLOUD_URL.matcher(url).matches()
                    || PATTERN_SOUNDCLOUD_SHORT_URL.matcher(url).matches());
    }

    @Override
    public String getName() {
        return "SoundCloud";
    }

    private static String fetchShowNameFromSoundCloud(String soundCloudUrl) throws IOException {
        OkHttpClient client = AntennapodHttpClient.getHttpClient();
        Request request = new Request.Builder()
                .url(soundCloudUrl)
                // SoundCloud serves a stripped-down page to bot UAs; pose as a real browser.
                .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                                + "Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("SoundCloud HTTP " + response.code());
            }
            ResponseBody body = response.body();
            String html = body != null ? body.string() : "";

            // og:title on SoundCloud track pages: "<track title> by <user>".
            Matcher m = PATTERN_OG_TITLE.matcher(html);
            if (m.find()) {
                String ogTitle = decodeHtmlEntities(m.group(1));
                ParseResult pr = parseScTitle(ogTitle, /* hasWrapper */ false);
                if (pr != null) {
                    EpisodeTitleCache.put(soundCloudUrl, pr.episode);
                    return pr.show;
                }
            }

            // <title> on SoundCloud track pages:
            //   "Stream [episode] <track> by <show>[ podcast] | Listen online for free on SoundCloud"
            m = PATTERN_HTML_TITLE.matcher(html);
            if (m.find()) {
                String htmlTitle = decodeHtmlEntities(m.group(1));
                ParseResult pr = parseScTitle(htmlTitle, /* hasWrapper */ true);
                if (pr != null) {
                    EpisodeTitleCache.put(soundCloudUrl, pr.episode);
                    return pr.show;
                }
            }

            // Last resort: og:title raw (probably won't match a podcast on iTunes,
            // but we surface a useful FeedUrlNotFoundException with this name).
            m = PATTERN_OG_TITLE.matcher(html);
            if (m.find()) {
                return decodeHtmlEntities(m.group(1));
            }
            return null;
        }
    }

    private static class ParseResult {
        final String show;
        final String episode;
        ParseResult(String show, String episode) {
            this.show = show;
            this.episode = episode;
        }
    }

    /** Parse a SoundCloud track title. Returns null if the "by &lt;show&gt;" structure
     *  isn't present — caller falls back to other extraction strategies.
     *  Format: "[Stream [episode] ]&lt;ep&gt; by &lt;show&gt;[ podcast][ | Listen online for free on SoundCloud]" */
    static ParseResult parseScTitle(String title, boolean hasWrapper) {
        if (title == null) return null;
        String t = title.trim();
        if (hasWrapper) {
            int sfx = indexOfIgnoreCase(t, SC_TITLE_SUFFIX);
            if (sfx >= 0) {
                t = t.substring(0, sfx).trim();
            }
        }
        if (startsWithIgnoreCase(t, SC_STREAM_EPISODE_PREFIX)) {
            t = t.substring(SC_STREAM_EPISODE_PREFIX.length()).trim();
        } else if (startsWithIgnoreCase(t, SC_STREAM_PREFIX)) {
            t = t.substring(SC_STREAM_PREFIX.length()).trim();
        }
        // Split on the LAST " by " — show names with embedded " by " are rare, and
        // the SoundCloud wrapper always puts the creator last.
        int byIdx = t.toLowerCase(Locale.ROOT).lastIndexOf(BY_SEPARATOR);
        if (byIdx <= 0) return null;
        String episode = t.substring(0, byIdx).trim();
        String show = t.substring(byIdx + BY_SEPARATOR.length()).trim();
        if (endsWithIgnoreCase(show, SC_PODCAST_SUFFIX)) {
            show = show.substring(0, show.length() - SC_PODCAST_SUFFIX.length()).trim();
        }
        if (show.isEmpty() || episode.isEmpty()) return null;
        return new ParseResult(show, episode);
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        return s.length() >= prefix.length()
                && s.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean endsWithIgnoreCase(String s, String suffix) {
        int offset = s.length() - suffix.length();
        return offset >= 0 && s.regionMatches(true, offset, suffix, 0, suffix.length());
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
