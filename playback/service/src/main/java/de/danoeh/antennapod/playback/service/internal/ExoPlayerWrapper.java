package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.media.audiofx.LoudnessEnhancer;
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
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
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
import java.io.File;
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
    // True while a seekTo() triggered internally by setPlaybackParams is in flight.
    // onPositionDiscontinuity uses this to apply the new speed + resume after the
    // seek-triggered AudioSink.flush() is confirmed complete, and to suppress the
    // external seek-complete listener for this internal seek.
    private boolean speedChangeSeeking = false;
    // skipSilence value captured at setPlaybackParams call-time for use in onPositionDiscontinuity.
    private boolean pendingSkipSilence = false;

    ExoPlayerWrapper(Context context) {
        this.context = context;
        createPlayer();
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
                if (audioErrorListener != null) {
                    if (NetworkUtils.wasDownloadBlocked(error)) {
                        audioErrorListener.accept(context.getString(R.string.download_error_blocked));
                    } else {
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
                }
            }

            @Override
            public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition,
                                                @NonNull Player.PositionInfo newPosition,
                                                @Player.DiscontinuityReason int reason) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    if (speedChangeSeeking) {
                        // The flush-seek is confirmed complete by ExoPlayer — AudioSink.flush()
                        // has already run, SonicAudioProcessor's stale-sample buffer is clear.
                        // Now it is safe to set the new speed and resume.
                        speedChangeSeeking = false;
                        applySpeedAndSilence(pendingSkipSilence);
                        if (wasPlayingBeforeSpeedChange) {
                            wasPlayingBeforeSpeedChange = false;
                            exoPlayer.play();
                        }
                        return;
                    }
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
        simpleCache = new SimpleCache(new File(context.getCacheDir(), "streaming"),
                new LeastRecentlyUsedCacheEvictor(100 * 1024 * 1024), new StandaloneDatabaseProvider(context));
        initLoudnessEnhancer(exoPlayer.getAudioSessionId());
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
        exoPlayer.pause();
    }

    public void prepare() throws IllegalStateException {
        exoPlayer.setMediaSource(mediaSource, false);
        exoPlayer.prepare();
    }

    public void release() {
        bufferingUpdateDisposable.dispose();
        if (exoPlayer != null) {
            exoPlayer.release();
        }
        if (simpleCache != null) {
            simpleCache.release();
            simpleCache = null;
        }
        cancelPendingSpeedChange();
        audioSeekCompleteListener = null;
        audioCompletionListener = null;
        audioErrorListener = null;
        bufferingUpdateListener = null;
    }

    public void reset() {
        cancelPendingSpeedChange();
        exoPlayer.release();
        if (simpleCache != null) {
            simpleCache.release();
            simpleCache = null;
        }
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

    public void seekTo(int i) throws IllegalStateException {
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

        if (!TextUtils.isEmpty(user) && !TextUtils.isEmpty(password)) {
            final HashMap<String, String> requestProperties = new HashMap<>();
            requestProperties.put("Authorization", HttpCredentialEncoder.encode(user, password, "ISO-8859-1"));
            httpDataSourceFactory.setDefaultRequestProperties(requestProperties);
        }
        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context, httpDataSourceFactory);
        if (s.startsWith("http")) {
            dataSourceFactory = new CacheDataSource.Factory()
                    .setCache(simpleCache)
                    .setUpstreamDataSourceFactory(httpDataSourceFactory);
        }
        DefaultExtractorsFactory extractorsFactory = new DefaultExtractorsFactory();
        extractorsFactory.setConstantBitrateSeekingEnabled(true);
        extractorsFactory.setMp3ExtractorFlags(Mp3Extractor.FLAG_DISABLE_ID3_METADATA);
        ProgressiveMediaSource.Factory f = new ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory);
        final MediaItem mediaItem = MediaItem.fromUri(Uri.parse(s));
        mediaSource = f.createMediaSource(mediaItem);
    }

    public void setDataSource(String s) throws IllegalArgumentException, IllegalStateException {
        setDataSource(s, null, null);
    }

    public void setDisplay(SurfaceHolder sh) {
        exoPlayer.setVideoSurfaceHolder(sh);
    }

    public void setPlaybackParams(float speed, boolean skipSilence) {
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
            exoPlayer.seekTo(exoPlayer.getCurrentPosition() + 1);
        };
        mainHandler.postDelayed(pendingSpeedApply, 80);
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
        exoPlayer.play();
    }

    public void stop() {
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
