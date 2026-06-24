package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.media.audiofx.LoudnessEnhancer;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.util.Consumer;

import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.AudioAttributes;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelectionArray;

import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.mp3.Mp3Extractor;
import androidx.media3.ui.DefaultTrackNameProvider;
import androidx.media3.ui.TrackNameProvider;
import de.danoeh.antennapod.net.common.UserAgentInterceptor;
import de.danoeh.antennapod.model.feed.VolumeAdaptionSetting;
import de.danoeh.antennapod.playback.service.R;
import de.danoeh.antennapod.net.common.HttpCredentialEncoder;
import de.danoeh.antennapod.net.common.NetworkUtils;
import de.danoeh.antennapod.model.playback.Playable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import de.danoeh.antennapod.playback.service.trim.StreamingCache;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

@OptIn(markerClass = UnstableApi.class)
public class ExoPlayerWrapper {
    public static final int BUFFERING_STARTED = -1;
    public static final int BUFFERING_ENDED = -2;
    private static final String TAG = "ExoPlayerWrapper";

    // Transient-network-error auto-recovery: how many re-prepare attempts before we give up and
    // surface a fatal error, plus the exponential-backoff bounds. The cap is deliberately small (5s):
    // a restored connection short-circuits the timer and retries immediately (see the NetworkCallback
    // in registerConnectivityCallback), so the cap only governs blind polling while the OS still
    // reports no usable network — and a 5s poll resumes far faster than the old 30s after a server-
    // side stall the connectivity callback can't see. The attempt budget is sized to span a realistic
    // dead-zone: 60 attempts × ≤5s ≈ 5 minutes before we give up.
    private static final int MAX_NETWORK_RECOVERY_ATTEMPTS = 60;
    private static final long RECOVERY_BASE_DELAY_MS = 1000;
    private static final long RECOVERY_MAX_DELAY_MS = 5_000;

    // HTTP + load resilience for streaming. media3's defaults give up far too quickly for
    // mobile: 8s connect/read timeouts and only ~3 load retries, so a brief cellular stall
    // escalates to a FATAL player error — which stops playback regardless of how much audio
    // is buffered ahead — before the buffer can cover the gap. We widen the timeouts and make
    // the loader patient so transient drops are ridden out from buffer (background retries)
    // instead of stopping playback. A genuinely dead load still surfaces once the retry budget
    // is spent, where NetworkRecoveryController takes over with its re-prepare path.
    private static final int HTTP_CONNECT_TIMEOUT_MS = 30_000;
    private static final int HTTP_READ_TIMEOUT_MS = 30_000;
    private static final int MAX_LOAD_RETRIES = 30;
    // Small cap so the background loader re-attempts a load ~every 5s once it's ramped, instead of
    // sitting idle for 30s after connectivity has already returned (the loader's retries are internal
    // to media3 and can't be triggered by our NetworkCallback, so a tight cap is the lever here).
    private static final long LOAD_RETRY_MAX_DELAY_MS = 5_000;

    // Seek-collapse guard. A forward seek on a progressive stream into a region the source can't serve
    // (offline, or past the downloaded frontier) does NOT raise an error — ExoPlayer's
    // ProgressiveMediaPeriod silently resolves it to ~0, restarting the episode from the start. When a
    // sizeable forward seek lands near 0 like that, we bounce once back to where we seeked from (still
    // in the retained back-buffer) instead of accepting the restart. An online seek reports its real
    // target position and never trips this.
    private static final long SEEK_COLLAPSE_MIN_FORWARD_MS = 30_000; // only guard sizeable forward seeks
    private static final long SEEK_COLLAPSE_NEAR_ZERO_MS = 3_000;    // landed this close to start = collapsed

