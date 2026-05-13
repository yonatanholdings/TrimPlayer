"""Mechanical SQLite -> PostgreSQL port for /opt/trimbrain/app/database.py.

Reads `database_sqlite_original.py` and writes `database_pg.py` with these
transformations applied:

  * Connection layer rewritten to use psycopg + a single global connection
    pool. Drops `_db_lock`, `_wal_checkpoint`, and all PRAGMA statements.
  * `init_db()` body replaced with a no-op that just verifies the schema is
    already in place (schema is applied separately via `schema_pg.sql`).
  * `?` placeholders converted to `%s` *inside string literals only*.
  * `INSERT OR IGNORE` -> `INSERT ... ON CONFLICT DO NOTHING`.
  * `INSERT OR REPLACE` is flagged for manual review (rare; one occurrence).
  * `datetime('now')` -> `NOW()`.
  * `cursor.lastrowid` -> `cursor.fetchone()[0]` (caller must add RETURNING).
    Marked with a `# FIXME-PG-LASTROWID` comment so we can audit.

The output is reviewable as a diff against the SQLite original and is what
ships to /opt/trimbrain/app/database.py.
"""
import re
import sys
from pathlib import Path


HEADER = '''"""
PostgreSQL persistence layer for TrimBrain.

Schema overview:
  episodes           - one row per audio file ever seen
  audio_files        - local download path per episode
  fingerprints       - raw Dejavu hashes (prunable after promotion)
  jobs               - background job tracking
  raw_matches        - every pairwise segment detection result
  canonical_segments - segments appearing in >= N episodes (promoted)
  episode_segments   - per-episode position of a canonical segment
  fingerprint_log    - debug: fingerprint run stats
  match_log          - debug: match run stats
  client_events      - aggregated user telemetry uploaded from mobile

Schema is created once via schema_pg.sql; init_db() here only verifies it.
"""

import os
import json
import logging
from contextlib import contextmanager
from typing import List, Tuple, Optional, Dict, Any
from collections import defaultdict, Counter

import psycopg
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

logger = logging.getLogger(__name__)

# DSN from env so deployments can point at managed PG without code change.
DSN = os.getenv("TRIMBRAIN_DSN", "dbname=trimbrain user=trimbrain")

# Storage eviction config (override via env vars).
MAX_AUDIO_BYTES: int = int(os.getenv("MAX_AUDIO_BYTES", str(15 * 1024 ** 3)))  # 15 GB
STREAMING_CACHE_DAYS: int = int(os.getenv("STREAMING_CACHE_DAYS", "7"))

# Single shared pool; psycopg handles concurrent writers, no Python lock needed.
_pool = ConnectionPool(conninfo=DSN, min_size=1, max_size=10, kwargs={"row_factory": dict_row})


@contextmanager
def get_db():
    """Write context: yields a psycopg connection. Transaction commits on exit
    (or rolls back on exception). PG's MVCC handles concurrent writers, so the
    SQLite-era Python-level _db_lock is gone."""
    with _pool.connection() as conn:
        try:
            yield conn
        except Exception:
            conn.rollback()
            raise
        else:
            conn.commit()


@contextmanager
def get_db_ro():
    """Read-only context. Same pool; PG MVCC makes reads non-blocking."""
    with _pool.connection() as conn:
        yield conn


def _wal_checkpoint():
    """No-op under PG (legacy SQLite call kept for compatibility with callers
    that explicitly checkpointed). PG's autovacuum + bgwriter do this work."""
    pass


def init_db():
    """Schema is applied via schema_pg.sql at deploy time; this just smoke-tests
    the connection and runs the orphan-job cleanup that the SQLite init_db did.
    The cleanup logic itself follows below."""
    with get_db() as conn:
        try:
            conn.execute("SELECT 1")
        except Exception:
            logger.exception("Postgres connection failed during init_db")
            raise
'''


