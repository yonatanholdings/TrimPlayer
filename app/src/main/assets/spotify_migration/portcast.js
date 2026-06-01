// PortCast document builder — pure ES module.
//
// JS mirror of server/portcast_server/exporter.py. No DOM, no chrome.*,
// no fetch — this file takes already-fetched platform payloads in and
// returns a plain object matching the PortCast schema.
//
// Reused unchanged inside the Trimplayer mobile app's WebView, so it
// must stay platform-agnostic. The only platform-specific knowledge
// here is the *shape* of input payloads, which is documented at each
// `*FromSaved*` function.

export const SPEC_VERSION = "0.2.0";
export const GENERATOR_NAME = "PortCast Export";
export const GENERATOR_URL = "https://portcast.org";

const SPOTIFY_SOURCE = "spotify";

export function nowIso() {
  // ISO 8601 with "Z" suffix, second precision — matches the Python
  // exporter's _now_iso(), so the JS and Python paths produce
  // byte-identical timestamps for identical inputs.
  return new Date().toISOString().split(".")[0] + "Z";
}

export function normalizeReleaseDate(raw, precision) {
  // Spotify's release_date is one of YYYY / YYYY-MM / YYYY-MM-DD. The
  // spec wants an ISO 8601 instant, so coarser precisions widen to
  // start-of-day UTC. Returns null if the input isn't parseable so
  // the caller can drop the field.
  if (!raw) return null;
  const p = (precision || "day").toLowerCase();
  if (p === "year" && raw.length === 4) return `${raw}-01-01T00:00:00Z`;
  if (p === "month" && raw.length === 7) return `${raw}-01T00:00:00Z`;
  if (p === "day" && raw.length === 10) return `${raw}T00:00:00Z`;
  return null;
}

function stripNull(obj) {
  const out = {};
  for (const [k, v] of Object.entries(obj)) {
    if (v !== null && v !== undefined) out[k] = v;
  }
  return out;
}

export function subscriptionFromSavedShow(saved, capturedAt) {
  const show = saved.show || {};
  const showId = show.id;
  const images = show.images || [];
  const imageUrl = images.length > 0 && images[0] ? images[0].url : null;

  return stripNull({
    subscriptionId: cryptoRandomId(),
    title: show.name || "(untitled show)",
    author: show.publisher || null,
    imageUrl: imageUrl,
    subscribedAt: saved.added_at || null,
    platformRefs: showId ? [`spotify:show:${showId}`] : null,
    updatedAt: capturedAt,
  });
}

export function episodeFromSavedEpisode(saved, capturedAt) {
  const ep = saved.episode || {};
  const epId = ep.id;
  const show = ep.show || {};
  const showId = show.id;

  // An episode with no Spotify ID, or detached from its show, is
  // unaddressable on the import side — skip it rather than emit a
  // broken reference. Matches the Python exporter behavior exactly.
  if (!epId || !showId) return null;

  const durationMs = ep.duration_ms;
  const durationS =
    typeof durationMs === "number" ? durationMs / 1000.0 : null;

  const resume = ep.resume_point || {};
  const fullyPlayed = Boolean(resume.fully_played);
  const resumeMs = resume.resume_position_ms || 0;
  let positionS = resumeMs ? resumeMs / 1000.0 : null;
  let status;

  if (fullyPlayed) {
    status = "completed";
    // Once fully played the resume position is meaningless — don't
    // round-trip it into an importer.
    positionS = null;
  } else if (positionS && positionS > 0) {
    status = "in_progress";
  } else {
    status = "unplayed";
    positionS = null;
  }

  return stripNull({
    episodeStateId: cryptoRandomId(),
    subscriptionRef: { platformRefs: [`spotify:show:${showId}`] },
    platformRefs: [`spotify:episode:${epId}`],
    title: ep.name || null,
    publishedAt: normalizeReleaseDate(
      ep.release_date,
      ep.release_date_precision,
    ),
    durationSeconds: durationS,
    status: status,
    positionSeconds: positionS,
    source: SPOTIFY_SOURCE,
    capturedAt: capturedAt,
    updatedAt: capturedAt,
  });
}

function ownerFromMe(me) {
  if (!me) return null;
  const displayName = me.display_name || null;
  const email = me.email || null;
  if (!displayName && !email) return null;
  return stripNull({ displayName, email });
}

export function buildDocument({
  me,
  savedShows,
  savedEpisodes,
  generatorVersion,
  capturedAt,
}) {
  const ts = capturedAt || nowIso();

  const subscriptions = (savedShows || []).map((s) =>
    subscriptionFromSavedShow(s, ts),
  );

  const episodes = [];
  for (const saved of savedEpisodes || []) {
    const ep = episodeFromSavedEpisode(saved, ts);
    if (ep !== null) episodes.push(ep);
  }

  const completeness = [
    {
      section: "subscriptions",
      source: SPOTIFY_SOURCE,
      level: "full",
      capturedAt: ts,
      note: "All shows in the user's Spotify library at export time.",
    },
    {
      section: "episodes",
      source: SPOTIFY_SOURCE,
      level: "current-state-only",
      capturedAt: ts,
      note:
        "Spotify exposes resume_point only for saved episodes; " +
        "no per-episode event log is available.",
    },
  ];

  const doc = {
    portcast: SPEC_VERSION,
    generatedAt: ts,
    generator: stripNull({
      name: GENERATOR_NAME,
      version: generatorVersion || null,
      url: GENERATOR_URL,
    }),
    subscriptions,
    episodes,
    completeness,
  };

  const owner = ownerFromMe(me);
  if (owner) doc.owner = owner;

  return doc;
}

// crypto.randomUUID() is available in MV3 service workers, modern
// WebViews, and Node 19+; we use a hex-only form to match the
// Python exporter's _new_id().
function cryptoRandomId() {
  const uuid =
    typeof crypto !== "undefined" && crypto.randomUUID
      ? crypto.randomUUID()
      : pseudoUuid();
  return uuid.replace(/-/g, "");
}

function pseudoUuid() {
  // Fallback for environments without crypto.randomUUID (older test
  // runners). Not cryptographically strong; only used for opaque IDs
  // inside the document.
  const r = () => Math.floor(Math.random() * 0x10000).toString(16).padStart(4, "0");
  return `${r()}${r()}-${r()}-${r()}-${r()}-${r()}${r()}${r()}`;
}
