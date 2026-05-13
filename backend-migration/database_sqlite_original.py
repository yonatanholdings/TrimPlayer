"""
SQLite persistence layer for TrimBrain.

Schema overview:
  episodes           — one row per audio file ever seen
  audio_files        — local download path per episode
  fingerprints       — raw Dejavu hashes (prunable after promotion)
  jobs               — background job tracking
  raw_matches        — every pairwise segment detection result
  canonical_segments — segments appearing in >= N episodes (promoted)
  episode_segments   — per-episode position of a canonical segment
  fingerprint_log    — debug: fingerprint run stats
  match_log          — debug: match run stats
"""

import sqlite3
import os
import json
import logging
import threading
from contextlib import contextmanager
from typing import List, Tuple, Optional, Dict, Any
from collections import defaultdict, Counter

logger = logging.getLogger(__name__)

# All DB operations (reads and writes) go through this lock.
# In DELETE journal mode, concurrent writers starve each other via SQLite's
# busy retry loop. Routing everything through a Python lock gives a fair FIFO
# queue at the Python level, preventing starvation of small writes (set_audio_file,
# update_job) by rapid fingerprint batch writers.
_db_lock = threading.Lock()

DB_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "trimbrain.db")

# Storage eviction config (override via env vars)
MAX_AUDIO_BYTES: int = int(os.getenv("MAX_AUDIO_BYTES", str(15 * 1024 ** 3)))  # 15 GB
STREAMING_CACHE_DAYS: int = int(os.getenv("STREAMING_CACHE_DAYS", "7"))

@contextmanager
def get_db():
    """Write context: acquires _db_lock so writes are serialized at the Python level.
    WAL mode lets readers proceed concurrently; the Python lock prevents write starvation
    (without it, rapid alternating writers can starve small writes like set_audio_file).

    wal_autocheckpoint=10000 raises the auto-checkpoint threshold from the default 1000
    pages (4MB) to 10000 pages (~40MB). The default fired on every batch commit when the
    WAL exceeded 4MB, stalling each commit for seconds while SQLite flushed — catastrophic
    when 10 concurrent fingerprint stores were all serialized through _db_lock. Setting it
    to 10000 prevents that stall in normal operation while still capping WAL growth (avoids
    the unbounded growth that wal_autocheckpoint=0 caused during long historical sweeps).
    Explicit PASSIVE checkpoints in _wal_checkpoint() handle routine cleanup after each
    episode's store.
    """
    with _db_lock:
        conn = sqlite3.connect(DB_PATH, check_same_thread=False, timeout=30)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA wal_autocheckpoint=10000")
        conn.execute("PRAGMA foreign_keys=ON")
        conn.execute("PRAGMA synchronous=NORMAL")
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()


@contextmanager
def get_db_ro():
    """Read-only context: no lock. WAL mode guarantees reads never block behind writers."""
    conn = sqlite3.connect(DB_PATH, check_same_thread=False, timeout=30)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    try:
        yield conn
    finally:
        conn.close()


def _wal_checkpoint():
    """RESTART WAL checkpoint under _db_lock.

    RESTART waits for any in-progress read transaction to finish (readers start
    every ~6s from the job-poll endpoint), flushes all WAL frames to the main
    DB, then resets the WAL write position to the beginning. This keeps the WAL
    from growing unboundedly — PASSIVE was useless here because the job-poll
    reader was always active when the checkpoint fired.

    The Python lock ensures we don't race against other writers that have already
    passed _db_lock and called sqlite3.connect.
    """
    with _db_lock:
        try:
            conn = sqlite3.connect(DB_PATH, timeout=15)
            busy, log, ckpt = conn.execute("PRAGMA wal_checkpoint(RESTART)").fetchone()
            conn.close()
            if busy:
                logger.warning("WAL checkpoint incomplete: busy=%d log=%d ckpt=%d", busy, log, ckpt)
            wal_path = DB_PATH + "-wal"
            try:
                wal_mb = os.path.getsize(wal_path) / 1024 / 1024
                if wal_mb > 200:
                    logger.warning("WAL still %.0f MB after checkpoint (busy=%d)", wal_mb, busy)
            except OSError:
                pass
        except Exception as exc:
            logger.warning("WAL checkpoint failed: %s", exc)


