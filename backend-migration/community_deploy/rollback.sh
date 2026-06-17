#!/usr/bin/env bash
# Roll back the Community Impact / Trim Nation backend deploy on the API host.
# Restores the pre-deploy file backups and restarts the service. The 6 RDS
# tables are additive and harmless once the code is rolled back (nothing calls
# the community functions), so they are LEFT IN PLACE by default. Pass
# --drop-tables to also remove them.
#
# Run on the API host (api.trimplayer.com):
#   sudo bash rollback.sh [--drop-tables]
set -euo pipefail
APP=/opt/trimbrain/app
TS=20260613-154931   # backup suffix written at deploy time

for f in api.py database.py database_pg.py models.py; do
  bak="$APP/$f.bak.community.$TS"
  if [[ -f "$bak" ]]; then
    cp -p "$bak" "$APP/$f"
    echo "restored $f"
  else
    echo "WARN: no backup for $f ($bak) — skipped"
  fi
done

if [[ "${1:-}" == "--drop-tables" ]]; then
  echo "dropping community tables..."
  /opt/trimbrain/venv/bin/python3 - <<'PY'
import os, psycopg
for line in open('/opt/trimbrain/.env'):
    line=line.strip()
    if line and not line.startswith('#') and '=' in line:
        k,v=line.split('=',1); os.environ.setdefault(k, v.strip().strip('"').strip("'"))
with psycopg.connect(os.environ['TRIMBRAIN_DSN']) as c:
    c.execute("DROP TABLE IF EXISTS community_windows, community_active_daily, "
              "community_impact_daily, community_scalars, community_clients, "
              "community_impact CASCADE")
print("community tables dropped")
PY
fi

systemctl restart trimbrain
echo "service restarted — verify: curl -s -H 'X-Api-Key: <key>' http://127.0.0.1:8000/api/v1/community/impact -o /dev/null -w '%{http_code}\n'"
