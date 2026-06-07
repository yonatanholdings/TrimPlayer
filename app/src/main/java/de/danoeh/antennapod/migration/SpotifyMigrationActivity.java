package de.danoeh.antennapod.migration;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

import org.greenrobot.eventbus.EventBus;

import de.danoeh.antennapod.BuildConfig;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.event.AnalyticsEvent;
import de.danoeh.antennapod.portcast.PortcastImportActivity;
import de.danoeh.antennapod.ui.common.ThemeSwitcher;

/**
 * In-app Spotify → PortCast migration flow (v2). Hosts a WebView pointed at
 * {@code open.spotify.com}, captures the user's library via the same JS
 * pathfinder fetcher used by the Chrome extension, runs
 * {@code lib/portcast.js#buildDocument} over the bridge to produce a
 * canonical {@code .portcast.json}, writes it to cache, and hands off to
 * {@link PortcastImportActivity} for the confirmation + execute flow shared
 * with the v1 file-transfer path.
 *
 * <p>See {@code trimplayer-android-migration-plan.md} §4 for the full
 * design notes (UA choice, hook injection timing, EME noise, etc.).
 *
 * <p>All three JS assets live in {@code app/src/main/assets/spotify_migration/}:
 * <ul>
 *   <li>{@code spotify_hook.js} — token capture, verbatim from upstream.</li>
 *   <li>{@code spotify_fetch.js} — pathfinder fetcher; adapted for the bridge.</li>
 *   <li>{@code portcast.js} — document builder, verbatim from upstream (the
 *       file is in ES-module form; we strip {@code export} keywords and
 *       attach the symbols to {@code window.__portcast} for callability).</li>
 * </ul>
 */
public class SpotifyMigrationActivity extends AppCompatActivity {

    private static final String TAG = "SpotifyMigration";
    private static final String START_URL = "https://open.spotify.com/collection/podcasts";
    /** Spotify's desktop SPA redirects /collection/podcasts → /collection/tracks
     *  on bootstrap; the library sidebar still fires libraryV3 from there. The
     *  Fetch button should enable for any /collection/* path. */
    private static final String LIBRARY_PREFIX = "https://open.spotify.com/collection";
    /** The default mobile WebView UA trips Spotify's "open in app or close"
     *  mobile-web wall, which never lets sign-in proceed. A desktop UA serves
     *  the full web player and the normal login flow. Verified working
     *  2026-05-31; version-bump when stable Chrome moves. */
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final String ASSET_HOOK = "spotify_migration/spotify_hook.js";
    private static final String ASSET_FETCH = "spotify_migration/spotify_fetch.js";
    private static final String ASSET_BUILDER = "spotify_migration/portcast.js";

    /** Namespace we attach portcast.js exports to so they're callable across
     *  separate {@code evaluateJavascript} invocations. */
    private static final String PORTCAST_NS = "__portcast";

    private enum Phase { SIGNING_IN, READY, FETCHING, BUILDING, HANDING_OFF }

    private WebView webView;
    private TextView statusView;
    private ProgressBar progressBar;
    private Button fetchButton;
    private Button cancelButton;

    private String hookJs;
    private String fetchJs;
    private String builderJs;