    private final Context context;
    private final Disposable bufferingUpdateDisposable;
    private ExoPlayer exoPlayer;
    private MediaSource mediaSource;
    private Runnable audioSeekCompleteListener;
    private Runnable audioCompletionListener;
    private Consumer<String> audioErrorListener;
    private Consumer<Integer> bufferingUpdateListener;
    private PlaybackParameters playbackParameters;
    private DefaultTrackSelector trackSelector;
    private SimpleCache simpleCache;
    @Nullable
    private LoudnessEnhancer loudnessEnhancer = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // True while a speed-change pause-window is active and we owe the player a play() once
    // the new params are applied. Lets rapid taps reuse the same pause without double-pausing.
    private boolean wasPlayingBeforeSpeedChange = false;
    private Runnable pendingSpeedApply = null;
    // Known-good position captured when the speed-change pause begins (player still playing), used as
    // a fallback if the debounced flush-seek re-reads the live position as 0 during a transient state.
    private long positionBeforeSpeedChange = 0;
    // True while a seekTo() triggered internally by setPlaybackParams is in flight.
    // onPositionDiscontinuity uses this to apply the new speed + resume after the
    // seek-triggered AudioSink.flush() is confirmed complete, and to suppress the
    // external seek-complete listener for this internal seek.
    private boolean speedChangeSeeking = false;
    // skipSilence value captured at setPlaybackParams call-time for use in onPositionDiscontinuity.
    private boolean pendingSkipSilence = false;
    // True while the current media source is an HTTP stream (vs a local file). Only streams are
    // eligible for transient-network-error auto-recovery; a local-file IO error is not retried.
    private boolean isHttpSource = false;
    // Auto-recovery for transient network errors mid-stream. Instead of surfacing a fatal error
    // (which stops playback until the user manually restarts), we re-prepare from the position
    // captured before the outage with capped backoff so playback resumes on its own once
    // connectivity returns. The state machine lives in a pure, unit-tested controller; this wrapper
    // only adapts it to the real ExoPlayer + main-thread Handler.
    private final NetworkRecoveryController networkRecovery = new NetworkRecoveryController(
            new NetworkRecoveryController.Player() {
                @Override
                public long getCurrentPositionMs() {
                    return exoPlayer.getCurrentPosition();
                }

                @Override
                public void prepare() {
                    exoPlayer.prepare();
                }

                @Override
                public void seekTo(long positionMs) {
                    // Mark this as a recovery restore seek so onPositionDiscontinuity can verify it
                    // landed (vs. collapsing to ~0) and report back to the controller.
                    recoveryRestoreTargetMs = positionMs;
                    exoPlayer.seekTo(positionMs);
                }
            },
            new NetworkRecoveryController.Host() {
                @Override
                public void scheduleRetry(long delayMs, Runnable retry) {
                    if (pendingNetworkRecovery != null) {
                        mainHandler.removeCallbacks(pendingNetworkRecovery);
                    }
                    pendingNetworkRecovery = retry;
                    mainHandler.postDelayed(retry, delayMs);
                }

                @Override
                public void cancelScheduled() {
                    if (pendingNetworkRecovery != null) {
                        mainHandler.removeCallbacks(pendingNetworkRecovery);
                        pendingNetworkRecovery = null;
                    }
                }

                @Override
                public void onBuffering() {
                    // Keep the UI in a "buffering" state rather than letting it look stalled/stopped.
                    if (bufferingUpdateListener != null) {
                        bufferingUpdateListener.accept(BUFFERING_STARTED);
                    }
                }

                @Override
                public void onExhausted() {
                    Log.w(TAG, "Network recovery exhausted after "
                            + (recoveryStartMs == 0 ? "?" : (SystemClock.elapsedRealtime() - recoveryStartMs))
                            + "ms; surfacing error");
                    recoveryStartMs = 0;
                    if (lastNetworkError != null) {
                        dispatchError(lastNetworkError);
                    }
                }
            },
            MAX_NETWORK_RECOVERY_ATTEMPTS, RECOVERY_BASE_DELAY_MS, RECOVERY_MAX_DELAY_MS);
    private Runnable pendingNetworkRecovery = null;
    // Last recoverable error seen, dispatched if the recovery budget is exhausted.
    private PlaybackException lastNetworkError = null;
    // elapsedRealtime when the current recovery sequence began, or 0 when not recovering. Used purely
    // to measure (and log) the user-visible stall: error→connectivity-restored→resumed-to-READY.
    private long recoveryStartMs = 0;
    // Seek-collapse guard state, set on each public seekTo and consumed by the next seek discontinuity.
    private long lastSeekTargetMs = C.TIME_UNSET;
    private long lastSeekFromMs = 0;
    private boolean seekCollapseGuardArmed = false;
    // Target of an in-flight recovery restore seek (set by the controller's Player.seekTo), so the next
    // seek discontinuity can verify it landed vs. collapsed to ~0 and tell the controller which.
    private long recoveryRestoreTargetMs = C.TIME_UNSET;
    // Set once release() runs so a connectivity callback already posted to the main thread becomes a
    // no-op instead of touching a released ExoPlayer.
    private volatile boolean released = false;
    // Registered once for the wrapper's lifetime; lets a restored connection short-circuit the
    // recovery backoff timer for a near-instant resume. Null when registration failed/was removed.
    private ConnectivityManager.NetworkCallback networkCallback = null;

