# Community Impact / "Trim Nation" — Backend Deploy Bundle & Checklist

Covers the two backend deployment steps for the Trim Nation screen:

1. **Schema + endpoint** on the API host (6 RDS tables, `/community/impact` route,
   `apply_community_events` ingestion hook).
2. **`avg_playback_speed`** optional field on `ClientEventsRequest` (the speed
   comparison row).

> **STATUS — DONE ON LIVE (2026-06-13).** Both steps are deployed to
> `api.trimplayer.com` and verified end-to-end (see *Deploy record* below). This
> bundle exists to (a) **reconcile the canonical TrimBrain repo** so the next
> `deploy.sh --redeploy` doesn't revert the feature, (b) optionally fan the
> module change out to the worker fleet, and (c) re-deploy or roll back.

---

## Bundle contents

| File | What it is |
|------|------------|
| `community_ddl.sql` | The 6 community tables + index + scalar seed. Idempotent (`IF NOT EXISTS` / `ON CONFLICT DO NOTHING`). |
| `apply_ddl.py` | Applies `community_ddl.sql` to RDS via psycopg using `TRIMBRAIN_DSN` from `.env`. Verifies all 6 tables. |
| `verify.py` | Imports the live `app.database`, exercises `get_community_impact()` + a no-op write against real RDS. Run before/after restart. |
| `rollback.sh` | Restores the pre-deploy file backups + restarts; `--drop-tables` also drops the schema. |
| `patches/api.community.diff` | Exact change to `app/api.py` (3-tuple unpack + `apply_community_events` hook + GET route). |
| `patches/database.community.diff` | Exact change to `app/database.py` (`store_client_events_batch` → 3-tuple; appended community block). |
| `patches/models.community.diff` | Exact change to `app/models.py` (`avg_playback_speed` field). |

The patches are real unified diffs (`backup → live`) captured from the server, so
they apply cleanly with `git apply` / `patch -p0` against the matching base.

---

## ⚠️ Critical context: why this is a *splice*, not a file copy

The `backend-migration/` snapshot is **incomplete and diverged** from live:

- The snapshot's `api_pg.py` does an **inline INSERT loop** in `post_client_events`.
  The **live** server refactored that into `db.store_client_events_batch()`.
- So the deployed integration is: **`store_client_events_batch` returns a 3-tuple
  `(accepted, duplicates, accepted_events)`** and `post_client_events` calls
  `apply_community_events` best-effort afterward — NOT what the snapshot shows.
- **Do NOT `scp database_pg.py`/`api_pg.py` from the snapshot over the live files**
  (`deploy.sh --redeploy` would also ship the SQLite `database.py` — see
  `infra_trimplayer_server` memory). Apply the **patches** instead.

---

## Step 1 — Schema + endpoint (API host: `api.trimplayer.com`)

> Already done on live. Re-runnable; idempotent.

```bash
# 0. SSH in (IP changes — resolve fresh)
IP=$(nslookup api.trimplayer.com | awk '/^Address: /{print $2}' | tail -1)
ssh -i ~/.ssh/trimplayer.pem ubuntu@$IP

# 1. Backup live files (timestamped)
cd /opt/trimbrain/app && TS=$(date +%Y%m%d-%H%M%S)
for f in api.py database.py database_pg.py models.py; do sudo cp -p $f $f.bak.community.$TS; done

# 2. Apply DDL to RDS (trimbrain role has CREATE on public)
#    copy community_ddl.sql + apply_ddl.py to the box first
sudo /opt/trimbrain/venv/bin/python3 apply_ddl.py community_ddl.sql

# 3. Apply the code patches (against the live files)
sudo patch -p0 /opt/trimbrain/app/api.py      < patches/api.community.diff
sudo patch -p0 /opt/trimbrain/app/database.py < patches/database.community.diff
# database_pg.py is the source-of-truth twin: keep them identical
sudo cp /opt/trimbrain/app/database.py /opt/trimbrain/app/database_pg.py

# 4. Byte-compile everything (catches syntax errors before restart)
for f in models.py database.py database_pg.py api.py; do
  sudo /opt/trimbrain/venv/bin/python3 -m py_compile /opt/trimbrain/app/$f && echo "OK $f"; done

# 5. Validate against real RDS WITHOUT restarting yet
sudo /opt/trimbrain/venv/bin/python3 verify.py   # expect "ALL CHECKS PASSED"

# 6. Restart in a 0-running-jobs window (init_db requeues an orphaned running job,
#    but pick a clean moment anyway). ~25s cold start may 502 transiently.
curl -s -H "X-Api-Key: <key>" http://127.0.0.1:8000/api/v1/jobs | grep -c '"status": "running"'
sudo systemctl restart trimbrain
```

## Step 2 — `avg_playback_speed` field

Included in `patches/models.community.diff` (Step 1.3 covers `api.py`/`database.py`;
also patch `models.py`):

```bash
sudo patch -p0 /opt/trimbrain/app/models.py < patches/models.community.diff
```

Adds `avg_playback_speed: Optional[float] = None` to `ClientEventsRequest`. Until a
client sends it the community speed average stays `null`; everything else works.

---

## Verification (post-restart)

```bash
KEY=<api key from infra_trimplayer_server memory>
# public endpoint the app hits — expect HTTP 200 + full payload (4 windows)
curl -s -w '\nHTTP %{http_code}\n' -H "X-Api-Key: $KEY" \
  https://api.trimplayer.com/api/v1/community/impact

# end-to-end write (USE A THROWAWAY client_id, then purge — see Deploy record)
curl -s -X POST -H "X-Api-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"client_id":"__smoke_DELETE_ME__","avg_playback_speed":1.55,
       "events":[{"client_event_id":1,"skip_type":"ad","duration_ms":42000,
                  "client_ts":'"$(($(date +%s)*1000))"'}]}' \
  https://api.trimplayer.com/api/v1/events
# then DELETE the test client rows and reset community_scalars/_impact/_impact_daily.
```

---

## Step 3 (optional) — Reconcile the canonical TrimBrain repo

The live host is edited in-place and is **not** a git checkout, so the deploy
re-introduced repo↔server drift (`infra_trimbrain_repo_server_drift`). To close it,
apply the **same surgical edits** (the patches here) to the real TrimBrain repo's
`app/api.py`, `app/database.py`, `app/models.py`, and add the DDL to its
`schema_pg.sql`. **Do not** port the snapshot's `api_pg.py` inline-loop version.

## Step 4 (optional) — Worker-fleet fan-out

Only the API host serves `/community/impact` and runs the ingestion hook, so the
EC2 workers (172.31.x) don't need the change to *function*. For module
consistency, fan `database.py` out with `fleet_deploy.sh` / `verify_deploy.sh`
(`project_trimbrain_phase2_demote_readflip`). Harmless if skipped — workers never
call the community functions.

---

## Rollback

```bash
sudo bash rollback.sh                # restore code backups + restart (keep tables)
sudo bash rollback.sh --drop-tables  # also drop the 6 community tables
```

---

## Deploy record (2026-06-13, live)

- RDS: 6 tables created as `trimbrain` (CREATE on public); `community_scalars`
  seeded (`contributors=0, speed_n=0`).
- Code: surgical splice into live `api.py` / `database.py` (+ `database_pg.py` twin)
  / `models.py`. Backups: `*.bak.community.20260613-154931`.
- Restart: clean startup in a 0-running-jobs window; "Application startup complete".
- Verified: public `GET /community/impact` → 200 full payload (4 windows);
  `POST /events` with `avg_playback_speed:1.55` moved totals/contributor/speed
  correctly; **test client then purged, aggregates reset to pristine 0**.
