# Backend SQLite → PostgreSQL migration

Working area for the TrimBrain DB port. The backend code lives on the Lightsail
box under `/opt/trimbrain/`, not in this repo — these files exist here so
schema and migration scripts get version-controlled before they're applied.

## Status

- **Phase 1 — Schema port: drafted.** See `schema_pg.sql`.
- Phases 2–6 (driver port, pgloader rehearsal, cutover, cleanup) are not started.

## Files

| File | Purpose |
|---|---|
| `schema_pg.sql` | Hand-written PostgreSQL DDL. Idempotent (`CREATE … IF NOT EXISTS`). Run against a fresh pg database. |

## Source SQLite snapshot

Current production DB (as of 2026-05-12, post-cleanup):
- Path: `/opt/trimbrain/trimbrain.db`
- Size: **70 MB** (was 2.87 GB before the 2026-05-12 fingerprint cleanup)
- Tables: 12 (incl. `client_events`)
- Largest table: `fingerprints` (~1M rows, ~120 MB raw — partitioning not needed)
- New analyze runs auto-delete fingerprints for no-segment episodes
  (`cleanup_episode_fingerprints_if_no_segments` in `database.py`), so this
  size should stay roughly flat as catalog grows.

## Open decisions before Phase 2

1. **Hosting**: managed (Aiven / Supabase / RDS) vs. self-host on a larger Lightsail box. Default: managed.
2. **Driver**: psycopg 3 with `psycopg_pool`, no ORM. Confirmed in Phase 1.
3. **Fingerprints partitioning**: not needed at current scale (~1M rows). See comment block in `schema_pg.sql`. Revisit only if EXPLAIN ANALYZE on the hash lookup shows a problem.
4. **FK cascade behaviour**: schema currently mirrors SQLite (no cascade). Decide whether to add `ON DELETE CASCADE` once we audit existing orphans.

## Pre-migration audit checklist (Phase 2 prep)

Run these on a **fresh snapshot** of `trimbrain.db` (copy the file when no
worker is running so the WAL is empty), not against the live file:

```python
import sqlite3, json
c = sqlite3.connect('trimbrain_snapshot.db')

# 1. Exact row counts (sized partition decisions)
for t in ['fingerprints','raw_matches','episode_segments','transcript_segments',
          'fingerprint_log','match_log','audio_files','episodes','jobs',
          'canonical_segments','podcast_feed_meta']:
    print(t, c.execute(f'SELECT COUNT(*) FROM {t}').fetchone()[0])

# 2. FK orphans (pgloader will fail on these; pre-clean or relax FKs)
for table, col, parent in [
    ('audio_files','episode_id','episodes'),
    ('fingerprints','episode_id','episodes'),
    ('canonical_segments','ref_episode_id','episodes'),
    ('episode_segments','episode_id','episodes'),
    ('episode_segments','canonical_segment_id','canonical_segments'),
    ('episode_segments','raw_match_id','raw_matches'),
    ('episode_segments','matched_against_episode_id','episodes'),
    ('raw_matches','ep_a_id','episodes'),
    ('raw_matches','ep_b_id','episodes'),
    ('match_log','ep_a_id','episodes'),
    ('match_log','ep_b_id','episodes'),
    ('fingerprint_log','episode_id','episodes'),
    ('transcript_segments','episode_id','episodes'),
]:
    n = c.execute(
        f'SELECT COUNT(*) FROM {table} '
        f'WHERE {col} IS NOT NULL AND {col} NOT IN (SELECT id FROM {parent})'
    ).fetchone()[0]
    if n: print(f'orphan: {table}.{col} -> {parent}.id : {n}')

# 3. JSON validity in TEXT columns we're moving to JSONB
for table, col in [('jobs','episode_urls'),
                   ('canonical_segments','promoting_match_ids'),
                   ('match_log','raw_offset_candidates')]:
    bad = 0
    for (v,) in c.execute(f'SELECT {col} FROM {table} WHERE {col} IS NOT NULL'):
        try: json.loads(v)
        except: bad += 1
    print(f'{table}.{col} invalid-json: {bad}')
```

## Applying the schema

Against a fresh pg database (no existing data):

```bash
psql "$PG_DSN" -f schema_pg.sql
```

Then run pgloader to copy data from the SQLite snapshot. pgloader config is
Phase 4; not in this directory yet.
