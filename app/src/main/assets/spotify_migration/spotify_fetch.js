// PortCast — Spotify library fetcher, WebView port.
//
// Adapted verbatim from chrome-extension/background.js
// `fetchSpotifyLibraryInTab` (~lines 220-587). Differences:
//   - progress() bridges via window.AndroidBridge.onProgress(JSON.stringify(...))
//     instead of chrome.runtime.sendMessage.
//   - At the end, the result is bridged via AndroidBridge.onPayload / onError
//     instead of being returned to chrome.scripting.executeScript.
//   - Wrapped in an async IIFE so it can be injected via WebView.evaluateJavascript.
//
// The hook (spotify_hook.js) must have already captured a Bearer + Client-Token
// in sessionStorage. If it hasn't, this falls back to a page-HTML token (which
// pathfinder will reject — surfaced as a precise error).
(function () {
  function bridge(method, arg) {
    try {
      if (window.AndroidBridge && typeof window.AndroidBridge[method] === 'function') {
        window.AndroidBridge[method](typeof arg === 'string' ? arg : JSON.stringify(arg));
      }
    } catch (_) { /* swallow */ }
  }

  (async function fetchSpotifyLibraryInTab() {
    const PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v2/query";
    const LIBRARY_OP = "libraryV3";
    const LIBRARY_HASH =
      "973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3";
    const PODCAST_FILTER_ID = "Podcasts & Shows";
    const PAGE_LIMIT = 50;

    function progress(phase, count, done) {
      bridge('onProgress', { phase: phase, count: count, done: done });
    }

    function readCapturedToken() {
      try {
        const token = sessionStorage.getItem("portcast_captured_token");
        const clientToken = sessionStorage.getItem("portcast_captured_client_token");
        if (token) return { token: token, clientToken: clientToken || null };
      } catch (_) {}
      return null;
    }

    function clearCapturedState() {
      try {
        sessionStorage.removeItem("portcast_captured_token");
        sessionStorage.removeItem("portcast_captured_client_token");
        sessionStorage.removeItem("portcast_captured_at");
        sessionStorage.removeItem("portcast_captured_bodies");
      } catch (_) {}
    }

    function extractTokenFromPageHtml() {
      const candidates = [
        document.getElementById("session"),
        document.getElementById("__NEXT_DATA__"),
      ].concat(Array.prototype.slice.call(
        document.querySelectorAll('script[type="application/json"]')));
      const paths = [
        function (d) { return d && d.accessToken; },
        function (d) { return d && d.session && d.session.accessToken; },
        function (d) { return d && d.props && d.props.pageProps && d.props.pageProps.accessToken; },
        function (d) {
          return d && d.props && d.props.pageProps && d.props.pageProps.session
            && d.props.pageProps.session.accessToken;
        },
      ];
      for (let i = 0; i < candidates.length; i++) {
        const el = candidates[i];
        if (!el || !el.textContent) continue;
        let data;
        try { data = JSON.parse(el.textContent); } catch (_) { continue; }
        for (let j = 0; j < paths.length; j++) {
          const t = paths[j](data);
          if (typeof t === "string" && t.length > 20) return t;
        }
      }
      return null;
    }

    async function waitForFreshHookCapture(timeoutMs) {
      const start = Date.now();
      while (Date.now() - start < timeoutMs) {
        try {
          const at = parseInt(sessionStorage.getItem("portcast_captured_at") || "0", 10);
          if (at >= start) {
            const t = readCapturedToken();
            if (t) return t;
          }
        } catch (_) {}
        await new Promise(function (r) { setTimeout(r, 250); });
      }
      return null;
    }

    async function obtainToken() {
      progress("token");

      const cached = readCapturedToken();
      if (cached && cached.clientToken) {
        return Object.assign({}, cached, { source: "hook-bootstrap" });
      }

      progress("token-waiting");
      const fresh = await waitForFreshHookCapture(10000);
      if (fresh && fresh.clientToken) {
        return Object.assign({}, fresh, { source: "fetch-hook-poll" });
      }

      if (cached && cached.token) {
        return Object.assign({}, cached, { source: "hook-no-client-token" });
      }
      const fromHtml = extractTokenFromPageHtml();
      if (fromHtml) {
        return { token: fromHtml, clientToken: null, source: "page-html" };
      }

      return {
        error:
          "Could not obtain a Spotify api-partner token. The library page " +
          "loaded but the hook never captured an authenticated pathfinder " +
          "request (no Bearer + Client-Token pair) during bootstrap or in " +
          "the 10s after. Reload open.spotify.com, confirm you're signed " +
          "in, open Your Library, and tap Fetch library again.",
      };
    }

    function uriToId(uri) {
      return uri ? String(uri).split(":").pop() : null;
    }

    function mapShowItem(it) {
      const wrapper = (it && it.item) || {};
      const d = wrapper.data || {};
      const publisher =
        d.publisher && typeof d.publisher === "object"
          ? d.publisher.name || null
          : d.publisher || null;
      const images = ((d.coverArt && d.coverArt.sources) || []).map(function (s) {
        return { url: s.url, height: s.height, width: s.width };
      });
      return {
        added_at: (it && it.addedAt && it.addedAt.isoString) || null,
        show: {
          id: uriToId(wrapper._uri || d.uri),
          name: d.name || null,
          publisher: publisher,
          images: images,
        },
      };
    }

    try {
      const tk = await obtainToken();
      if (tk.notSignedIn) {
        clearCapturedState();
        bridge('onError', 'Not signed in to Spotify.');
        return;
      }
      if (tk.error) {
        clearCapturedState();
        bridge('onError', tk.error);
        return;
      }

      const baseHeaders = {
        authorization: "Bearer " + tk.token,
        "content-type": "application/json",
        accept: "application/json",
        "app-platform": "WebPlayer",
        "accept-language": "en",
      };
      if (tk.clientToken) baseHeaders["client-token"] = tk.clientToken;

      function formatDuration(sec) {
        if (sec < 60) return sec + " seconds";
        if (sec < 3600) {
          const m = Math.ceil(sec / 60);
          return "about " + m + " minute" + (m === 1 ? "" : "s");
        }
        const h = Math.ceil(sec / 3600);
        return "about " + h + " hour" + (h === 1 ? "" : "s");
      }

      async function pathfinder(body) {
        for (let attempt = 0; attempt < 2; attempt++) {
          const r = await fetch(PATHFINDER_URL, {
            method: "POST",
            headers: baseHeaders,
            body: JSON.stringify(body),
          });

          if (r.status === 429) {
            let waitSec = parseInt(r.headers.get("Retry-After") || "30", 10);
            if (!Number.isFinite(waitSec) || waitSec < 1) waitSec = 30;
            if (attempt === 0 && waitSec <= 30) {
              progress("rate-limited", waitSec);
              await new Promise(function (res) { setTimeout(res, waitSec * 1000); });
              continue;
            }
            throw new Error(
              "Spotify rate-limited this request (HTTP 429). " +
                "Try again in " + formatDuration(waitSec) + ".");
          }

          if (r.status === 401 || r.status === 403) {
            const t = await r.text().catch(function () { return ""; });
            throw new Error(
              "Spotify rejected the token (HTTP " + r.status + "). This is " +
                "an authentication/CDN issue, not a rate limit. The captured " +
                "web-player token may be missing its Client-Token or may " +
                "have expired. Reload open.spotify.com (signed in) and tap " +
                "Fetch library again. " + t.slice(0, 120));
          }

          if (!r.ok) {
            const t = await r.text().catch(function () { return ""; });
            throw new Error("pathfinder " + r.status + ": " + t.slice(0, 160));
          }

          const j = await r.json();
          if (j && j.errors && j.errors.length) {
            const msg = j.errors[0] && j.errors[0].message
              ? j.errors[0].message
              : JSON.stringify(j.errors[0]);
            // Spotify's pathfinder reports unauthenticated /me access as a
            // GraphQL-layer error with this exact phrasing. The host token
            // looks structurally valid (the WebView captured both Bearer
            // and Client-Token from the page's bootstrap), so we can't
            // tell from the headers; only this specific error tells us
            // the user isn't signed in.
            if (/not authorized/i.test(msg)) {
              const err = new Error(msg);
              err.notSignedIn = true;
              throw err;
            }
            throw new Error("pathfinder GraphQL error: " + msg);
          }
          return j;
        }
      }

      function libraryBody(offset) {
        return {
          variables: {
            filters: [PODCAST_FILTER_ID],
            order: null,
            textFilter: "",
            features: [
              "LIKED_SONGS",
              "YOUR_EPISODES_V2",
              "PRERELEASES",
              "PRERELEASES_V2",
              "CLIPS",
              "EVENTS",
            ],
            limit: PAGE_LIMIT,
            offset: offset,
            flatten: false,
            expandedFolders: [],
            folderUri: null,
            includeFoldersWhenFlattening: true,
          },
          operationName: LIBRARY_OP,
          extensions: {
            persistedQuery: { version: 1, sha256Hash: LIBRARY_HASH },
          },
        };
      }

      progress("me");
      let me = { id: null, display_name: null, email: null };
      try {
        const sess = document.getElementById("session");
        if (sess && sess.textContent) {
          const s = JSON.parse(sess.textContent);
          const id = (s && s.userId)
            || (s && s.user && s.user.id)
            || (s && s.session && s.session.userId)
            || null;
          if (id) me.id = id;
        }
      } catch (_) {}

      progress("shows", 0);
      const savedShows = [];
      let offset = 0;
      let total = Infinity;
      let guard = 0;
      while (offset < total && guard < 200) {
        guard += 1;
        const j = await pathfinder(libraryBody(offset));
        const lib = j && j.data && j.data.me && j.data.me.libraryV3;

        if (!lib || lib.__typename !== "LibraryPage") {
          const m = (lib && lib.message) || JSON.stringify(lib);
          throw new Error("Library query returned: " + m);
        }

        if (typeof lib.totalCount === "number") total = lib.totalCount;
        const items = Array.isArray(lib.items) ? lib.items : [];
        const shows = items.filter(function (it) {
          return it && it.item && it.item.__typename === "PodcastResponseWrapper";
        });
        for (let i = 0; i < shows.length; i++) savedShows.push(mapShowItem(shows[i]));

        progress("shows", savedShows.length);

        if (items.length === 0) break;
        offset += PAGE_LIMIT;
        await new Promise(function (res) { setTimeout(res, 200); });
      }
      progress("shows", savedShows.length, true);

      progress("episodes", 0);
      const savedEpisodes = [];
      progress("episodes", savedEpisodes.length, true);

      clearCapturedState();
      bridge('onPayload', {
        me: me,
        savedShows: savedShows,
        savedEpisodes: savedEpisodes,
        tokenSource: tk.source,
      });
    } catch (err) {
      clearCapturedState();
      if (err && err.notSignedIn) {
        bridge('onNotSignedIn', '');
        return;
      }
      bridge('onError', String((err && err.message) || err));
    }
  })();
})();