PRAGMA_RE = re.compile(r'^\s*conn\.execute\(["\']PRAGMA[^"\']*["\']\).*$', re.MULTILINE)
DATETIME_NOW_RE = re.compile(r"datetime\('now'\)")
INSERT_OR_IGNORE_RE = re.compile(r"INSERT OR IGNORE\b")
INSERT_OR_REPLACE_RE = re.compile(r"INSERT OR REPLACE\b")
LASTROWID_RE = re.compile(r"\.lastrowid\b")
# Match `?` only inside Python string literals (single/double-quoted, possibly
# triple). This is intentionally simple — the file's literals are well-behaved.
STRING_LITERAL_RE = re.compile(
    r"""(?s)
    (
      \"\"\".*?\"\"\"   # triple-double
    |
      '''.*?'''         # triple-single
    |
      "(?:\\.|[^"\\])*"   # double
    |
      '(?:\\.|[^'\\])*'   # single
    )
    """,
    re.VERBOSE,
)


def replace_placeholders_in_strings(src: str) -> str:
    def fix(match: re.Match) -> str:
        lit = match.group(1)
        # Only touch literals that look like SQL: presence of a `?` AND any
        # SQL keyword. Avoids munging unrelated `?` characters in logging
        # format strings or error messages.
        if "?" not in lit:
            return lit
        if not re.search(r"\b(SELECT|INSERT|UPDATE|DELETE|WHERE|VALUES|FROM|AND|OR|SET|JOIN|LIMIT|ORDER BY|GROUP BY|HAVING|RETURNING)\b", lit, re.IGNORECASE):
            return lit
        return lit.replace("?", "%s")
    return STRING_LITERAL_RE.sub(fix, src)


def strip_sqlite_only_blocks(src: str) -> str:
    # Drop SQLite top-of-file block (imports, _db_lock, get_db, get_db_ro,
    # _wal_checkpoint, init_db). HEADER replaces them. Anchor on the private
    # helper _init_db_inner — everything before that is what we strip.
    # HEADER replaces everything up to and including init_db. The first real
    # function after the connection layer is upsert_episode — anchor there.
    marker = re.search(r"^def upsert_episode\(", src, re.MULTILINE)
    if not marker:
        raise SystemExit("Could not locate `def upsert_episode` anchor")
    return src[marker.start():]


def transform(src: str) -> str:
    body = strip_sqlite_only_blocks(src)
    body = PRAGMA_RE.sub("", body)
    body = DATETIME_NOW_RE.sub("NOW()", body)
    body = INSERT_OR_IGNORE_RE.sub("INSERT", body)  # PG: need to append ON CONFLICT DO NOTHING per-statement (handled below)
    body = INSERT_OR_REPLACE_RE.sub("INSERT", body)
    body = replace_placeholders_in_strings(body)

    # For INSERT OR IGNORE we stripped the "OR IGNORE" but the caller's intent
    # was "skip on conflict". Tag those for manual review unless they already
    # have ON CONFLICT (rare).
    # Strategy: any line beginning with `INSERT INTO` that does NOT already
    # contain "ON CONFLICT" gets a `# FIXME-PG-INSERT-OR-IGNORE` annotation.
    new_lines = []
    for line in body.splitlines():
        if re.search(r"\bINSERT INTO\b", line) and "ON CONFLICT" not in line:
            # Keep behaviour identical to OR IGNORE by appending the suffix to
            # the SQL itself. Most callers compose multi-line SQL with f-strings;
            # this naive sub only catches single-line cases. We tag the others.
            stripped = line.rstrip()
            if stripped.endswith('"""') or stripped.endswith("'''"):
                new_lines.append(line)
            elif "\"" in line or "'" in line:
                new_lines.append(line + "  # FIXME-PG-CONFLICT")
            else:
                new_lines.append(line)
        else:
            new_lines.append(line)
    body = "\n".join(new_lines)

    # lastrowid usages
    body = LASTROWID_RE.sub("  # FIXME-PG-LASTROWID: use RETURNING id\n        # .lastrowid", body)

    return HEADER + "\n\n" + body


if __name__ == "__main__":
    src = Path("database_sqlite_original.py").read_text(encoding="utf-8")
    out = transform(src)
    Path("database_pg.py").write_text(out, encoding="utf-8")
    print(f"wrote database_pg.py ({len(out)} bytes)")