def init_db():
    with get_db() as conn:
        conn.executescript("""
            CREATE TABLE IF NOT EXISTS episodes (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                url             TEXT UNIQUE NOT NULL,
                guid            TEXT,
                title           TEXT,
                podcast_title   TEXT,
                podcast_id      TEXT,
                published       TEXT,
                duration_seconds REAL,
                created_at      TEXT DEFAULT (datetime('now'))
            );

            CREATE TABLE IF NOT EXISTS audio_files (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                episode_id  INTEGER NOT NULL UNIQUE REFERENCES episodes(id),
                local_path  TEXT NOT NULL,
                size_bytes  INTEGER,
                download_ms INTEGER,
                downloaded_at TEXT DEFAULT (datetime('now'))
            );
            CREATE UNIQUE INDEX IF NOT EXISTS idx_audio_files_episode ON audio_files(episode_id);

            CREATE TABLE IF NOT EXISTS fingerprints (
                episode_id  INTEGER NOT NULL REFERENCES episodes(id),
                hash        INTEGER NOT NULL,
                timestamp   REAL NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_fp_hash    ON fingerprints(hash);
            CREATE INDEX IF NOT EXISTS idx_fp_episode ON fingerprints(episode_id);
            CREATE INDEX IF NOT EXISTS idx_fp_hash_ep ON fingerprints(hash, episode_id);

            CREATE TABLE IF NOT EXISTS jobs (
                id           TEXT PRIMARY KEY,
                status       TEXT NOT NULL DEFAULT 'running',
                podcast_id   TEXT,
                episode_urls TEXT DEFAULT '[]',
                message      TEXT,
                created_at   TEXT DEFAULT (datetime('now')),
                completed_at TEXT
            );

            CREATE TABLE IF NOT EXISTS raw_matches (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                ep_a_id      INTEGER NOT NULL REFERENCES episodes(id),
                ep_b_id      INTEGER NOT NULL REFERENCES episodes(id),
                start_a      REAL NOT NULL,
                end_a        REAL NOT NULL,
                start_b      REAL NOT NULL,
                end_b        REAL NOT NULL,
                offset_sec   REAL,
                confidence   REAL,
                hash_count   INTEGER,
                segment_type TEXT,
                detected_at  TEXT DEFAULT (datetime('now'))
            );
            CREATE INDEX IF NOT EXISTS idx_rm_ep_a ON raw_matches(ep_a_id);
            CREATE INDEX IF NOT EXISTS idx_rm_ep_b ON raw_matches(ep_b_id);

            CREATE TABLE IF NOT EXISTS canonical_segments (
                id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                podcast_id          TEXT NOT NULL,
                ref_episode_id      INTEGER NOT NULL REFERENCES episodes(id),
                ref_start           REAL NOT NULL,
                ref_end             REAL NOT NULL,
                segment_type        TEXT NOT NULL,
                episode_count       INTEGER DEFAULT 0,
                avg_confidence      REAL,
                min_confidence      REAL,
                first_seen_at       TEXT DEFAULT (datetime('now')),
                last_seen_at        TEXT DEFAULT (datetime('now')),
                promoting_match_ids TEXT
            );

            CREATE TABLE IF NOT EXISTS episode_segments (
                id                         INTEGER PRIMARY KEY AUTOINCREMENT,
                episode_id                 INTEGER NOT NULL REFERENCES episodes(id),
                canonical_segment_id       INTEGER NOT NULL REFERENCES canonical_segments(id),
                local_start                REAL NOT NULL,
                local_end                  REAL NOT NULL,
                confidence                 REAL,
                hash_count                 INTEGER,
                raw_match_id               INTEGER REFERENCES raw_matches(id),
                matched_against_episode_id INTEGER REFERENCES episodes(id),
                boundary_refined           INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS idx_es_episode   ON episode_segments(episode_id);
            CREATE INDEX IF NOT EXISTS idx_es_canonical ON episode_segments(canonical_segment_id);

            CREATE TABLE IF NOT EXISTS fingerprint_log (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                episode_id      INTEGER NOT NULL REFERENCES episodes(id),
                hash_count      INTEGER,
                duration_sec    REAL,
                cpu_time_ms     INTEGER,
                fingerprinted_at TEXT DEFAULT (datetime('now'))
            );

            CREATE TABLE IF NOT EXISTS match_log (
                id                   INTEGER PRIMARY KEY AUTOINCREMENT,
                ep_a_id              INTEGER NOT NULL REFERENCES episodes(id),
                ep_b_id              INTEGER NOT NULL REFERENCES episodes(id),
                raw_offset_candidates TEXT,
                raw_segment_count    INTEGER,
                final_segment_count  INTEGER,
                cpu_time_ms          INTEGER,
                run_at               TEXT DEFAULT (datetime('now'))
            );

            CREATE TABLE IF NOT EXISTS transcript_segments (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                episode_id   INTEGER NOT NULL REFERENCES episodes(id),
                start        REAL NOT NULL,
                end          REAL NOT NULL,
                segment_type TEXT NOT NULL,
                confidence   REAL,
                excerpt      TEXT,
                source       TEXT NOT NULL DEFAULT 'transcript',
                detected_at  TEXT DEFAULT (datetime('now'))
            );
            CREATE INDEX IF NOT EXISTS idx_ts_episode ON transcript_segments(episode_id);

            CREATE TABLE IF NOT EXISTS podcast_feed_meta (
                podcast_id         TEXT PRIMARY KEY,
                feed_episode_count INTEGER,
                algorithm_version  TEXT,
                updated_at         TEXT DEFAULT (datetime('now'))
            );
        """)
        # audio_files column migrations
        existing = {row[1] for row in conn.execute("PRAGMA table_info(audio_files)").fetchall()}
        if 'download_ms' not in existing:
            conn.execute("ALTER TABLE audio_files ADD COLUMN download_ms INTEGER")
        if 'last_accessed_at' not in existing:
            conn.execute("ALTER TABLE audio_files ADD COLUMN last_accessed_at TEXT")
        if 'processing_complete' not in existing:
            conn.execute("ALTER TABLE audio_files ADD COLUMN processing_complete INTEGER NOT NULL DEFAULT 0")

        # Add algorithm_version column to podcast_feed_meta if it doesn't exist yet
        pfm_cols = {row[1] for row in conn.execute("PRAGMA table_info(podcast_feed_meta)").fetchall()}
        if 'algorithm_version' not in pfm_cols:
            conn.execute("ALTER TABLE podcast_feed_meta ADD COLUMN algorithm_version TEXT")

        # Add guid column to episodes if it doesn't exist yet (migration for existing DBs)
        ep_cols = {row[1] for row in conn.execute("PRAGMA table_info(episodes)").fetchall()}
        if 'guid' not in ep_cols:
            conn.execute("ALTER TABLE episodes ADD COLUMN guid TEXT")

        # Add boundary_refined column to episode_segments if it doesn't exist yet
        es_cols = {row[1] for row in conn.execute("PRAGMA table_info(episode_segments)").fetchall()}
        if 'boundary_refined' not in es_cols:
            conn.execute("ALTER TABLE episode_segments ADD COLUMN boundary_refined INTEGER NOT NULL DEFAULT 0")

        # Reset boundary_refined for intro/outro segments so they are re-refined with the
        # improved logic (intro forward extension + outro snap to episode end).
        conn.execute("""
            UPDATE episode_segments SET boundary_refined = 0
            WHERE boundary_refined = 1
              AND canonical_segment_id IN (
                  SELECT id FROM canonical_segments WHERE segment_type IN ('intro', 'outro')
              )
        """)

        # Unique index on (guid, podcast_id) so the same episode at a new hosting URL
        # resolves to the same DB record rather than creating a duplicate.
        # Partial index (WHERE guid IS NOT NULL) so episodes without GUIDs are unaffected.
        try:
            conn.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_episodes_guid_podcast
                ON episodes(guid, podcast_id)
                WHERE guid IS NOT NULL
            """)
        except Exception:
            pass  # pre-existing GUID duplicates; harmless until they're cleaned up

        # Deduplicate audio_files after tables exist (no-op when already clean)
        conn.execute("""
            DELETE FROM audio_files WHERE id NOT IN (
                SELECT MAX(id) FROM audio_files GROUP BY episode_id
            )
        """)

        # On every startup, mark any jobs still in 'running' state as interrupted.
        # Background threads are killed on server restart and will never update their
        # status themselves, which would block new jobs for the same podcast.
        orphaned = conn.execute(
            "UPDATE jobs SET status='error', message='Interrupted by server restart', "
            "completed_at=datetime('now') WHERE status='running'"
        ).rowcount
        if orphaned:
            logger.info("Cleaned up %d orphaned running job(s) on startup", orphaned)


# ---------------------------------------------------------------------------
# Episodes
# ---------------------------------------------------------------------------

def upsert_episode(url: str, title: str = None, podcast_title: str = None,
                   podcast_id: str = None, published: str = None,
                   duration_seconds: float = None, guid: str = None) -> int:
    with get_db() as conn:
        # GUID-first: if we've seen this episode before (possibly at a different URL),
        # update its URL to the current one rather than creating a duplicate record.
        if guid and podcast_id:
            existing = conn.execute(
                "SELECT id FROM episodes WHERE guid=? AND podcast_id=?",
                (guid, podcast_id)
            ).fetchone()
            if existing:
                try:
                    conn.execute("""
                        UPDATE episodes SET
                            url              = ?,
                            title            = COALESCE(?, title),
                            podcast_title    = COALESCE(?, podcast_title),
                            published        = COALESCE(?, published),
                            duration_seconds = COALESCE(?, duration_seconds)
                        WHERE id = ?
                    """, (url, title, podcast_title, published, duration_seconds, existing['id']))
                except Exception:
                    # New URL already taken by a different record; update metadata only
                    conn.execute("""
                        UPDATE episodes SET
                            title            = COALESCE(?, title),
                            podcast_title    = COALESCE(?, podcast_title),
                            published        = COALESCE(?, published),
                            duration_seconds = COALESCE(?, duration_seconds)
                        WHERE id = ?
                    """, (title, podcast_title, published, duration_seconds, existing['id']))
                return existing['id']

        # No GUID match — fall back to URL upsert (also handles guid=None feeds)
        conn.execute("""
            INSERT INTO episodes (url, guid, title, podcast_title, podcast_id, published, duration_seconds)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(url) DO UPDATE SET
                guid             = COALESCE(excluded.guid, guid),
                title            = COALESCE(excluded.title, title),
                podcast_title    = COALESCE(excluded.podcast_title, podcast_title),
                podcast_id       = COALESCE(excluded.podcast_id, podcast_id),
                published        = COALESCE(excluded.published, published),
                duration_seconds = COALESCE(excluded.duration_seconds, duration_seconds)
        """, (url, guid, title, podcast_title, podcast_id, published, duration_seconds))
        row = conn.execute("SELECT id FROM episodes WHERE url=?", (url,)).fetchone()
        return row['id']


def upsert_episodes_bulk(rows: list, podcast_title: str, podcast_id: str) -> dict:
    """Upsert all episode metadata for a feed in a single transaction.
    Returns {url: episode_id} map.
    """
    result = {}
    with get_db() as conn:
        for r in rows:
            url, title, published, guid = r['url'], r['title'], r['published'], r['guid']
            if guid:
                existing = conn.execute(
                    "SELECT id FROM episodes WHERE guid=? AND podcast_id=?", (guid, podcast_id)
                ).fetchone()
                if existing:
                    conn.execute(
                        "UPDATE episodes SET url=?, title=COALESCE(?,title), "
                        "podcast_title=COALESCE(?,podcast_title), published=COALESCE(?,published) WHERE id=?",
                        (url, title, podcast_title, published, existing['id'])
                    )
                    result[url] = existing['id']
                    continue
            conn.execute("""
                INSERT INTO episodes (url, guid, title, podcast_title, podcast_id, published)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(url) DO UPDATE SET
                    guid          = COALESCE(excluded.guid, guid),
                    title         = COALESCE(excluded.title, title),
                    podcast_title = COALESCE(excluded.podcast_title, podcast_title),
                    published     = COALESCE(excluded.published, published)
            """, (url, guid, title, podcast_title, podcast_id, published))
            row = conn.execute("SELECT id FROM episodes WHERE url=?", (url,)).fetchone()
            result[url] = row['id']
    return result


def get_episode_by_url(url: str) -> Optional[Dict]:
    with get_db_ro() as conn:
        row = conn.execute("SELECT * FROM episodes WHERE url = ?", (url,)).fetchone()
        return dict(row) if row else None


def get_episode_by_guid(guid: str) -> Optional[Dict]:
    with get_db() as conn:
        row = conn.execute("SELECT * FROM episodes WHERE guid = ?", (guid,)).fetchone()
        return dict(row) if row else None


def get_episode_by_id(episode_id: int) -> Optional[Dict]:
    with get_db() as conn:
        row = conn.execute("SELECT * FROM episodes WHERE id = ?", (episode_id,)).fetchone()
        return dict(row) if row else None


def get_all_episodes(podcast_id: str = None) -> List[Dict]:
    with get_db() as conn:
        if podcast_id:
            rows = conn.execute(
                "SELECT * FROM episodes WHERE podcast_id = ? ORDER BY created_at DESC",
                (podcast_id,)
            ).fetchall()
        else:
            rows = conn.execute("SELECT * FROM episodes ORDER BY created_at DESC").fetchall()
        return [dict(r) for r in rows]


def get_episodes_with_segment_counts(limit: int = 200, offset: int = 0) -> List[Dict]:
    """Return episodes with pre-computed segment counts, paginated.

    canonical_count counts "cluster starts": episode_segments rows where no
    earlier row for the same episode exists within 120 seconds.  This mirrors
    the GAP_FILL_SEC=120 threshold used by get_segments_for_url and correctly
    collapses the many duplicate canonical rows for the same jingle window (e.g.
    36 rows at 1916s/1933s/1957s for the same outro) into a single count of 1.
    """
    with get_db() as conn:
        rows = conn.execute("""
            SELECT
                e.id, e.url, e.title, e.podcast_title, e.podcast_id,
                e.duration_seconds, e.created_at,
                (SELECT COUNT(DISTINCT canonical_segment_id)
                 FROM episode_segments WHERE episode_id = e.id)                          AS canonical_count,
                (SELECT COUNT(*) FROM transcript_segments ts
                 WHERE ts.episode_id = e.id AND ts.source != 'transcript_done')          AS transcript_count
            FROM episodes e
            ORDER BY e.podcast_id, e.created_at DESC
            LIMIT ? OFFSET ?
        """, (limit, offset)).fetchall()
        total = conn.execute("SELECT COUNT(*) FROM episodes").fetchone()[0]
        return {'items': [dict(r) for r in rows], 'total': total, 'limit': limit, 'offset': offset}


def update_episode_duration(episode_id: int, duration_seconds: float):
    """Persist audio duration discovered at fingerprint time."""
    with get_db() as conn:
        conn.execute(
            "UPDATE episodes SET duration_seconds = ? WHERE id = ? AND duration_seconds IS NULL",
            (duration_seconds, episode_id)
        )


def refine_episode_segment_boundaries(episode_id: int, local_path: str) -> int:
    """Expand detected segment boundaries outward to the nearest audio transition.

    Intro:
      - Snap local_start to 0 (fingerprinting misses the first 0-5s).
      - Walk forward from local_end using VAD to find where content begins
        after the jingle (extension window: 120s).

    Outro (all types, including same-jingle):
      - Walk backward from local_start (energy boundary, 15s) to find where
        host speech ended before the jingle.
      - Snap local_end to episode duration — the outro runs to episode end.

    Ad:
      - Fingerprinting only catches the ad jingle/bumper. Use silero-VAD to
        find the farthest major silence in each direction (window: 120s).

    Marks boundary_refined=1 on every processed row so this is idempotent.
    Returns the number of rows updated.
    """
    from .audio_processor import extend_to_energy_boundary, extend_to_vad_boundary
    import librosa

    processed = 0

    try:
        episode_dur = librosa.get_duration(path=local_path)
    except Exception:
        episode_dur = None

    # ── Intro/outro refinement ─────────────────────────────────────────────────
    with get_db() as conn:
        boundary_rows = conn.execute("""
            SELECT es.id, es.local_start, es.local_end, cs.segment_type
            FROM episode_segments es
            JOIN canonical_segments cs ON cs.id = es.canonical_segment_id
            WHERE es.episode_id       = ?
              AND es.boundary_refined = 0
              AND cs.segment_type     IN ('intro', 'outro')
        """, (episode_id,)).fetchall()

    for row in boundary_rows:
        effective_type = _reclassify_segment_type(row['segment_type'], row['local_start'])

        if effective_type == 'intro':
            new_start = 0.0
            new_end = extend_to_vad_boundary(
                local_path, row['local_end'], 'forward', max_extension_sec=120.0)
        else:  # outro — keep fingerprinted start (backward extension overshoots), snap end to EOF
            new_start = row['local_start']
            new_end = episode_dur if episode_dur is not None else row['local_end']

        with get_db() as conn:
            conn.execute(
                "UPDATE episode_segments SET local_start=?, local_end=?, boundary_refined=1 WHERE id=?",
                (new_start, new_end, row['id']),
            )
        processed += 1
        logger.info(
            "Refined %s ep_seg %d: %.2f–%.2f → %.2f–%.2f",
            effective_type, row['id'],
            row['local_start'], row['local_end'], new_start, new_end,
        )

    # ── Ad refinement (backward + forward, large window) ─────────────────────
    _AD_MAX_EXTENSION_SEC = 120.0

    with get_db() as conn:
        ad_rows = conn.execute("""
            SELECT es.id, es.local_start, es.local_end
            FROM episode_segments es
            JOIN canonical_segments cs ON cs.id = es.canonical_segment_id
            WHERE es.episode_id       = ?
              AND es.boundary_refined = 0
              AND cs.segment_type     = 'ad'
        """, (episode_id,)).fetchall()

    for row in ad_rows:
        new_start = extend_to_vad_boundary(
            local_path, row['local_start'], 'backward', _AD_MAX_EXTENSION_SEC)
        new_end = extend_to_vad_boundary(
            local_path, row['local_end'], 'forward', _AD_MAX_EXTENSION_SEC)

        with get_db() as conn:
            conn.execute(
                "UPDATE episode_segments SET local_start=?, local_end=?, boundary_refined=1 WHERE id=?",
                (new_start, new_end, row['id']),
            )
        processed += 1
        logger.info(
            "Refined ad ep_seg %d: %.2f–%.2f → %.2f–%.2f (−%.2fs start, +%.2fs end)",
            row['id'],
            row['local_start'], row['local_end'],
            new_start, new_end,
            row['local_start'] - new_start,
            new_end - row['local_end'],
        )

    return processed


# ---------------------------------------------------------------------------
# Audio files
# ---------------------------------------------------------------------------

def set_audio_file(episode_id: int, local_path: str, size_bytes: int = None, download_ms: int = None):
    if size_bytes is None and local_path and os.path.exists(local_path):
        try:
            size_bytes = os.path.getsize(local_path)
        except OSError:
            pass
    with get_db() as conn:
        conn.execute("DELETE FROM audio_files WHERE episode_id = ?", (episode_id,))
        conn.execute(
            """INSERT INTO audio_files
               (episode_id, local_path, size_bytes, download_ms, processing_complete)
               VALUES (?, ?, ?, ?, 0)""",
            (episode_id, local_path, size_bytes, download_ms)
        )


def get_audio_file_path(episode_id: int) -> Optional[str]:
    with get_db_ro() as conn:
        row = conn.execute(
            "SELECT local_path FROM audio_files WHERE episode_id = ?", (episode_id,)
        ).fetchone()
        return row['local_path'] if row else None


def touch_audio_accessed_by_path(local_path: str):
    """Record that this audio file was served (for LRU streaming cache tracking)."""
    with get_db() as conn:
        conn.execute(
            "UPDATE audio_files SET last_accessed_at = datetime('now') WHERE local_path = ?",
            (local_path,),
        )


def mark_audio_processing_complete(episode_id: int):
    """Mark audio file as safe to evict once refinement is done.

    Conditions: fingerprints stored AND no unrefined episode_segments rows.
    Unmatched episodes (0 segments) qualify immediately after fingerprinting —
    template matching uses DB fingerprints and doesn't need the audio file.
    """
    with get_db() as conn:
        has_fp = conn.execute(
            "SELECT 1 FROM fingerprints WHERE episode_id = ? LIMIT 1", (episode_id,)
        ).fetchone()
        if not has_fp:
            return

        unrefined = conn.execute(
            "SELECT COUNT(*) FROM episode_segments WHERE episode_id = ? AND boundary_refined = 0",
            (episode_id,),
        ).fetchone()[0]
        if unrefined > 0:
            return

        conn.execute(
            "UPDATE audio_files SET processing_complete = 1 WHERE episode_id = ?",
            (episode_id,),
        )


def evict_audio_files(
    max_bytes: int = MAX_AUDIO_BYTES,
    keep_accessed_days: int = STREAMING_CACHE_DAYS,
) -> Tuple[int, int]:
    """Delete audio files that are processing-complete and not recently accessed.

    Pass 1 — stale eviction: delete processing-complete files whose
              last_accessed_at is older than keep_accessed_days (or NULL).
    Pass 2 — cap enforcement: if total storage still exceeds max_bytes,
              delete LRU processing-complete files until under the cap.

    Files with processing_complete=0 are never touched.
    Returns (files_deleted, bytes_freed).
    """
    deleted = 0
    freed = 0

    # Pass 1: delete stale processing-complete files
    with get_db() as conn:
        stale = conn.execute("""
            SELECT id, local_path, size_bytes FROM audio_files
            WHERE processing_complete = 1
              AND local_path IS NOT NULL
              AND (
                last_accessed_at IS NULL
                OR last_accessed_at < datetime('now', ? || ' days')
              )
            ORDER BY last_accessed_at ASC NULLS FIRST
        """, (f'-{keep_accessed_days}',)).fetchall()

    for row in stale:
        path = row['local_path']
        if path and os.path.exists(path):
            try:
                os.remove(path)
                freed += row['size_bytes'] or 0
                deleted += 1
            except OSError as exc:
                logger.warning("Eviction: could not delete %s: %s", path, exc)
        with get_db() as conn:
            conn.execute(
                "DELETE FROM audio_files WHERE id = ?", (row['id'],)
            )

    # Pass 2: cap enforcement
    with get_db() as conn:
        total = conn.execute(
            "SELECT COALESCE(SUM(size_bytes), 0) FROM audio_files WHERE local_path IS NOT NULL"
        ).fetchone()[0]

    if total > max_bytes:
        with get_db() as conn:
            candidates = conn.execute("""
                SELECT id, local_path, size_bytes FROM audio_files
                WHERE processing_complete = 1
                  AND local_path IS NOT NULL
                ORDER BY last_accessed_at ASC NULLS FIRST
            """).fetchall()

        for row in candidates:
            if total <= max_bytes:
                break
            path = row['local_path']
            sz = row['size_bytes'] or 0
            if path and os.path.exists(path):
                try:
                    os.remove(path)
                    freed += sz
                    total -= sz
                    deleted += 1
                except OSError as exc:
                    logger.warning("Eviction cap: could not delete %s: %s", path, exc)
            with get_db() as conn:
                conn.execute(
                    "DELETE FROM audio_files WHERE id = ?", (row['id'],)
                )

    if deleted:
        logger.info("Eviction: removed %d file(s), freed %.1f MB", deleted, freed / 1024 / 1024)
    return deleted, freed


# ---------------------------------------------------------------------------
# Fingerprints
# ---------------------------------------------------------------------------

def store_fingerprints(episode_id: int, hashes: List[Tuple[int, float]]):
    """Replace all fingerprints for this episode in a single transaction.

    One lock acquisition instead of N/5000+1 acquisitions: with 10 concurrent
    episodes each having 200k fingerprints, the old batched approach created
    400+ serialized lock acquisitions, causing 13+ minute delays.
    """
    rows = [(episode_id, int(h), float(t)) for h, t in hashes]
    with get_db() as conn:
        conn.execute("DELETE FROM fingerprints WHERE episode_id = ?", (episode_id,))
        conn.executemany(
            "INSERT INTO fingerprints (episode_id, hash, timestamp) VALUES (?, ?, ?)",
            rows,
        )
    _wal_checkpoint()


def get_fingerprints(episode_id: int) -> List[Tuple[int, float]]:
    with get_db_ro() as conn:
        rows = conn.execute(
            "SELECT hash, timestamp FROM fingerprints WHERE episode_id = ?", (episode_id,)
        ).fetchall()
        return [(r['hash'], r['timestamp']) for r in rows]


def has_fingerprints(episode_id: int) -> bool:
    with get_db() as conn:
        row = conn.execute(
            "SELECT 1 FROM fingerprints WHERE episode_id = ? LIMIT 1", (episode_id,)
        ).fetchone()
        return row is not None


def get_fingerprints_in_window(episode_id: int, start_sec: float,
                               end_sec: float) -> List[Tuple[int, float]]:
    """Return stored fingerprint hashes within [start_sec, end_sec] for template matching."""
    with get_db() as conn:
        rows = conn.execute(
            "SELECT hash, timestamp FROM fingerprints "
            "WHERE episode_id = ? AND timestamp BETWEEN ? AND ?",
            (episode_id, start_sec, end_sec),
        ).fetchall()
        return [(r['hash'], r['timestamp']) for r in rows]


def get_hash_similar_episodes(
    ep_id: int,
    exclude_ep_ids: list,
    podcast_id: str,
    min_shared: int = 50,
    limit: int = 5,
) -> List[Tuple[int, int]]:
    """Return [(episode_id, shared_hash_count)] for historical episodes in podcast_id
    that share at least min_shared fingerprint hashes with ep_id.

    Two-step approach that keeps the self-join fast:
    1. Pre-fetch the small set of candidate episode IDs for this podcast (~10s–100s rows).
    2. Do the hash self-join with a LIMIT 10k sample and filter by episode_id IN (...).
       The covering index idx_fp_hash_ep(hash, episode_id) satisfies both the hash join
       and the episode_id filter without touching the fingerprints heap, so each hash
       lookup is an index-only scan.
    """
    exclude_set = set(exclude_ep_ids) | {ep_id}
    sample_size = 10_000
    with get_db_ro() as conn:
        # Step 1: get candidate episode IDs for this podcast (excluding current batch)
        cand_rows = conn.execute(
            "SELECT id FROM episodes WHERE podcast_id = ? AND id NOT IN (%s)"
            % ','.join('?' * len(exclude_set)),
            [podcast_id, *exclude_set],
        ).fetchall()
        candidate_ids = [r[0] for r in cand_rows]
        if not candidate_ids:
            return []

        total_hashes = (conn.execute(
            "SELECT COUNT(*) FROM fingerprints WHERE episode_id = ?", (ep_id,)
        ).fetchone() or (0,))[0]
        if total_hashes == 0:
            return []

        # Scale the minimum shared count to the sample fraction so we don't
        # miss candidates when the episode has more hashes than the sample cap.
        scaled_min = max(3, round(min_shared * min(sample_size, total_hashes) / total_hashes))
        cand_ph = ','.join('?' * len(candidate_ids))

        # Step 2: hash self-join using covering index — no episodes table join needed
        rows = conn.execute(f'''
            SELECT f2.episode_id, COUNT(*) AS shared
            FROM (SELECT hash FROM fingerprints WHERE episode_id = ? LIMIT ?) f1
            JOIN fingerprints f2 ON f1.hash = f2.hash
            WHERE f2.episode_id IN ({cand_ph})
            GROUP BY f2.episode_id
            HAVING shared >= ?
            ORDER BY shared DESC
            LIMIT ?
        ''', [ep_id, sample_size, *candidate_ids, scaled_min, limit]).fetchall()
    return [(r['episode_id'], r['shared']) for r in rows]


def get_episode_duration(ep_id: int) -> float:
    """Return cached duration_seconds for an episode (0.0 if not stored)."""
    with get_db_ro() as conn:
        row = conn.execute(
            "SELECT duration_seconds FROM episodes WHERE id = ?", (ep_id,)
        ).fetchone()
    return float(row['duration_seconds'] or 0.0) if row else 0.0


def canonical_already_mapped(episode_id: int, canonical_segment_id: int) -> bool:
    """True if this episode already has an episode_segments row for this canonical segment."""
    with get_db() as conn:
        row = conn.execute(
            "SELECT 1 FROM episode_segments "
            "WHERE episode_id = ? AND canonical_segment_id = ? LIMIT 1",
            (episode_id, canonical_segment_id),
        ).fetchone()
        return row is not None


def store_template_match(episode_id: int, canonical_segment_id: int,
                         local_start: float, local_end: float,
                         confidence: float, hash_count: int,
                         ref_episode_id: int):
    """Write a template-matched segment directly to episode_segments and update the count."""
    with get_db() as conn:
        existing = conn.execute(
            "SELECT id FROM episode_segments "
            "WHERE episode_id = ? AND canonical_segment_id = ?",
            (episode_id, canonical_segment_id),
        ).fetchone()
        if existing:
            return
        conn.execute(
            """
            INSERT INTO episode_segments
                (episode_id, canonical_segment_id, local_start, local_end,
                 confidence, hash_count, matched_against_episode_id)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (episode_id, canonical_segment_id, local_start, local_end,
             confidence, hash_count, ref_episode_id),
        )
        conn.execute(
            """
            UPDATE canonical_segments
            SET episode_count = (
                SELECT COUNT(*) FROM episode_segments WHERE canonical_segment_id = ?
            ), last_seen_at = datetime('now')
            WHERE id = ?
            """,
            (canonical_segment_id, canonical_segment_id),
        )


