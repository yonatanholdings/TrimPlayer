import json
import logging
import os
import subprocess
import sys
import time
import uuid
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed

# Worker counts tuned for a 2-vCPU / 2GB-RAM server.
# numpy/librosa release the GIL during their C computations, so ThreadPoolExecutor
# gives real CPU parallelism without the ~400 MB per-process overhead of ProcessPoolExecutor.
_PIPELINE_WORKERS   = 2   # concurrent download threads per job (I/O bound)
_MATCH_WORKERS      = 2   # threads for CPU-bound fingerprinting / matching (1 per CPU)
_ANALYSIS_SEMAPHORE = threading.Semaphore(5)  # max concurrent podcast jobs

# Shared thread pool for fingerprinting and pair-matching.  numpy/librosa release
# the GIL, so multiple threads actually run in parallel on both CPUs.
_MATCH_EXECUTOR = ThreadPoolExecutor(max_workers=_MATCH_WORKERS, thread_name_prefix="matcher")


logger = logging.getLogger(__name__)

import feedparser
from fastapi import APIRouter, HTTPException, BackgroundTasks, UploadFile, File
from fastapi.responses import FileResponse

from .models import (
    EpisodeSegmentsResponse, Segment, AnalyzeRequest,
    FeedResponse, FeedEpisode, CanonicalSegmentsResponse,
    CanonicalSegment, CanonicalSegmentOccurrence,
    TranscriptAnalyzeRequest, ManualSegmentsRequest,
    ClientEventsRequest, ClientEventsResponse,
)
from .audio_processor import download_file, find_duplicate_segments, fingerprint_file, match_template_to_episode, Config
from . import database as db
from .database import get_db, _reclassify_segment_type

router = APIRouter()

DOWNLOADS_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "downloads")

# Paths submitted via /analyze-paths are allowed to be streamed back.
# This is runtime-only (intentionally not persisted).
_allowed_stream_paths: set = set()
_paths_lock = threading.Lock()

# Per-job cancellation flags.  Set by POST /job/{id}/cancel; checked inside task loops.
_cancel_flags: dict = {}   # job_id → threading.Event
_cancel_lock  = threading.Lock()

# In-memory canonical template cache.
# Maps podcast_id → list[(seg_dict, template_hashes)].
# Avoids repeated DB reads in sweep_canonical_templates; invalidated after every
# promotion pass so stale templates are never used.
_template_cache: dict = {}
_template_cache_lock = threading.Lock()


def _get_cached_templates(podcast_id: str) -> list:
    """Return cached (seg, hashes) pairs for a podcast, loading from DB on miss."""
    with _template_cache_lock:
        if podcast_id in _template_cache:
            return _template_cache[podcast_id]

    canonical_segs = db.get_canonical_segments(podcast_id)
    templates = []
    for seg in canonical_segs:
        if (seg.get('episode_count') or 0) < 3:
            continue
        hashes = db.get_fingerprints_in_window(
            seg['ref_episode_id'],
            max(0.0, seg['ref_start'] - 2.0),
            seg['ref_end'] + 2.0,
        )
        if hashes:
            templates.append((seg, hashes))

    with _template_cache_lock:
        _template_cache[podcast_id] = templates
    return templates


def _invalidate_template_cache(podcast_id: str) -> None:
    with _template_cache_lock:
        _template_cache.pop(podcast_id, None)


def _register_cancel(job_id: str) -> threading.Event:
    flag = threading.Event()
    with _cancel_lock:
        _cancel_flags[job_id] = flag
    return flag


def _unregister_cancel(job_id: str) -> None:
    with _cancel_lock:
        _cancel_flags.pop(job_id, None)


def _is_cancelled(job_id: str) -> bool:
    with _cancel_lock:
        flag = _cancel_flags.get(job_id)
    return flag is not None and flag.is_set()


# ---------------------------------------------------------------------------
# Out-of-process task spawn
# ---------------------------------------------------------------------------
# CPU-heavy tasks (full-feed sweep, fingerprinting, transcription) run as
# detached subprocesses via app.worker. Keeps the FastAPI process's GIL free
# so /segments and /jobs stay responsive while a sweep is running.

_APP_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_WORKER_LOG = os.path.join(_APP_ROOT, "workers.log")


def _spawn_task(task_name: str, *args, **kwargs) -> int:
    args_json = json.dumps(list(args), default=str)
    kwargs_json = json.dumps(kwargs, default=str)
    log_fh = open(_WORKER_LOG, "ab", buffering=0)
    proc = subprocess.Popen(
        [sys.executable, "-m", "app.worker", task_name, args_json, kwargs_json],
        cwd=_APP_ROOT,
        stdin=subprocess.DEVNULL,
        stdout=log_fh,
        stderr=log_fh,
        start_new_session=True,
        close_fds=True,
    )
    log_fh.close()  # parent's reference; child still holds the fd
    logger.info("spawned worker pid=%s task=%s", proc.pid, task_name)
    return proc.pid


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _is_rerun(title: str) -> bool:
    """Detect rerun/encore episodes by common title markers."""
    t = title or ''
    return '(ש.ח.)' in t or '(rerun)' in t.lower() or '(encore)' in t.lower()


def _select_ref_indices(titles: list, n_refs: int = 4) -> list:
    """
    Pick up to n_refs reference-episode indices spread across the feed,
    preferring non-rerun episodes at each position.
    Falls back to reruns if the entire feed consists of reruns.
    """
    total = len(titles)
    if total == 0:
        return []

    pool = [i for i, t in enumerate(titles) if not _is_rerun(t)]
    if not pool:
        pool = list(range(total))   # all reruns - use them anyway

    if len(pool) <= n_refs:
        return pool

    step = len(pool) / n_refs
    return [pool[int(i * step)] for i in range(n_refs)]


def _local_url(local_path: str) -> str:
    return f"/downloads/{os.path.basename(local_path)}"


def _get_enclosure_url(entry) -> str | None:
    if getattr(entry, 'enclosures', None):
        return entry.enclosures[0].href
    for link in getattr(entry, 'links', []):
        if link.get('rel') == 'enclosure':
            return link.get('href')
    return None


def _fingerprint_with_cache(episode_id: int, filepath: str) -> list:
    """Return fingerprints from DB if available, otherwise compute and store them.

    Called from threads in the download pipeline; fingerprint_file releases the
    GIL during numpy/librosa C work so multiple threads run truly in parallel.
    """
    if db.has_fingerprints(episode_id):
        return db.get_fingerprints(episode_id)
    t0 = time.time()
    # Submit to shared executor to cap concurrent fingerprinting at _MATCH_WORKERS.
    # fingerprint_file releases the GIL for numpy but holds it for hash loops;
    # limiting concurrency prevents event-loop starvation and controls peak RAM.
    hashes = _MATCH_EXECUTOR.submit(fingerprint_file, filepath).result()
    elapsed = int((time.time() - t0) * 1000)
    db.store_fingerprints(episode_id, hashes)
    try:
        import librosa
        dur = librosa.get_duration(path=filepath)
    except Exception:
        dur = 0.0
    db.log_fingerprint(episode_id, len(hashes), dur, elapsed)
    if dur > 0:
        db.update_episode_duration(episode_id, dur)
    return hashes


