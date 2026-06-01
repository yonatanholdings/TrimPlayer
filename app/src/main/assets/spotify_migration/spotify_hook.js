// PortCast — MAIN-world Spotify token capture hook.
//
// Loaded by manifest.json as a content_scripts entry running at
// document_start in the MAIN world on every open.spotify.com page.
// Wraps window.fetch and XMLHttpRequest so that every authenticated
// request the page makes to *.spotify.com leaves its Authorization
// (Bearer) and Client-Token headers in sessionStorage.
//
// Why document_start (not on-demand from background.js): Spotify's
// web player fires its first authenticated API calls during
// bootstrap. If we install the hook later via
// chrome.scripting.executeScript, we miss those calls — and a page
// that's been idle since bootstrap may not fire any more for tens
// of seconds, leaving the export hanging at "Waiting for Spotify
// to make an API call…". Installing at document_start guarantees
// the hook is in place before any page JS runs.
//
// Why MAIN world: the hook must wrap the same window.fetch the
// page itself uses, which only exists in the page's JS realm.
// Spotify's page CSP blocks injecting a <script> tag from an
// extension, so MAIN-world content scripts (Chrome 111+) are the
// supported way to do this.
//
// Privacy: the captured token sits in sessionStorage of
// open.spotify.com, scoped to that origin. Spotify's own JS
// already has the same token. The extension reads it only when
// the user clicks Export, and clears it from sessionStorage when
// the export finishes.

(function () {
  if (window.__portcastHookInstalled) return;
  window.__portcastHookInstalled = true;

  const TOKEN_KEY = "portcast_captured_token";
  const CLIENT_KEY = "portcast_captured_client_token";
  const AT_KEY = "portcast_captured_at";
  const BODIES_KEY = "portcast_captured_bodies";

  function capture(auth, clientToken) {
    try {
      if (auth && typeof auth === "string" && auth.startsWith("Bearer ")) {
        sessionStorage.setItem(TOKEN_KEY, auth.slice(7));
        sessionStorage.setItem(AT_KEY, String(Date.now()));
      }
      if (clientToken && typeof clientToken === "string") {
        sessionStorage.setItem(CLIENT_KEY, clientToken);
      }
    } catch {}
  }

  function isSpotifyUrl(url) {
    return typeof url === "string" && /\bspotify\.com\b/.test(url);
  }

  // We capture response bodies for the /v1/me/* endpoints so that
  // if the player loaded any library data during bootstrap, the
  // export can use those bodies as a warm cache and skip duplicate
  // API calls (avoiding the rate-limit window we keep hitting).
  // Capped at ~4MB total to stay well under sessionStorage's 5MB
  // per-origin cap.
  function shouldCaptureBody(url) {
    if (!isSpotifyUrl(url)) return false;
    return /\/v1\/me(?:$|\/(?:shows|episodes)\b)/.test(url);
  }

  function captureBody(url, body) {
    try {
      const raw = sessionStorage.getItem(BODIES_KEY);
      const store = raw ? JSON.parse(raw) : {};
      store[url] = body;
      const s = JSON.stringify(store);
      if (s.length > 4 * 1024 * 1024) return;
      sessionStorage.setItem(BODIES_KEY, s);
    } catch {}
  }

  const origFetch = window.fetch;
  window.fetch = function (input, init) {
    let url = "";
    try {
      url = typeof input === "string"
        ? input
        : input && input.url
          ? input.url
          : "";
      if (isSpotifyUrl(url)) {
        let h = null;
        if (init && init.headers) {
          h = init.headers instanceof Headers
            ? init.headers
            : new Headers(init.headers);
        } else if (
          input &&
          input.headers &&
          typeof input.headers.get === "function"
        ) {
          h = input.headers;
        }
        if (h) capture(h.get("Authorization"), h.get("Client-Token"));
      }
    } catch {}

    const p = origFetch.apply(this, arguments);
    if (shouldCaptureBody(url)) {
      try {
        p.then((resp) => {
          try {
            if (resp && resp.ok) {
              const c = resp.clone();
              c.text().then((body) => captureBody(url, body)).catch(() => {});
            }
          } catch {}
        }).catch(() => {});
      } catch {}
    }
    return p;
  };

  const XHR = window.XMLHttpRequest;
  const origOpen = XHR.prototype.open;
  const origSetHeader = XHR.prototype.setRequestHeader;
  XHR.prototype.open = function (method, url) {
    this.__portcastUrl = url;
    return origOpen.apply(this, arguments);
  };
  XHR.prototype.setRequestHeader = function (name, value) {
    try {
      if (isSpotifyUrl(this.__portcastUrl) && typeof name === "string") {
        const lower = name.toLowerCase();
        if (
          lower === "authorization" &&
          typeof value === "string" &&
          value.startsWith("Bearer ")
        ) {
          capture(value, null);
        } else if (lower === "client-token" && typeof value === "string") {
          capture(null, value);
        }
      }
    } catch {}
    return origSetHeader.apply(this, arguments);
  };
})();