def get_episode_ids_for_podcast(podcast_id: str) -> List[int]:
    """Return all episode IDs that belong to the given podcast."""
    with get_db() as conn:
        rows = conn.execute(
            "SELECT id FROM episodes WHERE podcast_id = ? ORDER BY created_at",
            (podcast_id,),
        ).fetchall()
        return [r['id'] for r in rows]


def prune_fingerprints(episode_id: int, keep_windows: List[Tuple[float, float]],
                       buffer_sec: float = 2.0) -> int:
    """Delete fingerprints outside the given time windows. Returns count deleted."""
    if not keep_windows:
        return 0
    conditions = " OR ".join(
        f"(timestamp BETWEEN {max(0, s - buffer_sec)} AND {e + buffer_sec})"
        for s, e in keep_windows
    )
    with get_db() as conn:
        return conn.execute(
            f"DELETE FROM fingerprints WHERE episode_id = ? AND NOT ({conditions})",
            (episode_id,)
        ).rowcount


# ---------------------------------------------------------------------------
# Jobs
# ---------------------------------------------------------------------------

def create_job(job_id: str, podcast_id: str = None):
    with get_db() as conn:
        conn.execute("""
            INSERT OR REPLACE INTO jobs (id, status, podcast_id, episode_urls, message)
            VALUES (?, 'running', ?, '[]', 'Analysis started')
        """, (job_id, podcast_id))