def _run_analysis(ep_a_id: int, ep_b_id: int, f1: str, f2: str,
                  podcast_id: str | None = None,
                  hashes_a=None, hashes_b=None,
                  dur_a: float = None, dur_b: float = None) -> list:
    """
    Fingerprint (with DB cache), match, store raw matches, and attempt
    cluster promotion. Returns the list of segment dicts.

    hashes_a / hashes_b: pre-computed in-memory fingerprints. When provided
    the DB is NOT re-queried, so stale/pruned cached fingerprints are bypassed.

    dur_a / dur_b: pre-computed episode durations (seconds). When provided,
    librosa.get_duration() is skipped inside find_duplicate_segments. Required
    when audio files may not exist (cross-batch hash-only matching).
    """
    t0 = time.time()
    h_a = hashes_a if hashes_a is not None else _fingerprint_with_cache(ep_a_id, f1)
    h_b = hashes_b if hashes_b is not None else _fingerprint_with_cache(ep_b_id, f2)

    segments = find_duplicate_segments(
        [f1, f2],
        hashes_a=h_a, hashes_b=h_b,
        dur_a=dur_a or None, dur_b=dur_b or None,
    )
    elapsed  = int((time.time() - t0) * 1000)

    db.log_match(ep_a_id, ep_b_id,
                 [], len(segments), len(segments), elapsed)

    # Persist every detected segment as a raw match
    for seg in segments:
        db.store_raw_match(ep_a_id, ep_b_id, seg)

    # Attempt promotion to canonical when podcast context is known
    if podcast_id:
        db.try_promote_clusters(podcast_id)
        _invalidate_template_cache(podcast_id)

    return segments


# ---------------------------------------------------------------------------
# GET /segments
# ---------------------------------------------------------------------------

@router.get("/segments", response_model=EpisodeSegmentsResponse)
def get_segments(episode_url: str, episode_guid: str = None):
    # Primary lookup: exact URL match
    ep = db.get_episode_by_url(episode_url)
    # Fallback: GUID match (handles tracking-redirect URL differences between clients)
    if not ep and episode_guid:
        ep = db.get_episode_by_guid(episode_guid)
        if ep:
            logger.info("Segments: URL miss, GUID hit for guid=%s (stored url=%s)", episode_guid, ep['url'])

    segs = db.get_segments_for_url(ep['url']) if ep else []

    # Auto-trigger /analyze on segments miss: avoids the client having to do
    # a separate POST /analyze round-trip and helps non-mobile clients (web UI,
    # curl, /catalog) drive coverage. Guarded by has_running_job_for_podcast
    # so multiple in-flight requests for the same podcast don't pile up jobs.
    if not segs and ep and ep.get('podcast_id'):
        rss_url = ep['podcast_id']
        if not db.has_running_job_for_podcast(rss_url):
            auto_job_id = str(uuid.uuid4())
            db.create_job(auto_job_id, podcast_id=rss_url)
            _spawn_task(
                'analyze_feed', rss_url, auto_job_id,
                episode_url=episode_url,
                episode_guid=episode_guid,
            )
            logger.info('auto-analyze: spawned job %s for %s (segments miss on %s)',
                        auto_job_id, rss_url[:60], episode_url[:80])

    local_url = None
    if ep:
        local_path = db.get_audio_file_path(ep['id'])
        if local_path and os.path.exists(local_path):
            local_url = _local_url(local_path)

    return EpisodeSegmentsResponse(
        episode_url=episode_url,
        episode_guid=ep.get('guid') if ep else episode_guid,
        local_audio_url=local_url,
        segments=[
            Segment(
                start=s['start'], end=s['end'],
                start_b=s.get('start_b'), end_b=s.get('end_b'),
                type=s.get('type', 'ad'),
                source=s.get('source'),
            )
            for s in segs
        ]
    )


# ---------------------------------------------------------------------------
# POST /segments/manual
# ---------------------------------------------------------------------------

@router.post("/segments/manual")
def save_manual_segments(request: ManualSegmentsRequest):
    ep = db.get_episode_by_url(request.episode_url)
    if not ep:
        ep_id = db.upsert_episode(request.episode_url)
    else:
        ep_id = ep['id']
    segs = [{"start": s.start, "end": s.end, "type": s.type} for s in request.segments]
    db.store_manual_segments(ep_id, segs)
    return {"status": "ok", "episode_url": request.episode_url, "count": len(segs)}


# ---------------------------------------------------------------------------
# GET /job/{job_id}
# ---------------------------------------------------------------------------

@router.get("/jobs")
def list_jobs():
    return db.get_recent_jobs(limit=20)


