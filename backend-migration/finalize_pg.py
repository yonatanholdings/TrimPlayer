"""Finalize database_pg.py: fix remaining ?, lastrowid, FIXME-in-strings, and
inject the cleanup_episode_fingerprints_if_no_segments helper."""
import re
from pathlib import Path

src = Path("database_pg.py").read_text(encoding="utf-8")

# 1. Replace any remaining `?` with `%s`. All ? in this file are SQL params
#    (verified manually); the dynamic placeholder builder is also a SQL param.
src = src.replace("?", "%s")

# 2. Remove the inline FIXME-PG-CONFLICT comment that the transformer
#    accidentally embedded INSIDE a multi-line SQL string (would break the SQL).
src = re.sub(r"\s*#\s*FIXME-PG-CONFLICT\s*", "", src)

# 3. Fix lastrowid usages — find `return cur  # FIXME-PG-LASTROWID...\n        # .lastrowid`
#    and similar patterns. We need to add RETURNING id to the prior INSERT and
#    capture from fetchone(). Locate by FIXME marker and rewrite.
#
#    Manual inspection showed two occurrences:
#      - store_raw_match (returns the new id)
#      - try_promote_clusters (uses canonical_id from the INSERT)
#    Both patterns: conn.execute("INSERT INTO ... VALUES (...)", params)
#                   return cur (or canonical_id = cur)
#    We change the INSERT to use cur = conn.execute(...).fetchone()[0] with
#    RETURNING id appended.
#
# Simpler patch: locate the FIXME line and inject the proper pattern. The
# `cur = conn.execute(...)` form in psycopg returns a cursor; we need to call
# fetchone() on it after RETURNING id is in the SQL. Since database.py never
# saves the cursor before doing arithmetic, we can do the conversion manually
# for the two known sites.

# Site A: store_raw_match — `return cur  # FIXME-PG-LASTROWID...`
# This is right after `cur = conn.execute("INSERT INTO raw_matches ... VALUES (...)", ...)`
# Wait — actually the original used `conn.execute(...).lastrowid`. The transformer
# changed `.lastrowid` to a multi-line FIXME suffix. Let me check the actual context.
# After running the transformer, the lines are:
#    cur = conn.execute("""...""", params)
#    return cur  # FIXME-PG-LASTROWID: use RETURNING id
#               # .lastrowid
# We need to:
#    cur = conn.execute("...... RETURNING id""", params)
#    return cur.fetchone()['id']

# Identify each FIXME-PG-LASTROWID line and walk backward to find the matching
# INSERT, then surgically rewrite.

lines = src.splitlines()
i = 0
out = []
while i < len(lines):
    line = lines[i]
    if "FIXME-PG-LASTROWID" in line:
        # Look backward for the most recent `INSERT INTO` and append `RETURNING id`
        # to its closing `)`/`""")` line.
        j = i - 1
        while j >= 0 and "INSERT INTO" not in lines[j]:
            j -= 1
        if j < 0:
            out.append(line)
            i += 1
            continue
        # Find the line where the INSERT statement's parameters end. The original
        # code uses either `"""...""", params)` or `"...", params)` style. The
        # closing of the SQL string is on the line containing `"""` or just before
        # the args tuple. Insert ` RETURNING id` right before the closing triple-
        # quote (or after the last param token).
        # Simpler: walk forward from j until we find a `"""` or `")` ending the
        # SQL string and insert RETURNING id before that.
        for k in range(j, i):
            stripped = lines[k].rstrip()
            if stripped.endswith('"""'):
                # Insert RETURNING id before the closing """
                lines[k] = stripped[:-3].rstrip() + "\n            RETURNING id\n        \"\"\""
                break
            if stripped.endswith('"),') or stripped.endswith('")'):
                # Single-line SQL string
                # Insert " RETURNING id" before the closing "
                lines[k] = stripped.replace('")', ' RETURNING id")', 1).replace('"),', ' RETURNING id"),', 1) if False else \
                           re.sub(r'"\)', r' RETURNING id")', stripped, count=1)
                break
        # Rewrite the FIXME line: `        return cur  # FIXME...`
        # to:                    `        return cur.fetchone()['id']`
        # Or `        canonical_id = cur  # FIXME...`
        # to: `        canonical_id = cur.fetchone()['id']`
        new_line = re.sub(
            r"(\s*)(return |[a-zA-Z_]+ = )cur(\s|$).*$",
            lambda m: f"{m.group(1)}{m.group(2)}cur.fetchone()['id']",
            line,
        )
        out.append(new_line)
        # Skip the `# .lastrowid` continuation line if present
        if i + 1 < len(lines) and ".lastrowid" in lines[i + 1] and lines[i + 1].lstrip().startswith("#"):
            i += 2
            continue
        i += 1
    else:
        out.append(line)
        i += 1

src = "\n".join(out)

# 4. Inject cleanup_episode_fingerprints_if_no_segments before prune_fingerprints
needle = "def prune_fingerprints("
cleanup_fn = '''def cleanup_episode_fingerprints_if_no_segments(episode_id: int) -> int:
    """Delete every fingerprint for episode_id if no episode_segments exist for it.
    Called at the end of an analyze task: episodes that produced segments keep their
    pruned-to-window fingerprints, while episodes that came up empty drop their
    fingerprints entirely (the dominant source of DB bloat). Returns rows deleted."""
    with get_db() as conn:
        has_segs = conn.execute(
            "SELECT 1 FROM episode_segments WHERE episode_id = %s LIMIT 1",
            (episode_id,)
        ).fetchone()
        if has_segs:
            return 0
        cur = conn.execute("DELETE FROM fingerprints WHERE episode_id = %s", (episode_id,))
        return cur.rowcount


'''
if "cleanup_episode_fingerprints_if_no_segments" not in src and needle in src:
    src = src.replace(needle, cleanup_fn + needle)

# 5. Strip leftover sqlite-specific lines that crept through
src = re.sub(r"^\s*conn\.execute\(\"PRAGMA[^\"]*\"\).*$", "", src, flags=re.MULTILINE)
# remove any executescript call (PG psycopg has no executescript)
src = re.sub(r"conn\.executescript\(", "conn.execute(", src)

Path("database_pg.py").write_text(src, encoding="utf-8")
print(f"finalized database_pg.py ({len(src)} bytes)")
remaining_q = src.count("?")
remaining_fixme = src.count("FIXME")
print(f"remaining ?: {remaining_q}, remaining FIXME: {remaining_fixme}")