def update_job(job_id: str, status: str, episode_urls: list = None, message: str = None):
    with get_db() as conn:
        conn.execute("""
            UPDATE jobs SET
                status       = ?,
                episode_urls = COALESCE(?, episode_urls),
                message      = COALESCE(?, message),
                completed_at = CASE WHEN ? IN ('done', 'error')
                                    THEN datetime('now') ELSE completed_at END
            WHERE id = ?
        """, (
            status,
            json.dumps(episode_urls) if episode_urls is not None else None,
            message,
            status,
            job_id
        ))


def cancel_job(job_id: str):
    with get_db() as conn:
        conn.execute(
            "UPDATE jobs SET status='cancelled', message='Cancelled by user', "
            "completed_at=datetime('now') WHERE id = ?",
            (job_id,),
        )


def clear_analysis_for_podcast(podcast_id: str) -> dict:
    """
    Delete all derived analysis data for a podcast while keeping episodes,
    audio_files, and fingerprints (the expensive artefacts).

    Clears: raw_matches, canonical_segments, episode_segments, match_log.
    Returns counts of deleted rows for each table.
    """
    with get_db() as conn:
        ep_ids = [
            r[0] for r in conn.execute(
                "SELECT id FROM episodes WHERE podcast_id = ?", (podcast_id,)
            ).fetchall()
        ]
        if not ep_ids:
            return {}

        placeholders = ','.join('?' * len(ep_ids))

        # Delete in child-first order to satisfy FK constraints:
        # episode_segments.raw_match_id → raw_matches
        # episode_segments.canonical_segment_id → canonical_segments
        es = conn.execute(
            f"DELETE FROM episode_segments WHERE episode_id IN ({placeholders})",
            ep_ids,
        ).rowcount

        rm = conn.execute(
            f"DELETE FROM raw_matches WHERE ep_a_id IN ({placeholders}) OR ep_b_id IN ({placeholders})",
            ep_ids + ep_ids,
        ).rowcount

        cs = conn.execute(
            "DELETE FROM canonical_segments WHERE podcast_id = ?", (podcast_id,)
        ).rowcount

        ml = conn.execute(
            f"DELETE FROM match_log WHERE ep_a_id IN ({placeholders}) OR ep_b_id IN ({placeholders})",
            ep_ids + ep_ids,
        ).rowcount

    return {'raw_matches': rm, 'episode_segments': es,
            'canonical_segments': cs, 'match_log': ml}


def get_local_episodes_for_podcast(podcast_id: str) -> List[Dict]:
    """Return episodes that have a locally available audio file, ordered by created_at."""
    with get_db() as conn:
        rows = conn.execute(
            """
            SELECT e.id, e.url, e.title, af.local_path
            FROM episodes e
            JOIN audio_files af ON af.episode_id = e.id
            WHERE e.podcast_id = ? AND af.local_path IS NOT NULL
            ORDER BY e.created_at
            """,
            (podcast_id,),
        ).fetchall()
        return [dict(r) for r in rows]


def get_all_podcast_ids() -> List[str]:
    """Return all distinct podcast_ids that have at least one local episode."""
    with get_db() as conn:
        rows = conn.execute(
            """
            SELECT DISTINCT e.podcast_id
            FROM episodes e
            JOIN audio_files af ON af.episode_id = e.id
            WHERE e.podcast_id IS NOT NULL AND af.local_path IS NOT NULL
            """,
        ).fetchall()
        return [r[0] for r in rows]


def get_job(job_id: str) -> Optional[Dict]:
    with get_db() as conn:
        row = conn.execute("SELECT * FROM jobs WHERE id = ?", (job_id,)).fetchone()
        if not row:
            return None
        d = dict(row)
        d['episode_urls'] = json.loads(d['episode_urls'] or '[]')
        return d


