#!/usr/bin/env python3
"""Post-deploy verification for the Community Impact / Trim Nation backend.

Loads the real DSN from .env, imports the live app.database, and exercises the
read path (get_community_impact) + the no-op write path. Run this AFTER the
files are in place but optionally BEFORE restarting the service — it validates
the new code against the real RDS without touching the running uvicorn.

Usage (on the API host):
    sudo /opt/trimbrain/venv/bin/python3 verify.py
"""
import os
import sys

ENV_PATH = os.environ.get("TRIMBRAIN_ENV", "/opt/trimbrain/.env")
APP_ROOT = os.environ.get("TRIMBRAIN_ROOT", "/opt/trimbrain")

# DSN must be in the environment BEFORE importing app.database (read at import).
for line in open(ENV_PATH):
    line = line.strip()
    if line and not line.startswith("#") and "=" in line:
        k, v = line.split("=", 1)
        os.environ.setdefault(k, v.strip().strip('"').strip("'"))
sys.path.insert(0, APP_ROOT)

import app.database as d  # noqa: E402

assert hasattr(d, "get_community_impact"), "get_community_impact missing"
assert hasattr(d, "apply_community_events"), "apply_community_events missing"
assert hasattr(d, "refresh_community_windows"), "refresh_community_windows missing"
print("community functions present: OK")

payload = d.get_community_impact()
need = {"total_ms", "ads_ms", "silence_ms", "speed_ms", "intro_ms", "outro_ms",
        "contributors", "avg_playback_speed", "windows", "as_of"}
missing = need - set(payload)
assert not missing, f"payload missing keys: {missing}"
assert set(payload["windows"]) == {"7d", "30d", "90d", "1y"}, payload["windows"]
print("get_community_impact() OK:",
      "total_ms", payload["total_ms"],
      "| contributors", payload["contributors"],
      "| avg_speed", payload["avg_playback_speed"],
      "| windows", sorted(payload["windows"]))

d.apply_community_events("__verify_noop__", [], None)
print("apply_community_events([]) no-op OK")
print("\nALL CHECKS PASSED")