    ExoPlayerWrapper(Context context) {
        this.context = context;
        createPlayer();
        registerConnectivityCallback();
        playbackParameters = exoPlayer.getPlaybackParameters();
        bufferingUpdateDisposable = Observable.interval(2, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(tickNumber -> {
                    if (bufferingUpdateListener != null) {
                        bufferingUpdateListener.accept(exoPlayer.getBufferedPercentage());
                    }
                });
    }

    private void createPlayer() {
        DefaultLoadControl.Builder loadControl = new DefaultLoadControl.Builder();
        loadControl.setBufferDurationsMs((int) TimeUnit.HOURS.toMillis(1), (int) TimeUnit.HOURS.toMillis(3),
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS);
        // The durations above are only a high time ceiling — they do NOT bound the buffer. media3
        // gates loading by bytes OR time, whichever is reached first, and with
        // prioritizeTimeOverSizeThresholds left at its default (false) the BYTE cap wins. The default
        // audio cap (DefaultLoadControl.DEFAULT_AUDIO_BUFFER_SIZE ≈ 12.5 MB) silently overrode the
        // "1 hour" intent, leaving only ~6–25 min of real headroom (bitrate-dependent) — and a drop
        // that outlasts the buffered-ahead audio stalls. Raise the byte cap so the buffer can get far
        // enough ahead to ride out an outage from buffer. We deliberately keep
        // prioritizeTimeOverSizeThresholds=false: setting it true removes the byte ceiling entirely,
        // and a 1–3 hour in-memory media buffer would risk OOM on low-end (minSdk 23) devices and
        // video podcasts. A fixed byte cap self-limits for any bitrate / media type (≈ 16–32 min of
        // audio; far less for video).
        loadControl.setTargetBufferBytes(32 * 1024 * 1024);
        loadControl.setBackBuffer((int) TimeUnit.MINUTES.toMillis(5), true);
        trackSelector = new DefaultTrackSelector(context);
        exoPlayer = new ExoPlayer.Builder(context, new DefaultRenderersFactory(context))
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl.build())
                .build();
        exoPlayer.setSeekParameters(SeekParameters.EXACT);
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(@Player.State int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    if (recoveryStartMs != 0) {
                        Log.i(TAG, "Network recovery: resumed to READY after "
                                + (SystemClock.elapsedRealtime() - recoveryStartMs) + "ms");
                        recoveryStartMs = 0;
                    }
                    // A successful (re)buffer to READY confirms any in-flight network recovery
                    // worked, so the next outage gets a fresh full backoff budget.
                    networkRecovery.onPlayerReady();
                }
                if (audioCompletionListener != null && playbackState == Player.STATE_ENDED) {
                    audioCompletionListener.run();
                } else if (bufferingUpdateListener != null && playbackState == Player.STATE_BUFFERING) {
                    bufferingUpdateListener.accept(BUFFERING_STARTED);
                } else if (bufferingUpdateListener != null) {
                    bufferingUpdateListener.accept(BUFFERING_ENDED);
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.w(TAG, "onPlayerError: " + error.getErrorCodeName() + " (" + error.errorCode + ")", error);
                // A transient network drop mid-stream should not kill playback: re-prepare from the
                // retained position with backoff and let it resume when connectivity returns. Only
                // surface the error once recovery is exhausted (or the error isn't network-related).
                if (isHttpSource && !NetworkUtils.wasDownloadBlocked(error)
                        && isRecoverableNetworkError(error)) {
                    lastNetworkError = error;
                    if (recoveryStartMs == 0) {
                        recoveryStartMs = SystemClock.elapsedRealtime();
                        Log.i(TAG, "Network recovery: started (error=" + error.getErrorCodeName() + ")");
                    }
                    networkRecovery.onRecoverableError();
                    return;
                }
                networkRecovery.reset();
                dispatchError(error);
            }

            @Override
            public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition,
                                                @NonNull Player.PositionInfo newPosition,
                                                @Player.DiscontinuityReason int reason) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    // A public/user seek that silently resolved to ~the start must be bounced back
                    // BEFORE the speed-dance and recovery branches below can consume this
                    // discontinuity and swallow the collapse (the speed branch even disarms the
                    // guard). In the car a forward seek into an un-cached/un-reachable region
                    // collapses to 0 while one of those is active, and the cached prefix then plays
                    // on from 0 seamlessly — the user sees the episode "jump to the start". Restore
                    // the pre-seek position first; the guard is disarmed so a re-collapse of the
                    // bounce is accepted rather than looping.
                    if (seekCollapseGuardArmed && isCollapsedForwardSeek(newPosition.positionMs)) {
                        seekCollapseGuardArmed = false;
                        Log.w(TAG, "Seek to " + lastSeekTargetMs + " collapsed to "
                                + newPosition.positionMs + " — stream couldn't serve it; restoring to "
                                + lastSeekFromMs);
                        exoPlayer.seekTo(lastSeekFromMs);
                        return;
                    }
                    if (speedChangeSeeking) {
                        // The flush-seek is confirmed complete by ExoPlayer — AudioSink.flush()
                        // has already run, SonicAudioProcessor's stale-sample buffer is clear.
                        // Now it is safe to set the new speed and resume.
                        Log.d(TAG, "speed-change dance: seek complete, applying speed. wasPlayingBeforeSpeedChange="
                                + wasPlayingBeforeSpeedChange + " playWhenReady=" + exoPlayer.getPlayWhenReady());
                        speedChangeSeeking = false;
                        seekCollapseGuardArmed = false; // not a user seek; don't let it trip the guard
                        applySpeedAndSilence(pendingSkipSilence);
                        if (wasPlayingBeforeSpeedChange) {
                            wasPlayingBeforeSpeedChange = false;
                            Log.d(TAG, "speed-change dance: calling exoPlayer.play()");
                            exoPlayer.play();
                        }
                        return;
                    }
                    if (recoveryRestoreTargetMs != C.TIME_UNSET) {
                        long target = recoveryRestoreTargetMs;
                        recoveryRestoreTargetMs = C.TIME_UNSET;
                        if (target > SEEK_COLLAPSE_NEAR_ZERO_MS
                                && newPosition.positionMs < SEEK_COLLAPSE_NEAR_ZERO_MS) {
                            // The restore seek itself collapsed (target still un-servable). Don't accept
                            // the restart-from-0: keep recovering so the next ready retries the restore.
                            Log.w(TAG, "Recovery restore seek to " + target + " collapsed to "
                                    + newPosition.positionMs + " — keeping recovery alive");
                            networkRecovery.onRestoreSeekCollapsed();
                            return;
                        }
                        // Landed where asked — recovery is truly complete.
                        networkRecovery.onRestoreSeekSucceeded();
                        if (audioSeekCompleteListener != null) {
                            audioSeekCompleteListener.run();
                        }
                        return;
                    }
                    // Any genuine forward-seek collapse was already bounced at the top of this
                    // block; reaching here means the seek held (or wasn't an armed forward seek),
                    // so just disarm the guard before firing the seek-complete listener.
                    seekCollapseGuardArmed = false;
                    if (audioSeekCompleteListener != null) {
                        audioSeekCompleteListener.run();
                    }
                }
            }

            @Override
            public void onAudioSessionIdChanged(int audioSessionId) {
                initLoudnessEnhancer(audioSessionId);
            }
        });
        // Shared, persistent on-disk cache (singleton). Reused by the queue prefetcher so a
        // prefetched episode prefix is served from disk here. Not released on player teardown —
        // it lives for the process lifetime and its index survives app/device restarts.
        simpleCache = StreamingCache.getInstance(context);
        initLoudnessEnhancer(exoPlayer.getAudioSessionId());
    }

    /**
     * Watch for the OS reporting a usable network again. When connectivity returns mid-recovery the
     * callback retries the re-prepare immediately instead of waiting out the remaining backoff delay,
     * turning an up-to-{@link #RECOVERY_MAX_DELAY_MS} silent gap into a near-instant resume. The
     * callback marshals onto the main thread so all {@link NetworkRecoveryController} state is touched
     * from one thread, and is a no-op when no recovery is in flight. Registered once for the wrapper's
     * lifetime (survives reset(), which recreates the player but keeps networkRecovery); unregistered
     * in release().
     */
    private void registerConnectivityCallback() {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return;
        }
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                mainHandler.post(() -> {
                    if (released) {
                        return; // player torn down between the callback firing and this running
                    }
                    if (recoveryStartMs != 0) {
                        Log.i(TAG, "Network recovery: connectivity restored after "
                                + (SystemClock.elapsedRealtime() - recoveryStartMs)
                                + "ms — retrying immediately");
                    }
                    networkRecovery.onConnectivityRestored();
                });
            }
        };
        try {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            cm.registerNetworkCallback(request, networkCallback);
        } catch (RuntimeException e) {
            // Missing ACCESS_NETWORK_STATE or a transient framework failure: fall back to the
            // (still functional) timer-based recovery.
            Log.w(TAG, "Could not register network callback for fast recovery", e);
            networkCallback = null;
        }
    }

    private void unregisterConnectivityCallback() {
        if (networkCallback == null) {
            return;
        }
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not unregister network callback", e);
            }
        }
        networkCallback = null;
    }

    public int getCurrentPosition() {
        return (int) exoPlayer.getCurrentPosition();
    }

    public float getCurrentSpeedMultiplier() {
        return playbackParameters.speed;
    }

    public boolean getCurrentSkipSilence() {
        return exoPlayer.getSkipSilenceEnabled();
    }

    public void setSkipSilenceEnabled(boolean skipSilence) {
        if (exoPlayer.getSkipSilenceEnabled() != skipSilence) {
            exoPlayer.setSkipSilenceEnabled(skipSilence);
        }
        // Keep the speed-change dance's captured value in sync so an in-flight
        // dance doesn't re-apply a stale value when it finally completes.
        pendingSkipSilence = skipSilence;
    }

    public int getDuration() {
        if (exoPlayer.getDuration() == C.TIME_UNSET) {
            return Playable.INVALID_TIME;
        }
        return (int) exoPlayer.getDuration();
    }

    public boolean isPlaying() {
        return exoPlayer.getPlayWhenReady();
    }

    public void pause() {
        Log.d(TAG, "pause() called. exoPlayer.isPlaying=" + exoPlayer.isPlaying()
                + " playWhenReady=" + exoPlayer.getPlayWhenReady()
                + " wasPlayingBeforeSpeedChange=" + wasPlayingBeforeSpeedChange
                + " speedChangeSeeking=" + speedChangeSeeking
                + " pendingSpeedApply=" + (pendingSpeedApply != null), new Throwable("pause-caller"));
        exoPlayer.pause();
    }

    public void prepare() throws IllegalStateException {
        exoPlayer.setMediaSource(mediaSource, false);
        exoPlayer.prepare();
    }

    public void release() {
        released = true;
        unregisterConnectivityCallback();
        bufferingUpdateDisposable.dispose();
        if (loudnessEnhancer != null) {
            loudnessEnhancer.release();
            loudnessEnhancer = null;
        }
        if (exoPlayer != null) {
            exoPlayer.release();
        }
        // simpleCache is a shared singleton; do not release it here.
        cancelPendingSpeedChange();
        networkRecovery.reset();
        recoveryStartMs = 0;
        recoveryRestoreTargetMs = C.TIME_UNSET;
        audioSeekCompleteListener = null;
        audioCompletionListener = null;
        audioErrorListener = null;
        bufferingUpdateListener = null;
    }

    public void reset() {
        cancelPendingSpeedChange();
        networkRecovery.reset();
        recoveryStartMs = 0;
        recoveryRestoreTargetMs = C.TIME_UNSET;
        bufferingUpdateDisposable.dispose();
        if (loudnessEnhancer != null) {
            loudnessEnhancer.release();
            loudnessEnhancer = null;
        }
        exoPlayer.release();
        // simpleCache is a shared singleton; do not release it here. createPlayer() re-points
        // the field at the same singleton instance.
        createPlayer();
    }

    private void cancelPendingSpeedChange() {
        if (pendingSpeedApply != null) {
            mainHandler.removeCallbacks(pendingSpeedApply);
            pendingSpeedApply = null;
        }
        wasPlayingBeforeSpeedChange = false;
        speedChangeSeeking = false;
    }

    /**
     * Whether a {@link PlaybackException} looks like a recoverable transient network fault (the
     * connection dropped or timed out), as opposed to a permanent failure (bad HTTP status, parsing
     * error, unsupported format) where re-preparing would just fail again.
     */
    private static boolean isRecoverableNetworkError(PlaybackException error) {
        switch (error.errorCode) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                // Note: deliberately NOT ERROR_CODE_IO_UNSPECIFIED — that bucket catches
                // non-network IO faults (including a seek/range failure) that re-preparing
                // would only retry uselessly.
                return true;
            default:
                return false;
        }
    }

    /** Surfaces a player error to the registered listener (the non-recoverable path). */
    private void dispatchError(PlaybackException error) {
        if (audioErrorListener == null) {
            return;
        }
        if (NetworkUtils.wasDownloadBlocked(error)) {
            audioErrorListener.accept(context.getString(R.string.download_error_blocked));
            return;
        }
        Throwable cause = error.getCause();
        if (cause instanceof HttpDataSource.HttpDataSourceException) {
            if (cause.getCause() != null) {
                cause = cause.getCause();
            }
        }
        if (cause != null && "Source error".equals(cause.getMessage())) {
            cause = cause.getCause();
        }
        if (cause != null && cause.getMessage() != null) {
            audioErrorListener.accept(cause.getMessage());
        } else if (error.getMessage() != null && cause != null) {
            audioErrorListener.accept(error.getMessage() + ": " + cause.getClass().getSimpleName());
        } else {
            audioErrorListener.accept(null);
        }
    }

    /**
     * True when an armed public/user seek silently resolved to ~the start: a sizeable forward seek
     * (target well past where we were) that landed near 0 from a non-start position. That is how an
     * un-servable progressive seek manifests — the target is past the cached/buffered frontier on a
     * flaky connection, so ExoPlayer reports the seek complete but restarts the source at 0. The
     * cached prefix then plays on seamlessly, so without bouncing back the user just sees the episode
     * jump to the start.
     */
    private boolean isCollapsedForwardSeek(long landedMs) {
        return lastSeekTargetMs != C.TIME_UNSET
                && lastSeekTargetMs > lastSeekFromMs + SEEK_COLLAPSE_MIN_FORWARD_MS
                && landedMs < SEEK_COLLAPSE_NEAR_ZERO_MS
                && lastSeekFromMs > SEEK_COLLAPSE_NEAR_ZERO_MS;
    }

    public void seekTo(int i) throws IllegalStateException {
        // Arm the seek-collapse guard: snapshot where we are now so we can bounce back if this
        // forward seek silently resolves to ~0 (an un-servable progressive seek; see the constants).
        lastSeekFromMs = exoPlayer.getCurrentPosition();
        lastSeekTargetMs = i;
        seekCollapseGuardArmed = true;
        exoPlayer.seekTo(i);
        // Do NOT call audioSeekCompleteListener here — ExoPlayer fires onPositionDiscontinuity
        // (DISCONTINUITY_REASON_SEEK) on the main thread when the seek is actually complete.
        // Calling the listener synchronously before ExoPlayer processes the seek causes the
        // player state to be restored to PLAYING prematurely, which lets a concurrent speed-change
        // debounce fire setPlaybackParameters while a seek is still in-flight, interrupting the
        // seek callback delivery and leaving the player stuck in SEEKING state.
    }

    public void setAudioStreamType(int i) {
        AudioAttributes a = exoPlayer.getAudioAttributes();
        AudioAttributes.Builder b = new AudioAttributes.Builder();
        b.setContentType(i);
        b.setFlags(a.flags);
        b.setUsage(a.usage);
        exoPlayer.setAudioAttributes(b.build(), false);
    }

    public void setDataSource(String s, String user, String password)
            throws IllegalArgumentException, IllegalStateException {
        Log.d(TAG, "setDataSource: " + s);
        final DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory();
        httpDataSourceFactory.setUserAgent(UserAgentInterceptor.USER_AGENT);
        httpDataSourceFactory.setAllowCrossProtocolRedirects(true);
        httpDataSourceFactory.setKeepPostFor302Redirects(true);
        // Don't kill a slow-but-alive mobile read at the 8s default; give it room to recover.
        httpDataSourceFactory.setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS);
        httpDataSourceFactory.setReadTimeoutMs(HTTP_READ_TIMEOUT_MS);

        if (!TextUtils.isEmpty(user) && !TextUtils.isEmpty(password)) {
            final HashMap<String, String> requestProperties = new HashMap<>();
            requestProperties.put("Authorization", HttpCredentialEncoder.encode(user, password, "ISO-8859-1"));
            httpDataSourceFactory.setDefaultRequestProperties(requestProperties);
        }
        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context, httpDataSourceFactory);
        isHttpSource = s.startsWith("http");
        // A new source means any recovery in flight for the previous one is moot.
        networkRecovery.reset();
        recoveryStartMs = 0;
        recoveryRestoreTargetMs = C.TIME_UNSET;
        if (isHttpSource) {
            dataSourceFactory = new CacheDataSource.Factory()
                    .setCache(simpleCache)
                    .setUpstreamDataSourceFactory(httpDataSourceFactory);
        }
        DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory();
        extractorsFactory.setConstantBitrateSeekingEnabled(true);
        extractorsFactory.setMp3ExtractorFlags(Mp3Extractor.FLAG_DISABLE_ID3_METADATA);
        ProgressiveMediaSource.Factory f = new ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory);
        if (isHttpSource) {
            // Keep retrying transient network loads in the background (instead of failing
            // fatally after media3's default ~3 tries) so playback rides through brief drops
            // from buffer. Permanent faults (parse errors, bad HTTP status) still fail fast —
            // we defer that verdict to the default policy and only extend the patience/backoff.
            f.setLoadErrorHandlingPolicy(patientLoadErrorHandlingPolicy());
        }
        final MediaItem mediaItem = MediaItem.fromUri(Uri.parse(s));
        mediaSource = f.createMediaSource(mediaItem);
    }

    public void setDataSource(String s) throws IllegalArgumentException, IllegalStateException {
        setDataSource(s, null, null);
    }

    /**
     * A {@link LoadErrorHandlingPolicy} that is far more patient with transient network faults
     * than media3's default. The default gives up after ~3 retries with a ≤5s backoff, turning a
     * brief stall into a fatal player error (which stops playback even when audio is buffered
     * ahead). This keeps retrying loads in the background ({@link #MAX_LOAD_RETRIES} times with a
     * capped exponential backoff) so playback continues from buffer and the loader silently
     * catches up when connectivity returns. It does NOT change which errors are permanent — the
     * "don't retry" verdict (parse errors, position-out-of-range, fatal HTTP codes) is delegated
     * to {@link DefaultLoadErrorHandlingPolicy}; we only extend the retry count and backoff.
     */
    @androidx.annotation.VisibleForTesting
    static LoadErrorHandlingPolicy patientLoadErrorHandlingPolicy() {
        return new DefaultLoadErrorHandlingPolicy() {
            @Override
            public int getMinimumLoadableRetryCount(int dataType) {
                return MAX_LOAD_RETRIES;
            }

            @Override
            public long getRetryDelayMsFor(LoadErrorHandlingPolicy.LoadErrorInfo loadErrorInfo) {
                if (super.getRetryDelayMsFor(loadErrorInfo) == C.TIME_UNSET) {
                    // Permanent fault — let it surface instead of retrying uselessly.
                    return C.TIME_UNSET;
                }
                // Capped exponential backoff for transient network faults: 1s, 2s, 4s, then ≤5s.
                long backoff = 1000L << Math.min(loadErrorInfo.errorCount - 1, 5);
                return Math.min(backoff, LOAD_RETRY_MAX_DELAY_MS);
            }
        };
    }

    public void setDisplay(SurfaceHolder sh) {
        exoPlayer.setVideoSurfaceHolder(sh);
    }

    public void setPlaybackParams(float speed, boolean skipSilence) {
        Log.d(TAG, "setPlaybackParams: speed=" + speed + " skipSilence=" + skipSilence
                + " exoPlayer.isPlaying=" + exoPlayer.isPlaying()
                + " wasPlayingBeforeSpeedChange=" + wasPlayingBeforeSpeedChange
                + " speedChangeSeeking=" + speedChangeSeeking);
        playbackParameters = new PlaybackParameters(speed);
        pendingSkipSilence = skipSilence;

        // Cancel any pending apply from rapid taps without resetting wasPlayingBeforeSpeedChange —
        // a prior tap may have already paused the player and we still owe it a play().
        if (pendingSpeedApply != null) {
            mainHandler.removeCallbacks(pendingSpeedApply);
            pendingSpeedApply = null;
        }

        if (!exoPlayer.isPlaying()) {
            if (!wasPlayingBeforeSpeedChange) {
                // Truly not playing and no speed change in progress — apply directly.
                applySpeedAndSilence(skipSilence);
                return;
            }
            // wasPlayingBeforeSpeedChange=true: a prior rapid tap already paused the player.
            // Fall through to re-arm the debounce with the new speed.
        } else {
            // Capture the position while the player is still genuinely playing (known-good), to use
            // as a fallback for the flush-seek below. The debounced seek re-reads the live position
            // 80ms later, and if that read lands during a transient state (buffering after an
            // ad-skip seek, or an in-flight network re-prepare) it can come back as 0 — seeking to
            // 0+1 would restart the episode from the beginning. See NetworkRecoveryController for the
            // sibling case. Snapshot once: on a rapid-tap re-arm the player is already paused, so we
            // keep the original known-good value rather than re-reading a possibly-zero position.
            positionBeforeSpeedChange = exoPlayer.getCurrentPosition();
            // Pause now so no audio renders at the old speed during the debounce window.
            wasPlayingBeforeSpeedChange = true;
            exoPlayer.pause();
        }

        // Debounce rapid taps: wait until the user settles on a speed, then apply.
        //
        // Seek +1ms (not the same position — ExoPlayer may no-op same-position seeks).
        // ExoPlayer processes: pause → SEEK → AudioSink.flush() → SonicAudioProcessor.clear()
        // onPositionDiscontinuity fires on the main thread only after the seek (and flush) is
        // confirmed complete. setPlaybackParameters and play() are called from there, so the
        // message order on ExoPlayer's internal thread is guaranteed:
        //   flush (stale samples cleared) → setParams (new speed) → play
        pendingSpeedApply = () -> {
            pendingSpeedApply = null;
            speedChangeSeeking = true;
            exoPlayer.seekTo(flushSeekTarget(exoPlayer.getCurrentPosition(), positionBeforeSpeedChange));
        };
        mainHandler.postDelayed(pendingSpeedApply, 80);
    }

    /**
     * Target for the speed-change flush-seek. Normally the live position (precise), but if that read
     * is non-positive — a transient buffering/idle/re-prepare state can momentarily report 0 — fall
     * back to the position captured while the player was last known to be playing, so the flush never
     * accidentally restarts the episode from the beginning. Always nudges +1ms because ExoPlayer may
     * no-op a same-position seek (the flush only happens on an actual discontinuity).
     */
    static long flushSeekTarget(long livePositionMs, long fallbackPositionMs) {
        long base = livePositionMs > 0 ? livePositionMs : Math.max(fallbackPositionMs, 0);
        return base + 1;
    }

    private void applySpeedAndSilence(boolean skipSilence) {
        if (exoPlayer.getSkipSilenceEnabled() != skipSilence) {
            exoPlayer.setSkipSilenceEnabled(skipSilence);
        }
        exoPlayer.setPlaybackParameters(playbackParameters);
    }

    public void setVolume(float v, float v1) {
        applyVolume(v);
    }

    private void applyVolume(float v) {
        if (v > 1) {
            exoPlayer.setVolume(1f);
        } else {
            exoPlayer.setVolume(v);
        }
        updateLoudnessEnhancer(v);
    }

    private void updateLoudnessEnhancer(float v) {
        try {
            if (loudnessEnhancer != null) {
                if (v > 1) {
                    loudnessEnhancer.setEnabled(true);
                    loudnessEnhancer.setTargetGain((int) (1000 * (v - 1)));
                } else {
                    loudnessEnhancer.setEnabled(false);
                }
            }
        } catch (Exception e) {
            Log.d(TAG, e.toString());
        }
    }

    public void start() {
        Log.d(TAG, "start() called. exoPlayer.isPlaying=" + exoPlayer.isPlaying()
                + " playWhenReady=" + exoPlayer.getPlayWhenReady()
                + " wasPlayingBeforeSpeedChange=" + wasPlayingBeforeSpeedChange
                + " speedChangeSeeking=" + speedChangeSeeking, new Throwable("start-caller"));
        exoPlayer.play();
    }

    public void stop() {
        networkRecovery.reset();
        recoveryStartMs = 0;
        recoveryRestoreTargetMs = C.TIME_UNSET;
        exoPlayer.stop();
    }

    public List<String> getAudioTracks() {
        List<String> trackNames = new ArrayList<>();
        TrackNameProvider trackNameProvider = new DefaultTrackNameProvider(context.getResources());
        for (Format format : getFormats()) {
            trackNames.add(trackNameProvider.getTrackName(format));
        }
        return trackNames;
    }

    private List<Format> getFormats() {
        List<Format> formats = new ArrayList<>();
        MappingTrackSelector.MappedTrackInfo trackInfo = trackSelector.getCurrentMappedTrackInfo();
        if (trackInfo == null) {
            return Collections.emptyList();
        }
        TrackGroupArray trackGroups = trackInfo.getTrackGroups(getAudioRendererIndex());
        for (int i = 0; i < trackGroups.length; i++) {
            formats.add(trackGroups.get(i).getFormat(0));
        }
        return formats;
    }

    @SuppressWarnings("deprecation")
    public void setAudioTrack(int track) {
        MappingTrackSelector.MappedTrackInfo trackInfo = trackSelector.getCurrentMappedTrackInfo();
        if (trackInfo == null) {
            return;
        }
        TrackGroupArray trackGroups = trackInfo.getTrackGroups(getAudioRendererIndex());
        DefaultTrackSelector.SelectionOverride override = new DefaultTrackSelector.SelectionOverride(track, 0);
        DefaultTrackSelector.Parameters params = trackSelector.buildUponParameters()
                .setSelectionOverride(getAudioRendererIndex(), trackGroups, override).build();
        trackSelector.setParameters(params);
    }

    private int getAudioRendererIndex() {
        for (int i = 0; i < exoPlayer.getRendererCount(); i++) {
            if (exoPlayer.getRendererType(i) == C.TRACK_TYPE_AUDIO) {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("deprecation")
    public int getSelectedAudioTrack() {
        TrackSelectionArray trackSelections = exoPlayer.getCurrentTrackSelections();
        List<Format> availableFormats = getFormats();
        for (int i = 0; i < trackSelections.length; i++) {
            ExoTrackSelection track = (ExoTrackSelection) trackSelections.get(i);
            if (track == null) {
                continue;
            }
            if (availableFormats.contains(track.getSelectedFormat())) {
                return availableFormats.indexOf(track.getSelectedFormat());
            }
        }
        return -1;
    }

    void setOnCompletionListener(Runnable audioCompletionListener) {
        this.audioCompletionListener = audioCompletionListener;
    }

    void setOnSeekCompleteListener(Runnable audioSeekCompleteListener) {
        this.audioSeekCompleteListener = audioSeekCompleteListener;
    }

    void setOnErrorListener(Consumer<String> audioErrorListener) {
        this.audioErrorListener = audioErrorListener;
    }

    int getVideoWidth() {
        if (exoPlayer.getVideoFormat() == null) {
            return 0;
        }
        return exoPlayer.getVideoFormat().width;
    }

    int getVideoHeight() {
        if (exoPlayer.getVideoFormat() == null) {
            return 0;
        }
        return exoPlayer.getVideoFormat().height;
    }

    void setOnBufferingUpdateListener(Consumer<Integer> bufferingUpdateListener) {
        this.bufferingUpdateListener = bufferingUpdateListener;
    }

    private void initLoudnessEnhancer(int audioStreamId) {
        if (!VolumeAdaptionSetting.isBoostSupported()) {
            return;
        }
        if (audioStreamId == 0) {
            return;
        }

        LoudnessEnhancer newEnhancer;
        try {
            newEnhancer = new LoudnessEnhancer(audioStreamId);
        } catch (Exception e) {
            Log.d(TAG, "Failed to create LoudnessEnhancer: " + e);
            return;
        }

        LoudnessEnhancer oldEnhancer = this.loudnessEnhancer;
        if (oldEnhancer != null) {
            try {
                newEnhancer.setEnabled(oldEnhancer.getEnabled());
                if (oldEnhancer.getEnabled()) {
                    newEnhancer.setTargetGain((int) oldEnhancer.getTargetGain());
                }
            } catch (Exception e) {
                Log.d(TAG, e.toString());
            } finally {
                oldEnhancer.release();
            }
        }

        this.loudnessEnhancer = newEnhancer;
    }
}