def get_podcasts_needing_continuation() -> List[Dict]:
    """Return podcasts that have at least one episode without fingerprints.
    Used on startup to resume processing interrupted by a server restart."""
    with get_db_ro() as conn:
        rows = conn.execute("""
            SELECT e.podcast_id, COUNT(*) AS unprocessed_count
            FROM episodes e
            WHERE e.podcast_id IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1 FROM fingerprints WHERE episode_id = e.id LIMIT 1
              )
            GROUP BY e.podcast_id
        """).fetchall()
        return [{'podcast_id': r['podcast_id'], 'unprocessed_count': r['unprocessed_count']}
                for r in rows]


def has_running_job_for_podcast(podcast_id: str) -> bool:
    """True if there is already a running analysis job for this podcast."""
    with get_db_ro() as conn:
        row = conn.execute(
            "SELECT 1 FROM jobs WHERE podcast_id = ? AND status = 'running' LIMIT 1",
            (podcast_id,),
        ).fetchone()
        return row is not None


def get_running_job_for_podcast(podcast_id: str) -> Optional[Dict]:
    """Return the most recent running job for this podcast, or None."""
    with get_db() as conn:
        row = conn.execute(
            "SELECT * FROM jobs WHERE podcast_id = ? AND status = 'running' ORDER BY created_at DESC LIMIT 1",
            (podcast_id,),
        ).fetchone()
        if not row:
            return None
        d = dict(row)
        d['episode_urls'] = json.loads(d['episode_urls'] or '[]')
        return d


def get_recent_jobs(limit: int = 20) -> List[Dict]:
    with get_db_ro() as conn:
        rows = conn.execute("""
            SELECT id, status, podcast_id, message, created_at, completed_at
            FROM jobs
            ORDER BY created_at DESC
            LIMIT ?
        """, (limit,)).fetchall()
        return [dict(r) for r in rows]


# ---------------------------------------------------------------------------
# Raw matches
# ---------------------------------------------------------------------------