@router.get("/job/{job_id}")
def get_job_status(job_id: str):
    job = db.get_job(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")
    return job


@router.post("/job/{job_id}/cancel")
def cancel_job(job_id: str):
    job = db.get_job(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")
    if job['status'] != 'running':
        raise HTTPException(status_code=400, detail=f"Job is not running (status: {job['status']})")
    with _cancel_lock:
        flag = _cancel_flags.get(job_id)
        if flag:
            flag.set()
    db.cancel_job(job_id)
    return {"status": "cancelled", "job_id": job_id}


# ---------------------------------------------------------------------------
# GET /feed
# ---------------------------------------------------------------------------

@router.get("/feed", response_model=FeedResponse)
def get_feed(rss_url: str):
    try:
        feed = feedparser.parse(rss_url)
        if not feed.entries:
            raise HTTPException(status_code=400, detail="Could not parse RSS feed or feed is empty")

        episodes = []
        for entry in feed.entries:
            audio_url = _get_enclosure_url(entry)
            if not audio_url:
                continue

            image = None
            if hasattr(feed.feed, 'image'):
                image = feed.feed.image.href
            if hasattr(entry, 'image'):
                image = entry.image.href

            entry_guid = entry.get('id') or entry.get('guid') or None
            db.upsert_episode(audio_url, title=entry.get('title', ''),
                              podcast_id=rss_url, guid=entry_guid,
                              published=entry.get('published', ''))

            episodes.append(FeedEpisode(
                title=entry.get('title', 'Unknown Title'),
                audio_url=audio_url,
                published=entry.get('published', ''),
                duration=entry.get('itunes_duration', ''),
                image=image
            ))

        return FeedResponse(
            podcast_title=feed.feed.get('title', 'Unknown Podcast'),
            podcast_author=feed.feed.get('author', ''),
            podcast_image=feed.feed.get('image', {}).get('href') if hasattr(feed.feed, 'image') else None,
            episodes=episodes
        )
    except HTTPException:
        raise
    except Exception as e:
        print(f"Feed fetch error: {e}")
        raise HTTPException(status_code=500, detail="Failed to fetch or parse the RSS feed.")


# ---------------------------------------------------------------------------
# POST /analyze  (two episodes or RSS)
# ---------------------------------------------------------------------------

@router.post("/analyze")
def trigger_analysis(request: AnalyzeRequest, background_tasks: BackgroundTasks):
    podcast_id = request.rss_url or None

    # Guard: if a job is already running for this podcast, return it instead of
    # spawning a duplicate — prevents concurrent jobs from interfering with each other.
    if podcast_id and not (request.ep1_url and request.ep2_url):
        existing = db.get_running_job_for_podcast(podcast_id)
        if existing:
            return {"status": "already_running", "job_id": existing['id']}

    job_id = str(uuid.uuid4())
    db.create_job(job_id, podcast_id=podcast_id)

    if request.ep1_url and request.ep2_url:
        _spawn_task(
            "analyze_episodes", request.ep1_url, request.ep2_url, job_id, podcast_id
        )
    elif request.rss_url:
        _spawn_task(
            "analyze_feed", request.rss_url, job_id,
            episode_url=request.episode_url, episode_guid=request.episode_guid,
        )
    else:
        raise HTTPException(status_code=400,
                            detail="Either rss_url or both ep1_url and ep2_url must be provided")

    return {"status": "analysis_started", "job_id": job_id}


def analyze_feed_task(rss_url: str, job_id: str, episode_url: str = None, episode_guid: str = None):
    analyze_full_feed_task(rss_url, job_id, max_episodes=5,
                           playing_episode_url=episode_url,
                           playing_episode_guid=episode_guid)


def analyze_episodes_task(url1: str, url2: str, job_id: str, podcast_id: str = None):
    print(f"Analyzing:\n  {url1}\n  {url2}")
    os.makedirs(DOWNLOADS_DIR, exist_ok=True)

    ep_a_id = db.upsert_episode(url1, podcast_id=podcast_id)
    ep_b_id = db.upsert_episode(url2, podcast_id=podcast_id)

    # Use cached local path if available
    f1 = db.get_audio_file_path(ep_a_id) or os.path.join(DOWNLOADS_DIR, f"{uuid.uuid4()}.mp3")
    f2 = db.get_audio_file_path(ep_b_id) or os.path.join(DOWNLOADS_DIR, f"{uuid.uuid4()}.mp3")

    try:
        dl_ms1 = download_file(url1, f1)
        dl_ms2 = download_file(url2, f2)
        db.set_audio_file(ep_a_id, f1, download_ms=dl_ms1 or None)
        db.set_audio_file(ep_b_id, f2, download_ms=dl_ms2 or None)

        # Skip if already matched
        if db.pair_already_matched(ep_a_id, ep_b_id):
            print("Pair already matched, skipping.")
        else:
            _run_analysis(ep_a_id, ep_b_id, f1, f2, podcast_id)

        # Template sweep: match both episodes against all existing canonical segments
        # for this podcast, so a new episode inherits known patterns immediately.
        tmpl_suffix = ''
        if podcast_id:
            db.try_promote_clusters(podcast_id)
            _invalidate_template_cache(podcast_id)
            ep_ids   = [ep_a_id, ep_b_id]
            ep_hashes = [db.get_fingerprints(ep_a_id) or None,
                         db.get_fingerprints(ep_b_id) or None]
            new_tmpl = sweep_canonical_templates(podcast_id, ep_ids, ep_hashes)
            if new_tmpl:
                tmpl_suffix = f', {new_tmpl} template match(es)'

        # Drop fingerprints for either episode that produced no segments.
        for _ep in (ep_a_id, ep_b_id):
            db.cleanup_episode_fingerprints_if_no_segments(_ep)

        db.update_job(job_id, 'done',
                      episode_urls=[url1, url2],
                      message=f'Analysis complete{tmpl_suffix}')

    except Exception as e:
        print(f"Analysis failed: {e}")
        db.update_job(job_id, 'error', message='Analysis failed. Check server logs.')
        for path in (f1, f2):
            if os.path.exists(path):
                os.remove(path)


# ---------------------------------------------------------------------------
# POST /analyze-full-feed
# ---------------------------------------------------------------------------

@router.post("/analyze-full-feed")
def analyze_full_feed(request: AnalyzeRequest, background_tasks: BackgroundTasks):
    """
    Download every episode in the RSS feed, fingerprint each one,
    compare all against the first episode, then promote canonical segments.
    """
    if not request.rss_url:
        raise HTTPException(status_code=400, detail="rss_url required")

    existing = db.get_running_job_for_podcast(request.rss_url)
    if existing:
        return {"status": "already_running", "job_id": existing['id'], "podcast_id": request.rss_url}

    job_id = str(uuid.uuid4())
    db.create_job(job_id, podcast_id=request.rss_url)
    _spawn_task(
        "analyze_full_feed", request.rss_url, job_id,
        request.max_episodes,
        request.auto_transcript,
    )
    return {"status": "analysis_started", "job_id": job_id, "podcast_id": request.rss_url}


# ---------------------------------------------------------------------------
# Canonical template sweep
# ---------------------------------------------------------------------------

def sweep_canonical_templates(podcast_id: str, ep_ids: list,
                               all_hashes: list) -> int:
    """
    For every canonical segment in podcast_id, match its audio template against
    each episode that doesn't yet have a mapping for it.

    Template hashes are served from the in-memory cache (_get_cached_templates)
    so repeated calls within the same job avoid redundant DB reads.
    Only canonical segments with episode_count >= 3 are used (avoids weak clusters).

    Returns the number of new episode_segments rows created.
    """
    templates = _get_cached_templates(podcast_id)
    if not templates:
        return 0

    new_mappings = 0
    for seg, template_hashes in templates:
        for ep_id, ep_hashes in zip(ep_ids, all_hashes):
            if ep_hashes is None:
                continue
            if ep_id == seg['ref_episode_id']:
                continue
            if db.canonical_already_mapped(ep_id, seg['id']):
                continue

            result = match_template_to_episode(template_hashes, ep_hashes)
            if result is None:
                continue

            db.store_template_match(
                episode_id=ep_id,
                canonical_segment_id=seg['id'],
                local_start=result['local_start'],
                local_end=result['local_end'],
                confidence=result['confidence'],
                hash_count=result['hash_count'],
                ref_episode_id=seg['ref_episode_id'],
            )
            new_mappings += 1
            logger.info(
                "Template sweep: ep%d matched canonical segment %d at %.1f–%.1fs (conf=%.0f%%)",
                ep_id, seg['id'], result['local_start'], result['local_end'],
                result['confidence'] * 100,
            )

    return new_mappings


def _sweep_historical_for_new_segments(
    podcast_id: str,
    new_canonical_ids: list,
    current_ep_ids: list,
    job_id: str,
):
    """Download, fingerprint, and template-match all historical episodes against
    newly promoted intro/outro canonical segments.

    Only runs for intro/outro types — ad segments are time-bounded and don't
    apply retroactively to older episodes.
    """
    all_episodes = db.get_all_podcast_episodes_with_audio(podcast_id)
    current_set = set(current_ep_ids)

    # Build template list once (template hashes from ref episode window)
    templates = []
    for cid in new_canonical_ids:
        canonical = db.get_canonical_segment_by_id(cid)
        if not canonical or canonical['segment_type'] not in ('intro', 'outro'):
            continue
        template_hashes = db.get_fingerprints_in_window(
            canonical['ref_episode_id'],
            max(0.0, canonical['ref_start'] - 2.0),
            canonical['ref_end'] + 2.0,
        )
        if template_hashes:
            templates.append((canonical, template_hashes))

    if not templates:
        return

    # Episodes not in the current batch that need at least one new mapping
    to_process = [
        ep for ep in all_episodes
        if ep['id'] not in current_set
        and not all(db.canonical_already_mapped(ep['id'], c['id']) for c, _ in templates)
    ]

    if not to_process:
        return

    total = len(to_process)
    logger.info("Historical sweep: %d episodes to process for %d new canonical segment(s)",
                total, len(templates))
    db.update_job(job_id, 'running',
                  message=f'Historical sweep: {total} episodes against {len(templates)} new segment(s)…')

    # Parallel download + fingerprint for the historical sweep.
    # Template matching (fast, in-memory) and DB writes run sequentially after
    # each episode's hashes are ready to avoid write contention.
    sweep_results: dict = {}   # ep index → (ep_hashes, path)

    def _sweep_dl_fp(i: int):
        ep = to_process[i]
        if db.has_fingerprints(ep['id']):
            return i, db.get_fingerprints(ep['id']), ep.get('local_path')
        path = ep['local_path']
        if not path or not os.path.exists(path):
            path = os.path.join(DOWNLOADS_DIR, f"{uuid.uuid4()}.mp3")
            try:
                dl_ms = download_file(ep['url'], path)
                db.set_audio_file(ep['id'], path, download_ms=dl_ms or None)
            except Exception as e:
                logger.warning("Historical sweep: download failed for ep%d (%s): %s",
                               ep['id'], ep['url'], e)
                return i, None, None
        try:
            return i, _fingerprint_with_cache(ep['id'], path), path
        except Exception as e:
            logger.warning("Historical sweep: fingerprint failed for ep%d: %s", ep['id'], e)
            return i, None, None

    completed_sweep = 0
    with ThreadPoolExecutor(max_workers=_PIPELINE_WORKERS) as pool:
        futures = {pool.submit(_sweep_dl_fp, i): i for i in range(total)}
        for future in as_completed(futures):
            if _is_cancelled(job_id):
                pool.shutdown(wait=False, cancel_futures=True)
                logger.info("Historical sweep cancelled after %d/%d", completed_sweep, total)
                return
            idx, ep_hashes, path = future.result()
            sweep_results[idx] = (ep_hashes, path)
            completed_sweep += 1
            db.update_job(job_id, 'running',
                          message=f'Historical sweep: {completed_sweep}/{total} episodes processed…')

    # Template matching + DB writes (sequential — ordering-safe, fast pure computation)
    for i, ep in enumerate(to_process):
        ep_hashes, path = sweep_results.get(i, (None, None))
        if ep_hashes is None:
            continue
        matched = False
        for canonical, template_hashes in templates:
            if db.canonical_already_mapped(ep['id'], canonical['id']):
                continue
            result = match_template_to_episode(template_hashes, ep_hashes)
            if result:
                db.store_template_match(
                    episode_id=ep['id'],
                    canonical_segment_id=canonical['id'],
                    local_start=result['local_start'],
                    local_end=result['local_end'],
                    confidence=result['confidence'],
                    hash_count=result['hash_count'],
                    ref_episode_id=canonical['ref_episode_id'],
                )
                matched = True
                logger.info(
                    "Historical sweep: ep%d matched canonical %d at %.1f–%.1fs (conf=%.0f%%)",
                    ep['id'], canonical['id'],
                    result['local_start'], result['local_end'],
                    result['confidence'] * 100,
                )
        if matched:
            # Always refine — even when audio is missing, the function applies
            # the duration-based snaps (intro local_start=0, outro local_end=ep_dur)
            # via the DB-stored duration. VAD walks are skipped internally.
            try:
                db.refine_episode_segment_boundaries(ep['id'], path)
            except Exception as e:
                logger.warning("Historical sweep: boundary refinement failed for ep%d: %s",
                               ep['id'], e)
        db.mark_audio_processing_complete(ep['id'])

    db.evict_audio_files()
    logger.info("Historical sweep complete: %d episodes processed", total)


def analyze_full_feed_task(rss_url: str, job_id: str, max_episodes: int = 5,
                           auto_transcript: bool = False,
                           playing_episode_url: str = None,
                           playing_episode_guid: str = None):
    print(f"Full-feed analysis: {rss_url} (batch {max_episodes})")
    cancel_flag = _register_cancel(job_id)

    stale = db.get_stale_podcasts(Config.ALGORITHM_VERSION)
    if rss_url in stale:
        logger.warning(
            "Podcast %s was analyzed with an older algorithm version — "
            "existing canonical data will be replaced by this run.", rss_url
        )

    try:
        feed = feedparser.parse(rss_url)
        if not feed.entries:
            db.update_job(job_id, 'error', message='Could not parse RSS feed.')
            return

        podcast_title = feed.feed.get('title', '')
        os.makedirs(DOWNLOADS_DIR, exist_ok=True)

        # Build url → entry map (preserves feed order, deduplicates by URL)
        url_to_entry: dict = {}
        for entry in feed.entries:
            url = _get_enclosure_url(entry)
            if url and url not in url_to_entry:
                url_to_entry[url] = entry

        all_urls = list(url_to_entry.keys())

        # Acquire slot before any DB writes — prevents Phase 1 upsert loops from
        # multiple jobs creating write contention (e.g. 4 jobs × 1300 upserts).
        db.update_job(job_id, 'running', message='Waiting for analysis slot…')
        _ANALYSIS_SEMAPHORE.acquire()

        db.set_podcast_feed_episode_count(rss_url, len(all_urls))

        # Phase 1: upsert ALL episodes as metadata in one transaction.
        all_ep_id_map: dict = {}  # url → ep_id
        episode_rows = []
        for url, entry in url_to_entry.items():
            episode_rows.append({
                'url': url,
                'title': entry.get('title', ''),
                'published': entry.get('published', ''),
                'guid': entry.get('id') or entry.get('guid') or None,
            })
        all_ep_id_map = db.upsert_episodes_bulk(
            episode_rows, podcast_title=podcast_title, podcast_id=rss_url)

        # Phase 2: filter to episodes without fingerprints (not yet processed)
        unprocessed_urls = [u for u in all_urls if not db.has_fingerprints(all_ep_id_map[u])]

        # Prioritize the currently playing episode so it gets segments ASAP
        playing_url = None
        if playing_episode_guid:
            playing_url = next((
                u for u, e in url_to_entry.items()
                if (e.get('id') or e.get('guid')) == playing_episode_guid
            ), None)
        if playing_url is None and playing_episode_url and playing_episode_url in all_ep_id_map:
            playing_url = playing_episode_url

        if playing_url and playing_url in unprocessed_urls:
            unprocessed_urls.remove(playing_url)
            unprocessed_urls.insert(0, playing_url)
            logger.info("Prioritized playing episode: %s", playing_url)

        # Phase 3: take this batch
        urls = unprocessed_urls[:max_episodes]

        if len(urls) < 2:
            db.update_job(job_id, 'done',
                          message='All episodes already processed or not enough new audio.')
            return

        ep_ids = [all_ep_id_map[u] for u in urls]
        episode_titles = [url_to_entry.get(u, {}).get('title', '') for u in urls]
        local_paths = []
        for ep_id in ep_ids:
            cached = db.get_audio_file_path(ep_id)
            local_paths.append(cached or os.path.join(DOWNLOADS_DIR, f"{uuid.uuid4()}.mp3"))

        total = len(urls)

        # Download + fingerprint in a parallel pipeline.
        all_hashes = [None] * total
        completed_dl_fp = 0
        db.update_job(job_id, 'running',
                      message=f'Downloading & fingerprinting {total} episodes (0/{total})…')

        def _dl_fp(i: int):
            url, path, ep_id = urls[i], local_paths[i], ep_ids[i]
            if not os.path.exists(path):
                try:
                    dl_ms = download_file(url, path)
                    db.set_audio_file(ep_id, path, download_ms=dl_ms or None)
                except Exception as e:
                    logger.warning("Download failed for %s: %s", url, e)
                    return i, None
            if not os.path.exists(path):
                return i, None
            try:
                return i, _fingerprint_with_cache(ep_id, path)
            except Exception as e:
                logger.warning("Fingerprint failed for ep%d: %s", ep_id, e)
                return i, None

        with ThreadPoolExecutor(max_workers=_PIPELINE_WORKERS) as pool:
            futures = {pool.submit(_dl_fp, i): i for i in range(total)}
            for future in as_completed(futures):
                if cancel_flag.is_set():
                    pool.shutdown(wait=False, cancel_futures=True)
                    _unregister_cancel(job_id)
                    return
                idx, hashes = future.result()
                all_hashes[idx] = hashes
                completed_dl_fp += 1
                db.update_job(job_id, 'running',
                              message=f'Downloading & fingerprinting {completed_dl_fp}/{total}: '
                                      f'{episode_titles[idx][:50]}')

        # Build comparison pairs: star topology + consecutive sliding window
        ref_indices = _select_ref_indices(episode_titles[:total])
        logger.info(f"Reference episodes ({len(ref_indices)}): indices {ref_indices}")

        pairs: set = set()  # (batch_index_i, batch_index_j)
        for ref_i in ref_indices:
            for i in range(total):
                if i != ref_i and all_hashes[i] is not None and all_hashes[ref_i] is not None:
                    pairs.add((min(ref_i, i), max(ref_i, i)))
        for i in range(total - 1):
            if all_hashes[i] is not None and all_hashes[i + 1] is not None:
                pairs.add((i, i + 1))

        # Cross-batch discovery: use idx_fp_hash to find episodes from previous batches
        # that share fingerprints with each newly-fingerprinted episode.  This catches
        # intro/outro matches across batch boundaries without the O(N²) global comparison.
        # Concurrent cross-batch lookups were SQLite-era serialized via _XBATCH_SEMAPHORE;
        # on PG, the planner parallelizes self-joins on the now-120MB fingerprints
        # table fine. _ANALYSIS_SEMAPHORE(5) is the outer cap.
        cross_batch: list = []  # (ep_a_id, ep_b_id, batch_idx, hashes_b, old_path, dur_a, dur_b)
        for i, ep_id in enumerate(ep_ids):
            if all_hashes[i] is None:
                continue
            candidates = db.get_hash_similar_episodes(
                ep_id, ep_ids, rss_url, min_shared=50, limit=5
            )
            for cand_ep_id, shared in candidates:
                if db.pair_already_matched(ep_id, cand_ep_id):
                    continue
                hashes_b = db.get_fingerprints(cand_ep_id)
                if not hashes_b:
                    continue
                dur_b_v = db.get_episode_duration(cand_ep_id)
                if dur_b_v <= 0.0:
                    # Can't classify segments without duration and old audio is evicted
                    continue
                dur_a_v  = db.get_episode_duration(ep_ids[i])
                old_path = db.get_audio_file_path(cand_ep_id) or f"evicted://{cand_ep_id}"
                cross_batch.append((ep_id, cand_ep_id, i, hashes_b, old_path, dur_a_v, dur_b_v))
                logger.info("Cross-batch: ep%d ↔ ep%d (%d shared hashes)", ep_id, cand_ep_id, shared)

        db.update_job(job_id, 'running',
                      message=f'Matching pairs ({len(cross_batch)} cross-batch) across {total} episodes…')

        # Submit all pairs to the shared thread pool.  numpy/librosa release the GIL
        # during their C work, so multiple threads run truly in parallel on both CPUs
        # without the ~400 MB per-process overhead of ProcessPoolExecutor.
        # podcast_id=None defers try_promote_clusters to the single promotion pass below.
        total_segs = 0
        completed_pairs = 0
        pair_futures: dict = {}  # future → (ep_a_id, ep_b_id)

        for i, j in sorted(pairs):
            if db.pair_already_matched(ep_ids[i], ep_ids[j]):
                continue
            if all_hashes[i] is None or all_hashes[j] is None:
                continue
            fut = _MATCH_EXECUTOR.submit(
                find_duplicate_segments,
                [local_paths[i], local_paths[j]],
                hashes_a=all_hashes[i],
                hashes_b=all_hashes[j],
                dur_a=db.get_episode_duration(ep_ids[i]),
                dur_b=db.get_episode_duration(ep_ids[j]),
            )
            pair_futures[fut] = (ep_ids[i], ep_ids[j])

        for ep_a_id, ep_b_id, batch_idx, hashes_b, old_path, dur_a_v, dur_b_v in cross_batch:
            fut = _MATCH_EXECUTOR.submit(
                find_duplicate_segments,
                [local_paths[batch_idx], old_path],
                hashes_a=all_hashes[batch_idx],
                hashes_b=hashes_b,
                dur_a=dur_a_v,
                dur_b=dur_b_v,
            )
            pair_futures[fut] = (ep_a_id, ep_b_id)

        n_pairs = len(pair_futures)
        for future in as_completed(pair_futures):
            if cancel_flag.is_set():
                for f in pair_futures:
                    f.cancel()
                _unregister_cancel(job_id)
                return
            ep_a_id, ep_b_id = pair_futures[future]
            try:
                segs = future.result()
                total_segs += len(segs)
                db.log_match(ep_a_id, ep_b_id, [], len(segs), len(segs), 0)
                for seg in segs:
                    db.store_raw_match(ep_a_id, ep_b_id, seg)
            except Exception as e:
                logger.warning("Match failed ep%d vs ep%d: %s", ep_a_id, ep_b_id, e)
            completed_pairs += 1
            if n_pairs > 0 and (completed_pairs % max(1, n_pairs // 10) == 0
                                 or completed_pairs == n_pairs):
                db.update_job(job_id, 'running',
                              message=f'Matching pairs {completed_pairs}/{n_pairs}…')

        # Promotion pass
        promoted, new_intro_outro_ids = db.try_promote_clusters(rss_url)
        _invalidate_template_cache(rss_url)
        db.stamp_algorithm_version(rss_url, Config.ALGORITHM_VERSION)

        # Template sweep
        db.update_job(job_id, 'running', message='Running canonical template sweep…')
        new_template_mappings = sweep_canonical_templates(rss_url, ep_ids, all_hashes)

        # Boundary refinement
        db.update_job(job_id, 'running', message='Refining segment boundaries…')
        refined_boundaries = 0
        for ep_id, path in zip(ep_ids, local_paths):
            try:
                refined_boundaries += db.refine_episode_segment_boundaries(ep_id, path)
            except Exception as e:
                logger.warning("Boundary refinement failed for ep%d: %s", ep_id, e)
            db.mark_audio_processing_complete(ep_id)
        if refined_boundaries:
            logger.info("Refined %d segment boundaries", refined_boundaries)

        # Drop fingerprints for batch episodes that produced no segments. The data is
        # the dominant source of DB bloat and is not used by template-matching of
        # future episodes (canonical templates already carry the audio fingerprints).
        no_seg_cleaned = 0
        for ep_id in ep_ids:
            no_seg_cleaned += db.cleanup_episode_fingerprints_if_no_segments(ep_id)
        if no_seg_cleaned:
            logger.info("Cleaned %d fingerprints from no-segment episodes", no_seg_cleaned)

        # Historical sweep for newly promoted intro/outro segments
        if new_intro_outro_ids:
            logger.info("New intro/outro canonical segments: %s — starting historical sweep",
                        new_intro_outro_ids)
            _sweep_historical_for_new_segments(rss_url, new_intro_outro_ids, ep_ids, job_id)

        # Auto-transcript (optional)
        transcript_done = 0
        if auto_transcript:
            needs_transcript = [
                (ep_id, url)
                for ep_id, url, path in zip(ep_ids, urls, local_paths)
                if os.path.exists(path) and not db.has_transcript_segments(ep_id)
            ]
            n_transcript = len(needs_transcript)
            for t_num, (ep_id, url) in enumerate(needs_transcript):
                db.update_job(job_id, 'running',
                              message=f'Transcript analysis {t_num+1}/{n_transcript}: '
                                      f'{episode_titles[urls.index(url)][:50]}')
                try:
                    analyze_transcript_task(url, job_id=None)
                    transcript_done += 1
                except Exception as e:
                    print(f"  Transcript analysis failed for {url}: {e}")

        # Evict stale audio files
        db.update_job(job_id, 'running', message='Evicting stale audio files…')
        evicted, freed_bytes = db.evict_audio_files()
        evict_suffix = f', evicted {evicted} file(s) ({freed_bytes // (1024*1024)} MB freed)' if evicted else ''

        hist_suffix = f', historical sweep for {len(new_intro_outro_ids)} new segment(s)' if new_intro_outro_ids else ''
        tmpl_suffix = f', {new_template_mappings} template matches' if new_template_mappings else ''
        base_msg = (f'Done. {total} episodes, {total_segs} raw segments, '
                    f'{promoted} canonical segments promoted{tmpl_suffix}{hist_suffix}'
                    + (f', {transcript_done} transcribed' if auto_transcript else '')
                    + evict_suffix)

        _unregister_cancel(job_id)
        _ANALYSIS_SEMAPHORE.release()

        # Mark the current job done FIRST — has_running_job_for_podcast must not
        # count this job or the continuation guard would always block the spawn.
        db.update_job(job_id, 'done', episode_urls=urls, message=base_msg + '.')

        # Spawn next batch as a detached subprocess if there are unprocessed
        # episodes remaining and no other job is already running for this podcast.
        remaining = [u for u in all_urls if not db.has_fingerprints(all_ep_id_map[u])]
        if remaining and not db.has_running_job_for_podcast(rss_url):
            next_job_id = str(uuid.uuid4())
            db.create_job(next_job_id, podcast_id=rss_url)
            _spawn_task(
                "analyze_full_feed", rss_url, next_job_id,
                max_episodes=5, auto_transcript=auto_transcript,
            )
            logger.info("Spawned continuation job %s for %d remaining episodes", next_job_id, len(remaining))
            db.update_job(job_id, 'done',
                          message=base_msg + f', queued next batch ({len(remaining)} remaining).')

    except Exception as e:
        _unregister_cancel(job_id)
        _ANALYSIS_SEMAPHORE.release()
        print(f"Full-feed analysis failed: {e}")
        import traceback
        traceback.print_exc()
        db.update_job(job_id, 'error', message='Analysis failed. Check server logs.')


# ---------------------------------------------------------------------------
# POST /analyze-transcript
# ---------------------------------------------------------------------------

@router.post("/analyze-transcript")
def analyze_transcript(request: TranscriptAnalyzeRequest, background_tasks: BackgroundTasks):
    """
    Transcribe a single episode with VibeVoice and detect intro/outro/ad segments
    from the transcript text.  Results are merged into GET /segments alongside
    any fingerprint-based segments already stored for the episode.
    """
    job_id = str(uuid.uuid4())
    db.create_job(job_id)
    _spawn_task("analyze_transcript", request.episode_url, job_id)
    return {"status": "analysis_started", "job_id": job_id}


def analyze_transcript_task(episode_url: str, job_id: str | None):
    """Run transcript analysis for one episode.

    job_id may be None when called inline from analyze_full_feed_task; in that
    case all db.update_job calls are skipped so the parent job status is preserved.
    """
    def _update(status, **kw):
        if job_id:
            db.update_job(job_id, status, **kw)

    import os as _os
    _os.makedirs(DOWNLOADS_DIR, exist_ok=True)

    try:
        _update('running', message='Downloading episode...')
        ep_id = db.upsert_episode(episode_url)

        local_path = db.get_audio_file_path(ep_id)
        if not local_path or not os.path.exists(local_path):
            local_path = os.path.join(DOWNLOADS_DIR, f"{uuid.uuid4()}.mp3")
            dl_ms = download_file(episode_url, local_path)
            db.set_audio_file(ep_id, local_path, download_ms=dl_ms or None)

        # Skip if already transcribed
        if db.has_transcript_segments(ep_id):
            _update('done', episode_urls=[episode_url],
                    message='Transcript segments already cached.')
            return

        ep_info = db.get_episode_by_id(ep_id)
        episode_duration = ep_info.get('duration_seconds') or 0.0
        if not episode_duration:
            try:
                import librosa
                episode_duration = librosa.get_duration(path=local_path)
                db.upsert_episode(episode_url, duration_seconds=episode_duration)
            except Exception:
                pass

        _update('running', message='Preprocessing audio for ASR...')
        from .speech_preprocessor import preprocess_for_asr, chunk_time_offsets
        chunk_paths = preprocess_for_asr(local_path)

        _update('running', message=f'Transcribing {len(chunk_paths)} chunk(s) with Whisper...')
        from .transcriber import transcribe
        offsets = chunk_time_offsets(len(chunk_paths))
        words = transcribe(chunk_paths, time_offsets=offsets)

        # Clean up temp chunk files
        for p in chunk_paths:
            try:
                os.remove(p)
            except Exception as exc:
                logger.debug("Failed to delete chunk file %s: %s", p, exc)

        _update('running', message='Detecting segments from transcript...')
        from .transcript_segmenter import detect_segments
        segments = detect_segments(words, episode_duration=episode_duration)

        db.store_transcript_segments(ep_id, segments)

        _update('done', episode_urls=[episode_url],
                message=f'Found {len(segments)} transcript segment(s).')

    except Exception as exc:
        logger.exception("Transcript analysis failed for %s", episode_url)
        _update('error', message=f'Transcript analysis failed: {exc}')


# ---------------------------------------------------------------------------
# GET /canonical
# ---------------------------------------------------------------------------

@router.get("/canonical/{segment_id}/occurrences")
def get_canonical_occurrences(segment_id: int):
    """Lazy-load occurrences for a single canonical segment."""
    seg = db.get_canonical_segment_by_id(segment_id)
    if not seg:
        raise HTTPException(status_code=404, detail="Segment not found")
    occs = db.get_occurrences_for_canonical(segment_id)
    for occ in occs:
        occ['effective_type'] = _reclassify_segment_type(seg['segment_type'], occ['local_start'])
        local_path = occ.get('local_path')
        occ['local_audio_url'] = _local_url(local_path) if local_path and os.path.exists(local_path) else None
    return {"segment_id": segment_id, "occurrences": occs}


@router.get("/canonical", response_model=CanonicalSegmentsResponse)
def get_canonical_segments(podcast_id: str, include_occurrences: bool = True):
    """Return all canonical segments for a podcast.

    Pass include_occurrences=false to skip the per-segment occurrence queries
    and get a fast metadata-only response; occurrences can then be fetched
    lazily via GET /canonical/{id}/occurrences.
    """
    if include_occurrences:
        raw = db.get_canonical_segments_with_occurrences(podcast_id)
    else:
        raw = db.get_canonical_segments(podcast_id)
        for seg in raw:
            seg['occurrences'] = []
    segments = []
    for seg in raw:
        occurrences = []
        for occ in seg.get('occurrences', []):
            local_path = occ.get('local_path')
            local_audio = _local_url(local_path) if local_path and os.path.exists(local_path) else None
            occurrences.append(CanonicalSegmentOccurrence(
                episode_url=occ['episode_url'],
                episode_title=occ.get('episode_title'),
                local_start=occ['local_start'],
                local_end=occ['local_end'],
                confidence=occ.get('confidence'),
                local_audio_url=local_audio,
                effective_type=occ.get('effective_type'),
            ))
        segments.append(CanonicalSegment(
            id=seg['id'],
            segment_type=seg['segment_type'],
            ref_start=seg['ref_start'],
            ref_end=seg['ref_end'],
            episode_count=seg['episode_count'],
            avg_confidence=seg.get('avg_confidence'),
            min_confidence=seg.get('min_confidence'),
            occurrences=occurrences,
        ))
    return CanonicalSegmentsResponse(podcast_id=podcast_id, segments=segments)


# ---------------------------------------------------------------------------
# POST /analyze-local
# ---------------------------------------------------------------------------

@router.post("/analyze-local")
async def analyze_local_files(
    file1: UploadFile = File(...),
    file2: UploadFile = File(...),
    background_tasks: BackgroundTasks = None,
):
    os.makedirs(DOWNLOADS_DIR, exist_ok=True)
    f1 = os.path.join(DOWNLOADS_DIR, f"{uuid.uuid4()}_{file1.filename}")
    f2 = os.path.join(DOWNLOADS_DIR, f"{uuid.uuid4()}_{file2.filename}")

    try:
        with open(f1, "wb") as buf:
            buf.write(await file1.read())
        with open(f2, "wb") as buf:
            buf.write(await file2.read())

        job_id = str(uuid.uuid4())
        db.create_job(job_id)
        _spawn_task("analyze_local_files", f1, f2, file1.filename, file2.filename, job_id)
        return {"status": "analysis_started", "job_id": job_id,
                "file1": file1.filename, "file2": file2.filename}
    except Exception as e:
        print(f"Error saving files: {e}")
        raise HTTPException(status_code=500, detail="Failed to save files.")


def analyze_local_files_task(f1: str, f2: str, name1: str, name2: str, job_id: str):
    url1 = _local_url(f1)
    url2 = _local_url(f2)

    ep_a_id = db.upsert_episode(url1)
    ep_b_id = db.upsert_episode(url2)
    db.set_audio_file(ep_a_id, f1)
    db.set_audio_file(ep_b_id, f2)

    try:
        segs = _run_analysis(ep_a_id, ep_b_id, f1, f2)
        db.update_job(job_id, 'done', episode_urls=[url1, url2],
                      message=f'Found {len(segs)} segment(s)')
    except Exception as e:
        print(f"Local analysis failed: {e}")
        db.update_job(job_id, 'error', message='Analysis failed. Check server logs.')


# ---------------------------------------------------------------------------
# POST /analyze-paths
# ---------------------------------------------------------------------------

@router.post("/analyze-paths")
def analyze_paths(path1: str, path2: str, background_tasks: BackgroundTasks):
    if not os.path.exists(path1):
        raise HTTPException(status_code=404, detail=f"File not found: {path1}")
    if not os.path.exists(path2):
        raise HTTPException(status_code=404, detail=f"File not found: {path2}")

    with _paths_lock:
        _allowed_stream_paths.add(os.path.realpath(path1))
        _allowed_stream_paths.add(os.path.realpath(path2))

    job_id = str(uuid.uuid4())
    db.create_job(job_id)
    _spawn_task("analyze_paths", path1, path2, job_id)
    return {
        "status": "analysis_started",
        "job_id": job_id,
        "file1": os.path.basename(path1),
        "file2": os.path.basename(path2),
    }


def analyze_paths_task(f1: str, f2: str, job_id: str):
    url1 = f"/api/v1/stream_file?path={f1}"
    url2 = f"/api/v1/stream_file?path={f2}"

    ep_a_id = db.upsert_episode(url1)
    ep_b_id = db.upsert_episode(url2)
    db.set_audio_file(ep_a_id, f1)
    db.set_audio_file(ep_b_id, f2)

    try:
        segs = _run_analysis(ep_a_id, ep_b_id, f1, f2)
        db.update_job(job_id, 'done', episode_urls=[url1, url2],
                      message=f'Found {len(segs)} segment(s)')
    except Exception as e:
        print(f"Path analysis failed: {e}")
        db.update_job(job_id, 'error', message='Analysis failed. Check server logs.')


# ---------------------------------------------------------------------------
# POST /reanalyze-local
# ---------------------------------------------------------------------------

@router.post("/reanalyze-local")
def reanalyze_local(background_tasks: BackgroundTasks):
    """
    Re-run the full analysis pipeline on every podcast that has locally cached
    audio files, using cached fingerprints.  Clears existing raw_matches /
    canonical_segments / match_log first so the improved algorithm starts fresh.
    """
    podcast_ids = db.get_all_podcast_ids()
    if not podcast_ids:
        raise HTTPException(status_code=404, detail="No locally cached podcasts found.")

    job_id = str(uuid.uuid4())
    db.create_job(job_id)
    _spawn_task("reanalyze_local", podcast_ids, job_id)
    return {"status": "analysis_started", "job_id": job_id, "podcast_count": len(podcast_ids)}


def reanalyze_local_task(podcast_ids: list, job_id: str):
    total_new_segs   = 0
    total_promoted   = 0
    total_tmpl       = 0
    total_podcasts   = len(podcast_ids)

    try:
        for p_num, podcast_id in enumerate(podcast_ids):
            db.update_job(
                job_id, 'running',
                message=f'Podcast {p_num + 1}/{total_podcasts}: clearing old analysis…',
            )
            cleared = db.clear_analysis_for_podcast(podcast_id)
            logger.info("Cleared for %s: %s", podcast_id, cleared)

            episodes = db.get_local_episodes_for_podcast(podcast_id)
            if len(episodes) < 2:
                continue

            ep_ids     = [e['id']    for e in episodes]
            titles     = [e['title'] for e in episodes]
            paths      = [e['local_path'] for e in episodes]
            total_eps  = len(episodes)

            # Load all fingerprints from DB (no audio re-processing)
            db.update_job(
                job_id, 'running',
                message=f'Podcast {p_num + 1}/{total_podcasts}: loading {total_eps} fingerprint sets…',
            )
            all_hashes = []
            for ep_id in ep_ids:
                fp = db.get_fingerprints(ep_id)
                all_hashes.append(fp if fp else None)

            # Build comparison pairs: star topology + consecutive window
            ref_indices = _select_ref_indices(titles)
            pairs: set = set()
            for ref_i in ref_indices:
                for i in range(total_eps):
                    if i != ref_i and all_hashes[i] and all_hashes[ref_i]:
                        pairs.add((min(ref_i, i), max(ref_i, i)))
            for i in range(total_eps - 1):
                if all_hashes[i] and all_hashes[i + 1]:
                    pairs.add((i, i + 1))

            ordered_pairs = sorted(pairs)
            n_pairs = len(ordered_pairs)
            db.update_job(
                job_id, 'running',
                message=f'Podcast {p_num + 1}/{total_podcasts}: matching {n_pairs} pairs…',
            )

            for pair_num, (i, j) in enumerate(ordered_pairs):
                if pair_num % 20 == 0:
                    db.update_job(
                        job_id, 'running',
                        message=(
                            f'Podcast {p_num + 1}/{total_podcasts}: '
                            f'pair {pair_num + 1}/{n_pairs} — '
                            f'{(titles[i] or "")[:30]} vs {(titles[j] or "")[:30]}'
                        ),
                    )
                try:
                    segs = _run_analysis(
                        ep_ids[i], ep_ids[j], paths[i], paths[j],
                        podcast_id=None,
                        hashes_a=all_hashes[i],
                        hashes_b=all_hashes[j],
                    )
                    total_new_segs += len(segs)
                except Exception as e:
                    logger.warning("Pair (%d,%d) failed: %s", i, j, e)

            promoted, _ = db.try_promote_clusters(podcast_id)
            _invalidate_template_cache(podcast_id)
            total_promoted += promoted

            db.update_job(
                job_id, 'running',
                message=f'Podcast {p_num + 1}/{total_podcasts}: running template sweep…',
            )
            new_tmpl = sweep_canonical_templates(podcast_id, ep_ids, all_hashes)
            total_tmpl += new_tmpl

            for ep_id, path in zip(ep_ids, paths):
                try:
                    db.refine_episode_segment_boundaries(ep_id, path)
                except Exception as e:
                    logger.warning("Boundary refinement failed for ep%d: %s", ep_id, e)
                db.mark_audio_processing_complete(ep_id)

        db.evict_audio_files()
        db.update_job(
            job_id, 'done',
            message=(
                f'Done. {total_podcasts} podcast(s), {total_new_segs} raw segments, '
                f'{total_promoted} canonical segments promoted, '
                f'{total_tmpl} template match(es).'
            ),
        )
    except Exception as e:
        logger.exception("reanalyze_local_task failed")
        db.update_job(job_id, 'error', message=f'Re-analysis failed: {e}')


# ---------------------------------------------------------------------------
# POST /re-analyze-episodes
# ---------------------------------------------------------------------------

@router.post("/re-analyze-episodes")
def re_analyze_episodes(ep1_url: str, ep2_url: str, background_tasks: BackgroundTasks):
    ep1 = db.get_episode_by_url(ep1_url)
    ep2 = db.get_episode_by_url(ep2_url)
    if not ep1:
        raise HTTPException(status_code=404, detail=f"Episode not in DB: {ep1_url}")
    if not ep2:
        raise HTTPException(status_code=404, detail=f"Episode not in DB: {ep2_url}")

    f1 = db.get_audio_file_path(ep1['id'])
    f2 = db.get_audio_file_path(ep2['id'])
    if not f1 or not os.path.exists(f1):
        raise HTTPException(status_code=404, detail="Local audio not found for episode 1. Download it first.")
    if not f2 or not os.path.exists(f2):
        raise HTTPException(status_code=404, detail="Local audio not found for episode 2. Download it first.")

    job_id = str(uuid.uuid4())
    db.create_job(job_id)
    _spawn_task(
        "re_analyze_episodes", ep1['id'], ep2['id'], f1, f2, job_id, ep1_url
    )
    return {"status": "analysis_started", "job_id": job_id}


def _re_analyze_episodes_task(ep_a_id: int, ep_b_id: int, f1: str, f2: str,
                               job_id: str, primary_url: str):
    try:
        segs = _run_analysis(ep_a_id, ep_b_id, f1, f2)

        # Promote any new clusters, then sweep all episodes in the same podcast
        # against all canonical templates — this backfills sub-show episodes and
        # any episode that was previously unmatched.
        ep_a = db.get_episode_by_id(ep_a_id)
        podcast_id = ep_a.get('podcast_id') if ep_a else None
        tmpl_suffix = ''
        if podcast_id:
            db.try_promote_clusters(podcast_id)
            _invalidate_template_cache(podcast_id)
            all_ep_ids = db.get_episode_ids_for_podcast(podcast_id)
            all_hashes = [db.get_fingerprints(ep_id) or None for ep_id in all_ep_ids]
            new_mappings = sweep_canonical_templates(podcast_id, all_ep_ids, all_hashes)
            if new_mappings:
                tmpl_suffix = f', {new_mappings} template match(es) across podcast'

        db.update_job(job_id, 'done',
                      episode_urls=[primary_url],
                      message=f'Re-analysis complete: {len(segs)} segment(s) found{tmpl_suffix}')
    except Exception as e:
        logger.exception("Re-analyze failed")
        db.update_job(job_id, 'error', message=f'Re-analysis failed: {e}')


# ---------------------------------------------------------------------------
# GET /stream_file
# ---------------------------------------------------------------------------

@router.get("/stream_file")
def stream_file(path: str):
    abs_path    = os.path.realpath(path)
    allowed_dir = os.path.realpath(DOWNLOADS_DIR)
    in_downloads = abs_path.startswith(allowed_dir + os.sep)
    with _paths_lock:
        user_submitted = abs_path in _allowed_stream_paths
    if not in_downloads and not user_submitted:
        raise HTTPException(status_code=403, detail="Access denied")
    if not os.path.exists(abs_path):
        raise HTTPException(status_code=404, detail="File not found")
    return FileResponse(abs_path)


# ---------------------------------------------------------------------------
# Catalog / inventory management endpoints
# ---------------------------------------------------------------------------

@router.get("/search/podcast")
def search_podcast(name: str):
    """Search for podcasts by name, returns RSS URL candidates ranked by episode count."""
    results = db.search_podcast_by_name(name)
    return {"results": results}


@router.get("/catalog/stats")
def get_catalog_stats(podcast_id: str = None):
    """Aggregate stats for the catalog dashboard."""
    return db.get_catalog_stats(podcast_id)


@router.get("/catalog/podcasts")
def get_catalog_podcasts():
    """List all podcasts that have canonical segments."""
    return db.get_all_podcasts()


@router.delete("/canonical/{segment_id}")
def delete_canonical_segment(segment_id: int):
    """Delete a canonical segment and all its episode_segments."""
    success = db.delete_canonical_segment(segment_id)
    if not success:
        raise HTTPException(status_code=404, detail="Segment not found")
    return {"status": "deleted", "segment_id": segment_id}


@router.delete("/catalog/segments")
def bulk_delete_segments(podcast_id: str, segment_type: str = None):
    """Delete all canonical segments for a podcast, optionally filtered by type."""
    if segment_type and segment_type not in ('intro', 'outro', 'ad'):
        raise HTTPException(status_code=400, detail="segment_type must be intro, outro, or ad")
    count = db.delete_canonical_segments_bulk(podcast_id, segment_type)
    return {"status": "deleted", "count": count}


@router.patch("/canonical/{segment_id}/type")
def update_canonical_type(segment_id: int, segment_type: str):
    """Relabel a canonical segment's type (intro/outro/ad)."""
    if segment_type not in ('intro', 'outro', 'ad'):
        raise HTTPException(status_code=400, detail="segment_type must be intro, outro, or ad")
    success = db.update_canonical_segment_type(segment_id, segment_type)
    if not success:
        raise HTTPException(status_code=404, detail="Segment not found")
    return {"status": "updated", "segment_id": segment_id, "segment_type": segment_type}


# ---------------------------------------------------------------------------
# Debug endpoints
# ---------------------------------------------------------------------------

@router.get("/episodes")
def list_episodes(limit: int = 200, offset: int = 0):
    """DB episodes with per-episode segment counts, paginated. Used by the Stub Player tab."""
    return db.get_episodes_with_segment_counts(limit=limit, offset=offset)


@router.get("/debug/episodes")
def debug_episodes(podcast_id: str = None):
    return db.get_all_episodes(podcast_id)


@router.get("/debug/matches")
def debug_matches(episode_url: str):
    ep = db.get_episode_by_url(episode_url)
    if not ep:
        raise HTTPException(status_code=404, detail="Episode not found")
    return db.get_raw_matches_for_episode(ep['id'])


@router.get("/debug/fingerprint-log")
def debug_fingerprint_log(episode_url: str = None):
    ep_id = None
    if episode_url:
        ep = db.get_episode_by_url(episode_url)
        if ep:
            ep_id = ep['id']
    return db.get_fingerprint_log(ep_id)


@router.post("/events", response_model=ClientEventsResponse)
def post_client_events(req: ClientEventsRequest):
    """Accept a batch of skip events from a mobile client. Dedupes on (client_id, client_event_id)
    so retries are safe. Mobile keeps the original rows locally; only the high-water mark advances."""
    if not req.client_id:
        raise HTTPException(status_code=400, detail="client_id required")
    if len(req.events) == 0:
        return ClientEventsResponse(accepted=0, duplicates=0)
    if len(req.events) > 500:
        raise HTTPException(status_code=413, detail="batch too large (max 500)")
    accepted = 0
    duplicates = 0
    with get_db() as conn:
        for ev in req.events:
            cur = conn.execute("""
                INSERT INTO client_events
                    (client_id, client_event_id, skip_type, duration_ms,
                     episode_guid, episode_url, podcast_rss, client_ts)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (client_id, client_event_id) DO NOTHING
            """, (req.client_id, ev.client_event_id, ev.skip_type, ev.duration_ms,
                  ev.episode_guid, ev.episode_url, ev.podcast_rss, ev.client_ts))
            if cur.rowcount == 1:
                accepted += 1
            else:
                duplicates += 1
    return ClientEventsResponse(accepted=accepted, duplicates=duplicates)