    private Phase phase = Phase.SIGNING_IN;
    /** Cached after fetch so it can be passed into buildDocument. Cleared
     *  before we hand off. */
    @Nullable private String capturedPayloadJson;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(ThemeSwitcher.getNoTitleTheme(this));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.spotify_migration_activity);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.spotify_migration_title);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        webView = findViewById(R.id.spotify_webview);
        statusView = findViewById(R.id.spotify_status);
        progressBar = findViewById(R.id.spotify_progress);
        fetchButton = findViewById(R.id.spotify_fetch_button);
        cancelButton = findViewById(R.id.spotify_cancel_button);

        try {
            hookJs = loadAsset(ASSET_HOOK);
            fetchJs = loadAsset(ASSET_FETCH);
            builderJs = loadAsset(ASSET_BUILDER);
        } catch (IOException e) {
            Log.e(TAG, "Asset load failed", e);
            fatalError(getString(R.string.spotify_migration_error, e.getMessage()));
            return;
        }

        cancelButton.setOnClickListener(v -> finish());
        fetchButton.setOnClickListener(v -> startFetch());

        // The user entered the Spotify migration flow. Completion is reported
        // later by PortcastImportActivity (tagged "spotify" via the handoff
        // extra). Guard on savedInstanceState so a rotation doesn't re-count.
        if (savedInstanceState == null) {
            EventBus.getDefault().post(AnalyticsEvent.importStarted("spotify"));
        }

        configureWebView();
        // Don't pre-wipe cookies — third-party OAuth providers (Google,
        // Facebook, Apple "Continue with") refuse to complete sign-in
        // inside a WebView, so forcing a fresh sign-in every visit
        // strands users who use those providers. Persist the WebView
        // session; reset only AFTER a successful import (handOff) so the
        // next migration attempt starts clean for the same / a different
        // user.
        webView.loadUrl(START_URL);
    }

    @Override
    protected void onPause() {
        // Release media decoders Spotify spun up for its (unused) audio
        // pipeline — the EME errors in logcat are noisy but real underlying
        // decoder state. about:blank is the standard "let go" gesture.
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            // If we paused mid-flow, reset back to sign-in so the user
            // doesn't see about:blank.
            if (phase == Phase.SIGNING_IN || phase == Phase.READY) {
                webView.loadUrl(START_URL);
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidBridge");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUserAgentString(DESKTOP_UA);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                // Forward Spotify's JS console output to logcat in debug
                // builds only — useful when diagnosing a broken sign-in,
                // noisy in production.
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "console[" + message.messageLevel() + "] "
                            + message.message() + " @ " + message.sourceId()
                            + ":" + message.lineNumber());
                }
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (BuildConfig.DEBUG) Log.d(TAG, "pageStarted: " + url);
                // Inject the token-capture hook as early as we can so the
                // page's bootstrap pathfinder call surfaces a Bearer +
                // Client-Token into sessionStorage. The fetcher polls for
                // 10s if it misses the first capture, but the spike showed
                // the first read is normally enough.
                view.evaluateJavascript(hookJs, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (BuildConfig.DEBUG) Log.d(TAG, "pageFinished: " + url);
                view.evaluateJavascript(hookJs, null); // belt-and-suspenders
                // Reset scroll on every navigation. WebView preserves scroll
                // position across reloads, and Spotify's SPA route changes
                // can leave the user looking at the bottom of the previous
                // page after a redirect.
                view.scrollTo(0, 0);
                if (url == null) return;
                if (phase != Phase.SIGNING_IN && phase != Phase.READY) return;
                if (url.startsWith(LIBRARY_PREFIX)) {
                    setPhase(Phase.READY);
                    return;
                }
                // After sign-in Spotify redirects to its homepage (the URL
                // set in the login flow's `continue=` param), not to the
                // library route we wanted. Nudge the WebView back to
                // /collection/podcasts so the libraryV3 bootstrap fires
                // and the Fetch button enables.
                if (isSpotifyHome(url)) {
                    view.loadUrl(START_URL);
                    return;
                }
                setPhase(Phase.SIGNING_IN);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl() != null ? request.getUrl().toString() : "";
                if (BuildConfig.DEBUG) Log.d(TAG, "shouldOverrideUrlLoading: " + url);
                // Block deep-link schemes that would punt the user out of the
                // WebView (Spotify's mobile-web fallback tries spotify:// /
                // intent:// / android-app:// in some flows).
                if (url.startsWith("spotify:")
                        || url.startsWith("intent:")
                        || url.startsWith("market:")
                        || url.startsWith("android-app:")) {
                    return true;
                }
                // Block third-party OAuth (Google/Facebook/Apple "Continue with X"):
                // those providers all refuse to complete sign-in inside an
                // embedded WebView and bounce the user through an infinite
                // sign-in restart loop. Intercept at the Spotify side so the
                // user stays on the email-password form.
                if (isThirdPartyOauth(url)) {
                    showThirdPartyOauthBlockedDialog();
                    return true;
                }
                return false;
            }
        });
    }

    /** Wipe cookies + WebView storage. Called AFTER a successful handoff so
     *  the next migration session starts clean (for the same or a different
     *  user) without locking out users mid-flow when third-party OAuth
     *  providers refuse to complete inside a WebView. */
    private void clearStorageAfterSuccess() {
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        WebStorage.getInstance().deleteAllData();
        if (webView != null) {
            webView.clearHistory();
            webView.clearCache(true);
        }
    }

    private void startFetch() {
        if (fetchJs == null) return;
        setPhase(Phase.FETCHING);
        webView.evaluateJavascript(fetchJs, null);
    }

    private void runBuildDocument(String payloadJson) {
        // Strip ES-module `export` keywords (evaluateJavascript runs scripts
        // as classic, not as modules) and wrap so the symbols attach to
        // window.__portcast — top-level const/let are script-scoped, so we
        // can't rely on plain global bindings across calls.
        String stripped = builderJs.replaceAll("(?m)^export\\s+", "");
        String wrapped = "(function(){\n" + stripped + "\n"
                + "try { window." + PORTCAST_NS + " = { buildDocument: buildDocument }; }"
                + " catch (e) { window.AndroidBridge.onError('install builder: ' + e.message); }\n"
                + "})();";
        webView.evaluateJavascript(wrapped, ignored -> {
            String invocation = "(function(){"
                    + "  try {"
                    + "    var payload = " + payloadJson + ";"
                    + "    var doc = window." + PORTCAST_NS + ".buildDocument({"
                    + "      me: payload.me,"
                    + "      savedShows: payload.savedShows,"
                    + "      savedEpisodes: payload.savedEpisodes,"
                    + "      generatorVersion: 'TrimPlayer-" + BuildConfig.VERSION_NAME + "'"
                    + "    });"
                    + "    window.AndroidBridge.onDocument(JSON.stringify(doc));"
                    + "  } catch (e) {"
                    + "    window.AndroidBridge.onError('buildDocument: ' + (e && e.message || e));"
                    + "  }"
                    + "})();";
            webView.evaluateJavascript(invocation, null);
        });
    }

    private void handOff(String documentJson) {
        try {
            File cacheDir = new File(getCacheDir(), "spotify_migration");
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                throw new IOException("Could not create cache dir");
            }
            // Trim any stale files from prior runs so we don't accumulate.
            File[] existing = cacheDir.listFiles();
            if (existing != null) {
                for (File f : existing) f.delete();
            }
            File out = new File(cacheDir,
                    "spotify-" + System.currentTimeMillis() + ".portcast.json");
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(out), StandardCharsets.UTF_8)) {
                w.write(documentJson);
            }
            Uri uri = FileProvider.getUriForFile(this,
                    getString(R.string.provider_authority), out);
            Intent intent = new Intent(this, PortcastImportActivity.class);
            intent.setAction(Intent.ACTION_VIEW);
            intent.setData(uri);
            intent.putExtra(PortcastImportActivity.EXTRA_IMPORT_SOURCE, "spotify");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            clearStorageAfterSuccess();
            finish();
        } catch (Exception e) {
            Log.e(TAG, "handoff failed", e);
            fatalError(getString(R.string.spotify_migration_error, e.getMessage()));
        }
    }

    private void setPhase(Phase next) {
        phase = next;
        runOnUiThread(() -> {
            switch (next) {
                case SIGNING_IN:
                    statusView.setText(R.string.spotify_migration_signin_prompt);
                    progressBar.setVisibility(View.GONE);
                    fetchButton.setEnabled(false);
                    webView.setVisibility(View.VISIBLE);
                    break;
                case READY:
                    statusView.setText(R.string.spotify_migration_ready);
                    progressBar.setVisibility(View.GONE);
                    fetchButton.setEnabled(true);
                    webView.setVisibility(View.VISIBLE);
                    break;
                case FETCHING:
                    statusView.setText(R.string.spotify_migration_fetching);
                    progressBar.setVisibility(View.VISIBLE);
                    fetchButton.setEnabled(false);
                    break;
                case BUILDING:
                    statusView.setText(R.string.spotify_migration_building);
                    progressBar.setVisibility(View.VISIBLE);
                    fetchButton.setEnabled(false);
                    break;
                case HANDING_OFF:
                    statusView.setText(R.string.spotify_migration_handing_off);
                    progressBar.setVisibility(View.VISIBLE);
                    fetchButton.setEnabled(false);
                    break;
            }
        });
    }

    /** Spotify's homepage URL with or without trailing slash and optional
     *  query string. Anything more specific (a /collection/* / /show/* / ...
     *  path) means the user navigated themselves; we leave that alone. */
    private static boolean isSpotifyHome(String url) {
        return url.matches("https://open\\.spotify\\.com/?(\\?.*)?");
    }

    /** Matches Spotify's redirects to third-party identity providers. The
     *  language segment ({@code /en/}, {@code /de/}, …) is optional;
     *  /login/{google,facebook,apple} is the common shape. */
    private static boolean isThirdPartyOauth(String url) {
        if (url == null) return false;
        return url.matches(
                "https://accounts\\.spotify\\.com/(?:[a-z]{2,5}(?:-[A-Z]{2})?/)?login/(google|facebook|apple).*");
    }

    private void showThirdPartyOauthBlockedDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.spotify_migration_oauth_blocked_title)
                .setMessage(R.string.spotify_migration_oauth_blocked_body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void fatalError(String message) {
        runOnUiThread(() -> new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.spotify_migration_title)
                .setMessage(message)
                .setOnDismissListener(d -> finish())
                .setPositiveButton(android.R.string.ok, null)
                .show());
    }

    private String loadAsset(String path) throws IOException {
        try (InputStream in = getAssets().open(path);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    /**
     * Exposed to the WebView as {@code window.AndroidBridge}. Bridge methods
     * are intentionally privilege-free — at worst, a malicious page calling
     * {@code onDocument} with bogus JSON would cause {@link
     * de.danoeh.antennapod.storage.importexport.PortcastImporter} to error
     * during parse, which is benign.
     */
    private class Bridge {
        @JavascriptInterface
        public void onProgress(String json) {
            try {
                JSONObject o = new JSONObject(json);
                String phaseName = o.optString("phase", "");
                int count = o.optInt("count", -1);
                if ("shows".equals(phaseName) && count >= 0) {
                    int total = o.optInt("total", count);
                    runOnUiThread(() -> statusView.setText(getString(
                            R.string.spotify_migration_fetching_progress,
                            count, Math.max(total, count))));
                }
            } catch (JSONException ignored) {
                // Progress is advisory; bad JSON shouldn't break the run.
            }
        }

        @JavascriptInterface
        public void onPayload(String json) {
            capturedPayloadJson = json;
            setPhase(Phase.BUILDING);
            runOnUiThread(() -> runBuildDocument(json));
        }

        @JavascriptInterface
        public void onDocument(String json) {
            setPhase(Phase.HANDING_OFF);
            runOnUiThread(() -> handOff(json));
        }

        @JavascriptInterface
        public void onNotSignedIn(String ignored) {
            // Stay on the activity so the user can sign in and retry.
            setPhase(Phase.SIGNING_IN);
            runOnUiThread(() -> statusView.setText(R.string.spotify_migration_not_signed_in));
        }

        @JavascriptInterface
        public void onError(String msg) {
            Log.e(TAG, "JS error: " + msg);
            fatalError(getString(R.string.spotify_migration_error, msg));
        }
    }
}
