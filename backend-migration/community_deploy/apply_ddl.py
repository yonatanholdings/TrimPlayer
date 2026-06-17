#!/usr/bin/env python3
"""Apply the Community Impact / Trim Nation schema to the TrimBrain RDS.

Idempotent: every statement is CREATE ... IF NOT EXISTS / INSERT ... ON CONFLICT
DO NOTHING, so re-running is a no-op. Reads the DSN from /opt/trimbrain/.env
(TRIMBRAIN_DSN) and runs the whole DDL in a single transaction.

Usage (on the API host, as a user who can read .env):
    sudo /opt/trimbrain/venv/bin/python3 apply_ddl.py [path/to/community_ddl.sql]

The trimbrain app role has CREATE on schema public, so no admin role is needed.
"""
import os
import sys

ENV_PATH = os.environ.get("TRIMBRAIN_ENV", "/opt/trimbrain/.env")
DDL_PATH = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "community_ddl.sql")


def load_dsn(path):
    for line in open(path):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            if k == "TRIMBRAIN_DSN":
                return v.strip().strip('"').strip("'")
    raise SystemExit("TRIMBRAIN_DSN not found in " + path)


def main():
    import psycopg
    dsn = load_dsn(ENV_PATH)
    raw = open(DDL_PATH).read()
    # strip line comments + BEGIN/COMMIT, split into individual statements
    nocomments = "\n".join(
        ln for ln in raw.splitlines() if not ln.strip().startswith("--"))
    stmts = [s.strip() for s in nocomments.split(";")
             if s.strip() and s.strip().upper() not in ("BEGIN", "COMMIT")]
    print(f"Applying {len(stmts)} statements from {DDL_PATH}")
    with psycopg.connect(dsn) as conn:   # commit on clean exit, rollback on error
        for s in stmts:
            conn.execute(s)
            print("  ok:", " ".join(s.split())[:72])
    # verify
    with psycopg.connect(dsn) as conn:
        tables = [r[0] for r in conn.execute(
            "SELECT table_name FROM information_schema.tables "
            "WHERE table_schema='public' AND table_name LIKE 'community%' "
            "ORDER BY 1").fetchall()]
        print("community tables present:", tables)
        seed = conn.execute(
            "SELECT contributors, speed_n FROM community_scalars "
            "WHERE id = TRUE").fetchone()
        print("community_scalars seed row:", seed)
    expected = {"community_active_daily", "community_clients", "community_impact",
                "community_impact_daily", "community_scalars", "community_windows"}
    missing = expected - set(tables)
    if missing:
        raise SystemExit("MISSING TABLES: " + ", ".join(sorted(missing)))
    print("OK: all 6 community tables present.")


if __name__ == "__main__":
    main()