def store_raw_match(ep_a_id: int, ep_b_id: int, seg: Dict) -> int:
    with get_db() as conn:
        cur = conn.execute("""
            INSERT INTO raw_matches
                (ep_a_id, ep_b_id, start_a, end_a, start_b, end_b,
                 offset_sec, confidence, hash_count, segment_type)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            ep_a_id, ep_b_id,
            seg.get('start_a'), seg.get('end_a'),
            seg.get('start_b'), seg.get('end_b'),
            seg.get('offset'), seg.get('confidence'),
            seg.get('hash_count'), seg.get('type')
        ))
        return cur.lastrowid


def get_raw_matches_for_episode(episode_id: int) -> List[Dict]:
    with get_db_ro() as conn:
        rows = conn.execute("""
            SELECT rm.*, ea.url as url_a, ea.title as title_a,
                         eb.url as url_b, eb.title as title_b
            FROM raw_matches rm
            JOIN episodes ea ON ea.id = rm.ep_a_id
            JOIN episodes eb ON eb.id = rm.ep_b_id
            WHERE rm.ep_a_id = ? OR rm.ep_b_id = ?
            ORDER BY rm.start_a
        """, (episode_id, episode_id)).fetchall()
        return [dict(r) for r in rows]


def pair_already_matched(ep_a_id: int, ep_b_id: int) -> bool:
    # Check match_log (written for every analysis run, even when 0 segments are found)
    # rather than raw_matches (only written when ≥1 segment is found). Using raw_matches
    # permanently blocked re-analysis of pairs where some segments were missed on the
    # first pass.
    with get_db_ro() as conn:
        row = conn.execute("""
            SELECT 1 FROM match_log
            WHERE (ep_a_id = ? AND ep_b_id = ?) OR (ep_a_id = ? AND ep_b_id = ?)
            LIMIT 1
        """, (ep_a_id, ep_b_id, ep_b_id, ep_a_id)).fetchone()
        return row is not None


# ---------------------------------------------------------------------------
# Canonical segments
# ---------------------------------------------------------------------------

def get_all_podcast_episodes_with_audio(podcast_id: str) -> List[Dict]:
    """Return all episodes for a podcast with their cached audio path (if any)."""
    with get_db() as conn:
        rows = conn.execute("""
            SELECT e.id, e.url, af.local_path
            FROM episodes e
            LEFT JOIN audio_files af ON af.episode_id = e.id
            WHERE e.podcast_id = ?
            ORDER BY e.created_at
        """, (podcast_id,)).fetchall()
        return [dict(r) for r in rows]


def get_canonical_segment_by_id(canonical_id: int) -> Optional[Dict]:
    with get_db() as conn:
        row = conn.execute(
            "SELECT * FROM canonical_segments WHERE id = ?", (canonical_id,)
        ).fetchone()
        return dict(row) if row else None


def try_promote_clusters(podcast_id: str, min_consensus: int = 3,
                         time_tolerance: float = 10.0) -> Tuple[int, List[int]]:
    """
    Cluster raw_matches by overlapping intervals in ep_a — the reference episode's
    position. Two matches belong to the same cluster when their [start_a, end_a]
    intervals overlap (within time_tolerance). This handles all segment types:
    - Intro: all matches start near 0 in ep_a — always one cluster
    - Outro: all matches end near ep_a's duration — always one cluster
    - Ad:    matches cluster by their position in ep_a; start_a can vary by
             10-30s across pairs due to fingerprint alignment noise, so
             fixed-size buckets would split the same ad into multiple groups

    A cluster with N distinct ep_b_ids covers N+1 total episodes (ep_a + all
    ep_b's). Promote when that total >= min_consensus.
    """
    with get_db() as conn:
        rows = conn.execute("""
            SELECT rm.id, rm.ep_a_id, rm.ep_b_id,
                   rm.start_a, rm.end_a, rm.start_b, rm.end_b,
                   rm.confidence, rm.hash_count, rm.segment_type
            FROM raw_matches rm
            JOIN episodes ea ON ea.id = rm.ep_a_id
            JOIN episodes eb ON eb.id = rm.ep_b_id
            WHERE ea.podcast_id = ? AND eb.podcast_id = ?
        """, (podcast_id, podcast_id)).fetchall()

    if not rows:
        return 0, []

    # Step 1: group matches by ep_a_id
    by_ep_a: Dict = defaultdict(list)
    for r in rows:
        by_ep_a[r['ep_a_id']].append(dict(r))

    # Step 2: within each ep_a, merge matches whose [start_a, end_a] intervals
    # overlap (gap <= time_tolerance). Sorted scan — O(n log n).
    groups: Dict = {}
    group_id = 0
    for ep_a_id, matches in by_ep_a.items():
        matches.sort(key=lambda m: m['start_a'])
        cluster: list = []
        cluster_end = -1.0
        for m in matches:
            if cluster and m['start_a'] <= cluster_end + time_tolerance:
                cluster.append(m)
                cluster_end = max(cluster_end, m['end_a'])
            else:
                if cluster:
                    groups[(ep_a_id, group_id)] = cluster
                    group_id += 1
                cluster = [m]
                cluster_end = m['end_a']
        if cluster:
            groups[(ep_a_id, group_id)] = cluster
            group_id += 1

    promoted = 0
    new_intro_outro_ids: List[int] = []
    for (ep_a_id, _), matches in groups.items():
        ep_b_ids = {m['ep_b_id'] for m in matches}
        # Total distinct episodes = ep_a + all ep_b's
        all_ep_ids = ep_b_ids | {ep_a_id}
        if len(all_ep_ids) < min_consensus:
            continue

        # Canonical position derived from ep_a (the reference)
        ref_start = sum(m['start_a'] for m in matches) / len(matches)
        ref_end   = sum(m['end_a']   for m in matches) / len(matches)
        confs     = [m['confidence'] for m in matches if m['confidence'] is not None]
        match_ids = [m['id'] for m in matches]
        # Majority vote on segment_type — prevents one mislabelled match from
        # corrupting the canonical (e.g. same-jingle intro/outro confusion).
        type_votes = Counter(m['segment_type'] for m in matches if m['segment_type'])
        seg_type   = type_votes.most_common(1)[0][0] if type_votes else 'ad'
        avg_conf  = sum(confs) / len(confs) if confs else None
        min_conf  = min(confs)              if confs else None

        with get_db() as conn:
            # Check if already promoted — same ref episode + approx position, OR
            # same type and ep_a already appears as an ep_b in another canonical
            # (prevents duplicate intros/outros when two ref episodes both get promoted
            # for the same underlying audio event).
            existing = conn.execute("""
                SELECT id FROM canonical_segments
                WHERE podcast_id = ? AND ref_episode_id = ? AND ABS(ref_start - ?) < ?
            """, (podcast_id, ep_a_id, ref_start, time_tolerance)).fetchone()

            if not existing:
                existing = conn.execute("""
                    SELECT cs.id FROM canonical_segments cs
                    JOIN episode_segments es ON es.canonical_segment_id = cs.id
                    WHERE cs.podcast_id = ? AND cs.segment_type = ?
                      AND es.episode_id = ? AND ABS(es.local_start - ?) < ?
                    LIMIT 1
                """, (podcast_id, seg_type, ep_a_id, ref_start, time_tolerance * 3)).fetchone()

            if existing:
                # Include segment_type so re-runs correct stale labels via majority vote.
                conn.execute("""
                    UPDATE canonical_segments SET
                        segment_type        = ?,
                        episode_count       = ?,
                        avg_confidence      = ?,
                        min_confidence      = ?,
                        last_seen_at        = datetime('now'),
                        promoting_match_ids = ?
                    WHERE id = ?
                """, (seg_type, len(all_ep_ids), avg_conf, min_conf,
                      json.dumps(match_ids), existing['id']))
                canonical_id = existing['id']
            else:
                cur = conn.execute("""
                    INSERT INTO canonical_segments
                        (podcast_id, ref_episode_id, ref_start, ref_end, segment_type,
                         episode_count, avg_confidence, min_confidence, promoting_match_ids)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, (podcast_id, ep_a_id, ref_start, ref_end, seg_type,
                      len(all_ep_ids), avg_conf, min_conf, json.dumps(match_ids)))
                canonical_id = cur.lastrowid
                promoted += 1
                if seg_type in ('intro', 'outro'):
                    new_intro_outro_ids.append(canonical_id)

            # ep_a's position in this segment (the reference)
            exists_a = conn.execute("""
                SELECT id FROM episode_segments
                WHERE episode_id = ? AND canonical_segment_id = ?
            """, (ep_a_id, canonical_id)).fetchone()
            if not exists_a:
                conn.execute("""
                    INSERT INTO episode_segments
                        (episode_id, canonical_segment_id, local_start, local_end, confidence)
                    VALUES (?, ?, ?, ?, ?)
                """, (ep_a_id, canonical_id, ref_start, ref_end, avg_conf))

            # Each ep_b's position in this segment (from its raw_match)
            for m in matches:
                exists = conn.execute("""
                    SELECT id FROM episode_segments
                    WHERE episode_id = ? AND canonical_segment_id = ?
                """, (m['ep_b_id'], canonical_id)).fetchone()
                if not exists:
                    conn.execute("""
                        INSERT INTO episode_segments
                            (episode_id, canonical_segment_id, local_start, local_end,
                             confidence, hash_count, raw_match_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, (m['ep_b_id'], canonical_id, m['start_b'], m['end_b'],
                          m['confidence'], m['hash_count'], m['id']))

            # Sync episode_count from actual episode_segments rows (fixes bucket overwrite bug)
            conn.execute("""
                UPDATE canonical_segments SET episode_count = (
                    SELECT COUNT(*) FROM episode_segments WHERE canonical_segment_id = ?
                ) WHERE id = ?
            """, (canonical_id, canonical_id))

        # Prune fingerprints for all involved episodes
        for ep_id in all_ep_ids:
            segs    = get_episode_segments_for_id(ep_id)
            windows = [(s['local_start'], s['local_end']) for s in segs]
            if windows:
                prune_fingerprints(ep_id, windows)

    return promoted, new_intro_outro_ids


def get_canonical_segments(podcast_id: str) -> List[Dict]:
    with get_db_ro() as conn:
        rows = conn.execute("""
            SELECT cs.*, e.url as ref_episode_url, e.title as ref_episode_title
            FROM canonical_segments cs
            JOIN episodes e ON e.id = cs.ref_episode_id
            WHERE cs.podcast_id = ?
            ORDER BY cs.episode_count DESC, cs.ref_start
        """, (podcast_id,)).fetchall()
        return [dict(r) for r in rows]


def get_canonical_segments_with_occurrences(podcast_id: str) -> List[Dict]:
    """Returns canonical segments with the full list of episode occurrences.

    Each occurrence gains an 'effective_type' field: the position-corrected type
    for that specific episode (same-jingle podcasts reuse one canonical for both
    intro and outro positions across different episodes).
    """
    segments = get_canonical_segments(podcast_id)
    result = []
    for seg in segments:
        occurrences = get_occurrences_for_canonical(seg['id'])
        for occ in occurrences:
            occ['effective_type'] = _reclassify_segment_type(seg['segment_type'], occ['local_start'])
        result.append({**seg, 'occurrences': occurrences})
    return result


def get_occurrences_for_canonical(canonical_segment_id: int) -> List[Dict]:
    with get_db_ro() as conn:
        rows = conn.execute("""
            SELECT es.local_start, es.local_end, es.confidence,
                   e.url as episode_url, e.title as episode_title,
                   af.local_path
            FROM episode_segments es
            JOIN episodes e ON e.id = es.episode_id
            LEFT JOIN audio_files af ON af.episode_id = e.id
            WHERE es.canonical_segment_id = ?
            ORDER BY e.published DESC
        """, (canonical_segment_id,)).fetchall()
        return [dict(r) for r in rows]


def get_episode_segments_for_id(episode_id: int) -> List[Dict]:
    with get_db_ro() as conn:
        rows = conn.execute("""
            SELECT es.*, cs.segment_type, cs.episode_count, cs.ref_start
            FROM episode_segments es
            JOIN canonical_segments cs ON cs.id = es.canonical_segment_id
            WHERE es.episode_id = ?
            ORDER BY es.local_start
        """, (episode_id,)).fetchall()
        return [dict(r) for r in rows]


def get_segments_for_url(episode_url: str) -> List[Dict]:
    """
    Return merged segment list for the player view.

    Sources (in priority order):
      1. canonical episode_segments  (fingerprint, multi-episode, promoted)
      2. raw_matches not covered by canonical  (fingerprint, below promotion threshold)
      3. transcript_segments         (transcription-based or manual)

    All returned dicts include a 'source' key: 'fingerprint' or 'transcript'.
    Transcript segments that overlap an existing fingerprint segment by > 50 %
    are suppressed to avoid duplicates.
    """
    ep = get_episode_by_url(episode_url)
    if not ep:
        return []

    fingerprint_segs: List[Dict] = []

    canonical = get_episode_segments_for_id(ep['id'])
    # An episode can have multiple episode_segments rows for the same time window when it
    # has appeared as both a reference episode (ep_a) and a matched episode (ep_b) in
    # separate promotion passes — each pass creates its own canonical_segment row.
    # Deduplicate by overlap, but pick the *best* boundary among overlapping candidates:
    #   • outro → prefer the latest local_start (conservative: don't cut into content)
    #   • intro → prefer the earliest local_start (catch the full intro)
    #   • ad / tie-break → prefer the higher episode_count (stronger evidence)
    fingerprint_segs: List[Dict] = []
    for s in canonical:
        seg_type = _reclassify_segment_type(s['segment_type'], s['local_start'])
        seg = {
            'start':               s['local_start'],
            'end':                 s['local_end'],
            'start_b':             None,
            'end_b':               None,
            'type':                seg_type,
            'confidence':          s['confidence'],
            'episode_count':       s['episode_count'],
            'source':              'fingerprint',
            'canonical_ref_start': s.get('ref_start'),
        }
        idx = _find_overlapping_idx(seg['start'], seg['end'], fingerprint_segs)
        if idx is None:
            fingerprint_segs.append(seg)
        else:
            existing = fingerprint_segs[idx]
            replace = False
            if seg['type'] == 'outro':
                seg_is_jingle = (seg.get('canonical_ref_start') or float('inf')) < _OUTRO_MIN_START_SEC
                existing_is_jingle = (existing.get('canonical_ref_start') or float('inf')) < _OUTRO_MIN_START_SEC
                if seg_is_jingle and not existing_is_jingle:
                    replace = True   # same-jingle canonical is a more precise outro boundary
                elif seg_is_jingle and existing_is_jingle and seg['start'] < existing['start']:
                    replace = True   # both jingle: prefer earlier start to catch full jingle onset
                elif not seg_is_jingle and not existing_is_jingle and seg['start'] > existing['start']:
                    replace = True   # both pure-outro: prefer later start (conservative)
                # not seg_is_jingle and existing_is_jingle → keep existing
            elif seg['type'] == 'intro' and seg['start'] < existing['start']:
                replace = True   # earlier start catches more of the intro
            elif seg['type'] == 'ad' and seg.get('episode_count', 1) > existing.get('episode_count', 1) * 1.5:
                replace = True   # for ads: prefer significantly stronger evidence
            if replace:
                fingerprint_segs[idx] = seg

    # Also include raw_matches that are NOT already covered by a canonical segment.
    # Previously these were silently dropped once any canonical segment existed, hiding
    # ads that were detected but never promoted (appeared in < min_consensus episodes).
    raw = get_raw_matches_for_episode(ep['id'])
    for r in raw:
        if r['ep_a_id'] == ep['id']:
            seg_start, seg_end = r['start_a'], r['end_a']
            start_b, end_b     = r['start_b'], r['end_b']
        else:
            seg_start, seg_end = r['start_b'], r['end_b']
            start_b, end_b     = r['start_a'], r['end_a']
        raw_type = _reclassify_segment_type(r['segment_type'] or 'ad', seg_start)
        if not _covered_by_any(seg_start, seg_end, fingerprint_segs):
            fingerprint_segs.append({
                'start':         seg_start,
                'end':           seg_end,
                'start_b':       start_b,
                'end_b':         end_b,
                'type':          raw_type,
                'confidence':    r['confidence'],
                'episode_count': 1,
                'source':        'fingerprint',
            })

    transcript_segs = get_transcript_segments(ep['id'])
    # Suppress transcript segments that heavily overlap a fingerprint segment
    non_duplicate = [
        t for t in transcript_segs
        if not _overlaps_any(t['start'], t['end'], fingerprint_segs)
    ]
    transcript_out = [{
        'start':         t['start'],
        'end':           t['end'],
        'start_b':       None,
        'end_b':         None,
        'type':          t['segment_type'],
        'confidence':    t['confidence'],
        'episode_count': 1,
        'source':        'transcript',
    } for t in non_duplicate]

    result = fingerprint_segs + transcript_out
    result.sort(key=lambda s: s['start'])

    # When same-jingle outro canonicals exist (ref_start within the intro window),
    # discard pure-outro canonicals before gap-fill.  Pure-outro fingerprints capture
    # the full closing section including pre-jingle host speech — that speech should
    # not be skipped; only the jingle itself should be.
    _same_jingle_outros = [
        s for s in result
        if s['type'] == 'outro'
        and (s.get('canonical_ref_start') or float('inf')) < _OUTRO_MIN_START_SEC
    ]
    if _same_jingle_outros:
        result = [
            s for s in result
            if s['type'] != 'outro'
            or (s.get('canonical_ref_start') or float('inf')) < _OUTRO_MIN_START_SEC
        ]

    # Gap-fill: merge consecutive same-type segments whose gap is small enough to
    # belong to one ad break (e.g. jingle + host live-read + jingle).  A 120-second
    # ceiling covers any realistic single-advertiser slot while preventing unrelated
    # breaks from being joined.
    GAP_FILL_SEC = 120.0
    filled: List[Dict] = []
    for seg in result:
        if (filled
                and filled[-1]['type'] == seg['type']
                and seg['start'] - filled[-1]['end'] <= GAP_FILL_SEC):
            prev = filled[-1]
            prev['end']        = max(prev['end'], seg['end'])
            prev['confidence'] = max(prev['confidence'] or 0, seg['confidence'] or 0) or None
            # Keep the higher episode_count as a quality signal
            prev['episode_count'] = max(prev.get('episode_count') or 1,
                                        seg.get('episode_count') or 1)
        else:
            filled.append(dict(seg))
    return _snap_boundaries(filled, episode_duration=ep.get('duration_seconds'))


# ---------------------------------------------------------------------------
# Catalog inventory
# ---------------------------------------------------------------------------

def get_catalog_stats(podcast_id: str = None) -> Dict:
    """Aggregate stats for the catalog page."""
    with get_db_ro() as conn:
        where = "WHERE podcast_id = ?" if podcast_id else ""
        params = (podcast_id,) if podcast_id else ()
        stats = conn.execute(f"""
            SELECT
                COUNT(*) as total_segments,
                SUM(CASE WHEN segment_type='intro' THEN 1 ELSE 0 END) as intro_count,
                SUM(CASE WHEN segment_type='outro' THEN 1 ELSE 0 END) as outro_count,
                SUM(CASE WHEN segment_type='ad'    THEN 1 ELSE 0 END) as ad_count,
                SUM(episode_count) as total_occurrences,
                SUM((ref_end - ref_start) * episode_count) as total_duplicate_seconds,
                AVG(avg_confidence) as overall_avg_confidence
            FROM canonical_segments {where}
        """, params).fetchone()

        ep_where = "WHERE cs.podcast_id = ?" if podcast_id else ""
        ep_params = (podcast_id,) if podcast_id else ()
        ep_row = conn.execute(f"""
            SELECT COUNT(DISTINCT es.episode_id) as cnt
            FROM episode_segments es
            JOIN canonical_segments cs ON cs.id = es.canonical_segment_id
            {ep_where}
        """, ep_params).fetchone()

        # Processing time: fingerprinting + matching cpu_time_ms for episodes in this podcast
        fp_join = "JOIN episodes e ON e.id = fl.episode_id WHERE e.podcast_id = ?" if podcast_id else ""
        fp_params = (podcast_id,) if podcast_id else ()
        fp_row = conn.execute(f"""
            SELECT
                COUNT(DISTINCT fl.episode_id) as fingerprinted_episodes,
                SUM(fl.cpu_time_ms) as total_fp_ms
            FROM fingerprint_log fl
            {fp_join}
        """, fp_params).fetchone()

        ml_join = "JOIN episodes e ON e.id = ml.ep_a_id WHERE e.podcast_id = ?" if podcast_id else ""
        ml_params = (podcast_id,) if podcast_id else ()
        ml_row = conn.execute(f"""
            SELECT SUM(ml.cpu_time_ms) as total_match_ms
            FROM match_log ml
            {ml_join}
        """, ml_params).fetchone()

        dl_join = "JOIN episodes e ON e.id = af.episode_id WHERE e.podcast_id = ?" if podcast_id else ""
        dl_params = (podcast_id,) if podcast_id else ()
        dl_row = conn.execute(f"""
            SELECT SUM(af.download_ms) as total_dl_ms
            FROM audio_files af
            {dl_join}
        """, dl_params).fetchone()

        fp_ms    = fp_row['total_fp_ms']    or 0 if fp_row else 0
        match_ms = ml_row['total_match_ms'] or 0 if ml_row else 0
        dl_ms    = dl_row['total_dl_ms']    or 0 if dl_row else 0
        fp_eps   = fp_row['fingerprinted_episodes'] or 0 if fp_row else 0

        feed_meta_row = conn.execute(
            "SELECT feed_episode_count FROM podcast_feed_meta WHERE podcast_id = ?",
            (podcast_id,)
        ).fetchone() if podcast_id else None

        ep_analyzed_where = "WHERE podcast_id = ?" if podcast_id else ""
        ep_analyzed_row = conn.execute(
            f"SELECT COUNT(*) as cnt FROM episodes {ep_analyzed_where}",
            params
        ).fetchone()

        return {
            **dict(stats),
            'total_episodes': ep_analyzed_row['cnt'] if ep_analyzed_row else 0,
            'feed_episode_count': feed_meta_row['feed_episode_count'] if feed_meta_row else None,
            'episodes_with_duplicates': ep_row['cnt'] if ep_row else 0,
            'fingerprinted_episodes': fp_eps,
            'total_download_ms': dl_ms,
            'total_processing_ms': fp_ms + match_ms,
            'total_time_ms': dl_ms + fp_ms + match_ms,
            'avg_processing_ms_per_episode': (dl_ms + fp_ms + match_ms) / fp_eps if fp_eps else 0,
        }


def set_podcast_feed_episode_count(podcast_id: str, count: int):
    """Store the total episode count from the RSS feed for a podcast."""
    with get_db() as conn:
        conn.execute("""
            INSERT INTO podcast_feed_meta (podcast_id, feed_episode_count, updated_at)
            VALUES (?, ?, datetime('now'))
            ON CONFLICT(podcast_id) DO UPDATE SET
                feed_episode_count = excluded.feed_episode_count,
                updated_at         = excluded.updated_at
        """, (podcast_id, count))


def stamp_algorithm_version(podcast_id: str, version: str):
    """Record the algorithm version used for the most recent analysis of a podcast."""
    with get_db() as conn:
        conn.execute("""
            INSERT INTO podcast_feed_meta (podcast_id, algorithm_version, updated_at)
            VALUES (?, ?, datetime('now'))
            ON CONFLICT(podcast_id) DO UPDATE SET
                algorithm_version = excluded.algorithm_version,
                updated_at        = excluded.updated_at
        """, (podcast_id, version))


def get_stale_podcasts(current_version: str) -> List[str]:
    """Return podcast_ids whose stored algorithm_version differs from current_version."""
    with get_db() as conn:
        rows = conn.execute("""
            SELECT pfm.podcast_id
            FROM podcast_feed_meta pfm
            JOIN canonical_segments cs ON cs.podcast_id = pfm.podcast_id
            WHERE pfm.algorithm_version IS NULL OR pfm.algorithm_version != ?
            GROUP BY pfm.podcast_id
        """, (current_version,)).fetchall()
        return [r['podcast_id'] for r in rows]


def search_podcast_by_name(name: str) -> List[Dict]:
    """Full-text search for podcasts by title, returns RSS URL candidates."""
    with get_db() as conn:
        rows = conn.execute("""
            SELECT DISTINCT podcast_id, podcast_title,
                   COUNT(*) OVER (PARTITION BY podcast_id) as episode_count
            FROM episodes
            WHERE podcast_title LIKE ? AND podcast_id IS NOT NULL
            ORDER BY episode_count DESC
            LIMIT 20
        """, (f"%{name}%",)).fetchall()
        return [dict(r) for r in rows]


def get_all_podcasts() -> List[Dict]:
    """All distinct podcast_ids that have canonical segments."""
    with get_db_ro() as conn:
        rows = conn.execute("""
            SELECT
                cs.podcast_id,
                COUNT(*) as segment_count,
                SUM(cs.episode_count) as total_occurrences,
                MAX(cs.last_seen_at) as last_analyzed,
                (SELECT COUNT(*) FROM episodes e WHERE e.podcast_id = cs.podcast_id) as analyzed_episodes,
                pfm.feed_episode_count,
                (SELECT podcast_title FROM episodes WHERE podcast_id = cs.podcast_id
                 AND podcast_title IS NOT NULL AND podcast_title != '' LIMIT 1) as podcast_title
            FROM canonical_segments cs
            LEFT JOIN podcast_feed_meta pfm ON pfm.podcast_id = cs.podcast_id
            GROUP BY cs.podcast_id
            ORDER BY last_analyzed DESC
        """).fetchall()
        return [dict(r) for r in rows]


def delete_canonical_segment(segment_id: int) -> bool:
    """Delete a canonical segment and its episode_segments rows."""
    with get_db() as conn:
        conn.execute("DELETE FROM episode_segments WHERE canonical_segment_id = ?", (segment_id,))
        result = conn.execute("DELETE FROM canonical_segments WHERE id = ?", (segment_id,))
        return result.rowcount > 0


def delete_canonical_segments_bulk(podcast_id: str, segment_type: str = None) -> int:
    """Delete all canonical segments for a podcast, optionally filtered by type. Returns count."""
    with get_db() as conn:
        if segment_type:
            seg_ids = [r['id'] for r in conn.execute(
                "SELECT id FROM canonical_segments WHERE podcast_id = ? AND segment_type = ?",
                (podcast_id, segment_type)
            ).fetchall()]
        else:
            seg_ids = [r['id'] for r in conn.execute(
                "SELECT id FROM canonical_segments WHERE podcast_id = ?",
                (podcast_id,)
            ).fetchall()]
        if not seg_ids:
            return 0
        placeholders = ','.join('?' * len(seg_ids))
        conn.execute(f"DELETE FROM episode_segments WHERE canonical_segment_id IN ({placeholders})", seg_ids)
        result = conn.execute(f"DELETE FROM canonical_segments WHERE id IN ({placeholders})", seg_ids)
        return result.rowcount


def update_canonical_segment_type(segment_id: int, segment_type: str) -> bool:
    """Update the segment type (intro/outro/ad) for a canonical segment."""
    with get_db() as conn:
        result = conn.execute(
            "UPDATE canonical_segments SET segment_type = ? WHERE id = ?",
            (segment_type, segment_id)
        )
        return result.rowcount > 0


def fix_mislabeled_canonical_types(podcast_id: str = None) -> int:
    """Re-derive each canonical's segment_type from a majority vote over its
    occurrences' *effective* types.

    The stored type reflects the reference episode's position when the canonical
    was first promoted.  For same-jingle podcasts the same audio clip appears as
    both intro (near t=0) and outro (near end) across different episodes.  When
    more occurrences are in the opposite position the stored label is wrong.

    This function corrects the canonical DB value by applying
    _reclassify_segment_type to every episode_segments row and voting.
    Returns the number of canonicals updated.
    """
    with get_db() as conn:
        where = "WHERE podcast_id = ?" if podcast_id else ""
        params = (podcast_id,) if podcast_id else ()
        canonicals = conn.execute(
            f"SELECT id, segment_type FROM canonical_segments {where}", params
        ).fetchall()

    updated = 0
    for cs in canonicals:
        with get_db() as conn:
            occs = conn.execute(
                "SELECT local_start FROM episode_segments WHERE canonical_segment_id = ?",
                (cs['id'],)
            ).fetchall()
        if not occs:
            continue
        votes = Counter(
            _reclassify_segment_type(cs['segment_type'], o['local_start'])
            for o in occs
        )
        majority = votes.most_common(1)[0][0]
        if majority != cs['segment_type']:
            with get_db() as conn:
                conn.execute(
                    "UPDATE canonical_segments SET segment_type = ? WHERE id = ?",
                    (majority, cs['id'])
                )
            updated += 1
    return updated


def _overlaps_any(start: float, end: float, segs: List[Dict],
                  threshold: float = 0.5) -> bool:
    """Return True if [start, end] overlaps any segment in *segs* by > threshold."""
    duration = end - start
    if duration <= 0:
        return False
    for s in segs:
        overlap = min(end, s['end']) - max(start, s['start'])
        if overlap / duration > threshold:
            return True
    return False


def _covered_by_any(start: float, end: float, segs: List[Dict],
                    threshold: float = 0.5) -> bool:
    """Return True if [start, end] is effectively covered by any segment in *segs*.

    Checks coverage from both directions:
    - If > threshold of [start, end] is overlapped by an existing segment → covered
    - If an existing segment is mostly contained within [start, end] → also covered
      (the incoming window is a wider superset; the existing canonical is authoritative)
    """
    duration = end - start
    if duration <= 0:
        return False
    for s in segs:
        overlap = min(end, s['end']) - max(start, s['start'])
        if overlap <= 0:
            continue
        if overlap / duration > threshold:
            return True
        s_dur = s['end'] - s['start']
        if s_dur > 0 and overlap / s_dur > threshold:
            return True
    return False


def _find_overlapping_idx(start: float, end: float, segs: List[Dict],
                          threshold: float = 0.5) -> Optional[int]:
    """Return the index of the first segment in *segs* that overlaps [start, end]
    by strictly more than *threshold* of [start, end]'s own duration, or None."""
    duration = end - start
    if duration <= 0:
        return None
    for i, s in enumerate(segs):
        overlap = min(end, s['end']) - max(start, s['start'])
        if overlap / duration > threshold:
            return i
    return None


# Boundary thresholds for position-based type reclassification.
# Both are conservative: intro must start early, outro must start late.
_INTRO_MAX_START_SEC: float = 180.0   # intro cannot start after 3 minutes
_OUTRO_MIN_START_SEC: float = 60.0    # outro cannot start before 1 minute


INTRO_SNAP_TO_START_SEC: float = 5.0   # fingerprint lag can be up to ~5s
OUTRO_SNAP_TO_END_SEC: float = 120.0  # snap outro end to episode end if within 2 min


def _snap_boundaries(segs: List[Dict],
                     episode_duration: float = None) -> List[Dict]:
    """Extend intro/outro segments to cover small gaps at episode boundaries.

    Fingerprinting under-reports the true intro start by 1–5s (STFT warm-up,
    hash-pair density ramp). If the intro starts within INTRO_SNAP_TO_START_SEC
    of t=0, snap to 0.0 so the skip begins from the very first frame.

    Outro end-snap only fires when episode_duration is known; the RSS feed
    often omits it, so the feature degrades gracefully to a no-op.
    """
    result = []
    for seg in segs:
        seg = dict(seg)
        if seg['type'] == 'intro' and 0.0 < seg['start'] <= INTRO_SNAP_TO_START_SEC:
            seg['start'] = 0.0
        if (seg['type'] == 'outro'
                and episode_duration is not None
                and 0.0 < episode_duration - seg['end'] <= OUTRO_SNAP_TO_END_SEC):
            seg['end'] = episode_duration
        result.append(seg)
    return result


def _reclassify_segment_type(segment_type: str, local_start: float) -> str:
    """Correct type labels that are inconsistent with the segment's actual position.

    Same-jingle podcasts cause canonical types to reflect the *reference* episode's
    position, not the target's.  Two cases:
    - intro canonical at local_start > 180s → the jingle is the outro in this episode
    - outro canonical at local_start < 60s  → the jingle is the intro in this episode
    """
    if segment_type == 'intro' and local_start > _INTRO_MAX_START_SEC:
        return 'outro'
    if segment_type == 'outro' and local_start < _OUTRO_MIN_START_SEC:
        return 'intro'
    return segment_type


# ---------------------------------------------------------------------------
# Transcript segments
# ---------------------------------------------------------------------------

def store_transcript_segments(episode_id: int, segments: List[Dict]):
    """Bulk-insert transcript-detected segments, replacing any prior results (manual segments are preserved).

    When segments is empty a sentinel row (source='transcript_done') is inserted so
    has_transcript_segments() returns True and avoids re-running expensive Whisper
    transcription on the same episode.  get_transcript_segments() filters this row out.
    """
    with get_db() as conn:
        conn.execute("DELETE FROM transcript_segments WHERE episode_id = ? AND source != 'manual'", (episode_id,))
        if segments:
            conn.executemany(
                """
                INSERT INTO transcript_segments
                    (episode_id, start, end, segment_type, confidence, excerpt, source)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                [
                    (
                        episode_id,
                        seg["start"],
                        seg["end"],
                        seg["type"],
                        seg.get("confidence"),
                        seg.get("text", "")[:200],
                        seg.get("source", "transcript"),
                    )
                    for seg in segments
                ],
            )
        else:
            conn.execute(
                "INSERT INTO transcript_segments (episode_id, start, end, segment_type, source) VALUES (?, 0, 0, 'content', 'transcript_done')",
                (episode_id,),
            )


def get_transcript_segments(episode_id: int) -> List[Dict]:
    with get_db() as conn:
        rows = conn.execute(
            """
            SELECT start, end, segment_type, confidence, excerpt, source
            FROM transcript_segments
            WHERE episode_id = ? AND source != 'transcript_done'
            ORDER BY start
            """,
            (episode_id,),
        ).fetchall()
        return [dict(r) for r in rows]


def has_transcript_segments(episode_id: int) -> bool:
    with get_db_ro() as conn:
        row = conn.execute(
            "SELECT 1 FROM transcript_segments WHERE episode_id = ? AND source != 'manual' LIMIT 1",
            (episode_id,),
        ).fetchone()
        return row is not None


def store_manual_segments(episode_id: int, segments: List[Dict]):
    """Replace manually-marked segments for an episode."""
    with get_db() as conn:
        conn.execute("DELETE FROM transcript_segments WHERE episode_id = ? AND source = 'manual'", (episode_id,))
        conn.executemany(
            """
            INSERT INTO transcript_segments
                (episode_id, start, end, segment_type, confidence, excerpt, source)
            VALUES (?, ?, ?, ?, 1.0, '', 'manual')
            """,
            [(episode_id, seg["start"], seg["end"], seg["type"]) for seg in segments],
        )


# ---------------------------------------------------------------------------
# Debug logs
# ---------------------------------------------------------------------------

def log_fingerprint(episode_id: int, hash_count: int, duration_sec: float, cpu_time_ms: int):
    with get_db() as conn:
        conn.execute("""
            INSERT INTO fingerprint_log (episode_id, hash_count, duration_sec, cpu_time_ms)
            VALUES (?, ?, ?, ?)
        """, (episode_id, hash_count, duration_sec, cpu_time_ms))


def log_match(ep_a_id: int, ep_b_id: int, offset_candidates: list,
              raw_count: int, final_count: int, cpu_time_ms: int):
    with get_db() as conn:
        conn.execute("""
            INSERT INTO match_log
                (ep_a_id, ep_b_id, raw_offset_candidates,
                 raw_segment_count, final_segment_count, cpu_time_ms)
            VALUES (?, ?, ?, ?, ?, ?)
        """, (ep_a_id, ep_b_id, json.dumps(offset_candidates),
              raw_count, final_count, cpu_time_ms))


def get_fingerprint_log(episode_id: int = None) -> List[Dict]:
    with get_db() as conn:
        if episode_id:
            rows = conn.execute("""
                SELECT fl.*, e.url FROM fingerprint_log fl
                JOIN episodes e ON e.id = fl.episode_id
                WHERE fl.episode_id = ? ORDER BY fl.fingerprinted_at DESC
            """, (episode_id,)).fetchall()
        else:
            rows = conn.execute("""
                SELECT fl.*, e.url FROM fingerprint_log fl
                JOIN episodes e ON e.id = fl.episode_id
                ORDER BY fl.fingerprinted_at DESC LIMIT 200
            """).fetchall()
        return [dict(r) for r in rows]
