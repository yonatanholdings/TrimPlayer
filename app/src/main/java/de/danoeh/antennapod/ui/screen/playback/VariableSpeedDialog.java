package de.danoeh.antennapod.ui.screen.playback;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;
import com.google.android.material.snackbar.Snackbar;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.event.playback.SpeedChangedEvent;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.view.ItemOffsetDecoration;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class VariableSpeedDialog extends BottomSheetDialogFragment {
    private static final String TAG = "VariableSpeedDialog";
    private SpeedSelectionAdapter adapter;
    private PlaybackController controller;
    private final List<Float> selectedSpeeds;
    private PlaybackSpeedSeekBar speedSeekBar;
    private android.widget.TextView currentSpeedLabel;
    private View addCurrentSpeedButton;
    private CheckBox skipSilenceCheckbox;
    private android.widget.CompoundButton.OnCheckedChangeListener skipSilenceUserChangedListener;
    private CheckBox skipIntrosCheckbox;
    private CheckBox skipAdsCheckbox;
    private CheckBox skipOutrosCheckbox;
    private Disposable disposable;

    /**
     * Snapshot of the currently-playing media, resolved on the IO thread by
     * {@link #loadMediaInfo()}. The seek-bar and skip-silence listeners must
     * NEVER call {@code controller.getMedia()} directly — its first call
     * synchronously opens the DB and crashes the app via StrictMode when
     * triggered from the main thread (e.g. the global Settings → Playback
     * speed entry where the dialog opens with no media yet loaded).
     */
    private volatile de.danoeh.antennapod.model.playback.Playable cachedMedia;

    public VariableSpeedDialog() {
        DecimalFormatSymbols format = new DecimalFormatSymbols(Locale.US);
        format.setDecimalSeparator('.');
        selectedSpeeds = new ArrayList<>(UserPreferences.getPlaybackSpeedArray());
    }

    @Override
    public void onStart() {
        super.onStart();
        controller = new PlaybackController(getActivity()) {
            @Override
            public void loadMediaInfo() {
                VariableSpeedDialog.this.loadMediaInfo();
            }
        };
        controller.init();
        EventBus.getDefault().register(this);
        loadMediaInfo();
    }

    @Override
    public void onStop() {
        super.onStop();
        controller.release();
        controller = null;
        EventBus.getDefault().unregister(this);
        if (disposable != null) {
            disposable.dispose();
        }
    }

    private void loadMediaInfo() {
        if (disposable != null) {
            disposable.dispose();
        }
        disposable = Observable.fromCallable(() -> {
            if (controller == null) {
                return java.util.Optional.<de.danoeh.antennapod.model.playback.Playable>empty();
            }
            // Force the IO-side DB load here (off the main thread) so the seek-bar
            // and silence listeners can use the cached snapshot without re-fetching.
            de.danoeh.antennapod.model.playback.Playable p = controller.getMedia();
            return p == null ? java.util.Optional.<de.danoeh.antennapod.model.playback.Playable>empty()
                             : java.util.Optional.of(p);
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(maybe -> {
                    if (controller == null) {
                        return;
                    }
                    cachedMedia = maybe.orElse(null);
                    updateSpeed(new SpeedChangedEvent(controller.getCurrentPlaybackSpeedMultiplier()));
                    updateSkipSilence(controller.getCurrentPlaybackSkipSilence());
                    updateTrimSkipCheckboxes();
                }, error -> Log.e(TAG, Log.getStackTraceString(error)));

    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void updateSpeed(SpeedChangedEvent event) {
        speedSeekBar.updateSpeed(event.getNewSpeed());
        currentSpeedLabel.setText(String.format(Locale.getDefault(), "%1$.2f×", event.getNewSpeed()));
    }

    public void updateSkipSilence(boolean skipSilence) {
        // Programmatic setChecked() fires the OnCheckedChangeListener if the
        // state actually changes. That listener writes to UserPreferences and
        // controller, so if the dialog opens with the global value pre-filled
        // and then this method later sets the resolved per-feed value, an
        // unintended write happens. Suppress the listener for this update.
        skipSilenceCheckbox.setOnCheckedChangeListener(null);
        skipSilenceCheckbox.setChecked(skipSilence);
        skipSilenceCheckbox.setOnCheckedChangeListener(skipSilenceUserChangedListener);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = View.inflate(getContext(), R.layout.speed_select_dialog, null);
        speedSeekBar = root.findViewById(R.id.speed_seek_bar);
        // Seed the seek-bar with the app-wide saved speed immediately. The
        // underlying SeekBar's intrinsic default is progress=0 which maps to
        // (0 + 10) / 20 = 0.5×, so without this pre-fill the dialog briefly
        // displays "0.50" before loadMediaInfo's async IO callback arrives
        // and corrects it. When a media is playing, loadMediaInfo() later
        // overwrites this with the resolved per-feed/per-episode value.
        speedSeekBar.updateSpeed(UserPreferences.getPlaybackSpeed());
        speedSeekBar.setProgressChangedListener(multiplier -> {
            // Use the cached snapshot resolved by loadMediaInfo on the IO thread;
            // calling controller.getMedia() here would trigger a main-thread DB
            // open and crash via StrictMode (RuntimeException: I/O on main thread).
            de.danoeh.antennapod.model.playback.Playable media = cachedMedia;
            if (media instanceof FeedMedia) {
                FeedMedia feedMedia = (FeedMedia) media;
                Feed feed = feedMedia.getItem() != null ? feedMedia.getItem().getFeed() : null;
                if (feed != null && feed.getPreferences() != null) {
                    feed.getPreferences().setFeedPlaybackSpeed(multiplier);
                    DBWriter.setFeedPreferences(feed.getPreferences());
                } else {
                    UserPreferences.setPlaybackSpeed(multiplier);
                }
            } else {
                UserPreferences.setPlaybackSpeed(multiplier);
            }

            if (controller != null) {
                controller.setPlaybackSpeed(multiplier);
            }
        });
        RecyclerView selectedSpeedsGrid = root.findViewById(R.id.selected_speeds_grid);
        selectedSpeedsGrid.setLayoutManager(new GridLayoutManager(getContext(), 3));
        selectedSpeedsGrid.addItemDecoration(new ItemOffsetDecoration(getContext(), 4));
        adapter = new SpeedSelectionAdapter();
        adapter.setHasStableIds(true);
        selectedSpeedsGrid.setAdapter(adapter);

        // Numeric readout of the current speed in the dialog header.
        currentSpeedLabel = root.findViewById(R.id.current_speed_label);
        currentSpeedLabel.setText(String.format(Locale.getDefault(), "%1$.2f×",
                UserPreferences.getPlaybackSpeed()));

        // "+ Add current" TextButton placed next to the Speed presets header so
        // its plus icon doesn't visually compete with the seek-bar's + button.
        addCurrentSpeedButton = root.findViewById(R.id.add_current_speed_button);
        addCurrentSpeedButton.setOnClickListener(v -> addCurrentSpeed());

        skipSilenceCheckbox = root.findViewById(R.id.skipSilence);
        skipSilenceCheckbox.setChecked(UserPreferences.isSkipSilence());
        // When playing a FeedMedia, mirror the per-feed write semantics that the
        // speed seek-bar uses above: writing the user's intent to the per-feed
        // preference rather than the app-wide one. Previously this only wrote
        // to UserPreferences, which silently flipped silence globally and broke
        // inheritance for other podcasts the user had not configured.
        skipSilenceUserChangedListener = (buttonView, isChecked) -> {
            // Use cachedMedia, not controller.getMedia(), for the same StrictMode
            // reason as the speed seek-bar listener above.
            boolean wroteToFeed = false;
            de.danoeh.antennapod.model.playback.Playable media = cachedMedia;
            if (media instanceof FeedMedia) {
                FeedMedia feedMedia = (FeedMedia) media;
                Feed feed = feedMedia.getItem() != null ? feedMedia.getItem().getFeed() : null;
                if (feed != null && feed.getPreferences() != null) {
                    feed.getPreferences().setFeedSkipSilence(isChecked
                            ? FeedPreferences.SkipSilence.AGGRESSIVE
                            : FeedPreferences.SkipSilence.OFF);
                    DBWriter.setFeedPreferences(feed.getPreferences());
                    wroteToFeed = true;
                }
            }
            if (!wroteToFeed) {
                UserPreferences.setSkipSilence(isChecked);
            }
            if (controller != null) {
                controller.setSkipSilence(isChecked);
            }
        };
        skipSilenceCheckbox.setOnCheckedChangeListener(skipSilenceUserChangedListener);

        skipIntrosCheckbox = root.findViewById(R.id.skipIntros);
        skipAdsCheckbox    = root.findViewById(R.id.skipAds);
        skipOutrosCheckbox = root.findViewById(R.id.skipOutros);
        // Initial best-guess from globals; updateTrimSkipCheckboxes() will refine once media is loaded.
        skipIntrosCheckbox.setChecked(UserPreferences.isTrimSkipIntrosEnabled());
        skipAdsCheckbox.setChecked(UserPreferences.isTrimSkipAdsEnabled());
        skipOutrosCheckbox.setChecked(UserPreferences.isTrimSkipOutrosEnabled());
        skipIntrosCheckbox.setOnCheckedChangeListener((b, isChecked) -> writeTrimSkipPref("intro", isChecked));
        skipAdsCheckbox.setOnCheckedChangeListener((b, isChecked)    -> writeTrimSkipPref("ad",    isChecked));
        skipOutrosCheckbox.setOnCheckedChangeListener((b, isChecked) -> writeTrimSkipPref("outro", isChecked));

        return root;
    }

    /** Refresh trim-skip checkboxes from the active feed's preferences (or global as fallback). */
    private void updateTrimSkipCheckboxes() {
        if (skipIntrosCheckbox == null) return;
        Feed feed = currentFeed();
        boolean intros = feed != null ? feed.getPreferences().isTrimSkipIntros() : UserPreferences.isTrimSkipIntrosEnabled();
        boolean ads    = feed != null ? feed.getPreferences().isTrimSkipAds()    : UserPreferences.isTrimSkipAdsEnabled();
        boolean outros = feed != null ? feed.getPreferences().isTrimSkipOutros() : UserPreferences.isTrimSkipOutrosEnabled();
        setCheckedSilently(skipIntrosCheckbox, intros, (b, v) -> writeTrimSkipPref("intro", v));
        setCheckedSilently(skipAdsCheckbox,    ads,    (b, v) -> writeTrimSkipPref("ad",    v));
        setCheckedSilently(skipOutrosCheckbox, outros, (b, v) -> writeTrimSkipPref("outro", v));
    }

    private static void setCheckedSilently(CheckBox cb, boolean checked,
                                           android.widget.CompoundButton.OnCheckedChangeListener listener) {
        cb.setOnCheckedChangeListener(null);
        cb.setChecked(checked);
        cb.setOnCheckedChangeListener(listener);
    }

    private Feed currentFeed() {
        // Use cachedMedia (resolved on the IO thread by loadMediaInfo) — calling
        // controller.getMedia() from main-thread UI callbacks would trigger a
        // synchronous DB open and crash via StrictMode.
        de.danoeh.antennapod.model.playback.Playable media = cachedMedia;
        if (!(media instanceof FeedMedia)) return null;
        FeedMedia feedMedia = (FeedMedia) media;
        if (feedMedia.getItem() == null) return null;
        return feedMedia.getItem().getFeed();
    }

    private void writeTrimSkipPref(String type, boolean enabled) {
        Feed feed = currentFeed();
        if (feed != null && feed.getPreferences() != null) {
            switch (type) {
                case "intro": feed.getPreferences().setTrimSkipIntros(enabled); break;
                case "ad":    feed.getPreferences().setTrimSkipAds(enabled);    break;
                case "outro": feed.getPreferences().setTrimSkipOutros(enabled); break;
                default: return;
            }
            DBWriter.setFeedPreferences(feed.getPreferences());
        } else {
            switch (type) {
                case "intro": UserPreferences.setTrimSkipIntrosEnabled(enabled); break;
                case "ad":    UserPreferences.setTrimSkipAdsEnabled(enabled);    break;
                case "outro": UserPreferences.setTrimSkipOutrosEnabled(enabled); break;
                default:
            }
        }
    }

    private void addCurrentSpeed() {
        float newSpeed = speedSeekBar.getCurrentSpeed();
        if (selectedSpeeds.contains(newSpeed)) {
            Snackbar.make(addCurrentSpeedButton,
                    getString(R.string.preset_already_exists, newSpeed), Snackbar.LENGTH_LONG).show();
        } else {
            selectedSpeeds.add(newSpeed);
            Collections.sort(selectedSpeeds);
            UserPreferences.setPlaybackSpeedArray(selectedSpeeds);
            adapter.notifyDataSetChanged();
        }
    }

    public class SpeedSelectionAdapter extends RecyclerView.Adapter<SpeedSelectionAdapter.ViewHolder> {

        @Override
        @NonNull
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Chip chip = new Chip(getContext());
            chip.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            return new ViewHolder(chip);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            float speed = selectedSpeeds.get(position);

            holder.chip.setText(String.format(Locale.getDefault(), "%1$.2f", speed));
            holder.chip.setOnLongClickListener(v -> {
                // 1.0× is the default speed and must always remain available as a
                // one-tap preset. Block removal at the UI layer; the storage layer
                // (UserPreferences.setPlaybackSpeedArray) also re-adds it if missing.
                if (Math.abs(speed - 1.0f) < 0.001f) {
                    android.widget.Toast.makeText(v.getContext(),
                            R.string.playback_speed_default_cannot_remove,
                            android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                }
                selectedSpeeds.remove(speed);
                UserPreferences.setPlaybackSpeedArray(selectedSpeeds);
                notifyDataSetChanged();
                return true;
            });
            holder.chip.setOnClickListener(v -> {
                // Use cachedMedia, not controller.getMedia(), to avoid main-thread
                // DB I/O when nothing is playing yet (Settings → Playback speed).
                de.danoeh.antennapod.model.playback.Playable cm = cachedMedia;
                boolean wroteToFeed = false;
                if (cm instanceof FeedMedia) {
                    FeedMedia media = (FeedMedia) cm;
                    Feed feed = media.getItem() != null ? media.getItem().getFeed() : null;
                    if (feed != null && feed.getPreferences() != null) {
                        feed.getPreferences().setFeedPlaybackSpeed(speed);
                        DBWriter.setFeedPreferences(feed.getPreferences());
                        wroteToFeed = true;
                    }
                }
                if (!wroteToFeed) {
                    UserPreferences.setPlaybackSpeed(speed);
                }
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (controller != null) {
                        controller.setPlaybackSpeed(speed);
                        dismiss();
                    }
                }, 200);
            });
        }

        @Override
        public int getItemCount() {
            return selectedSpeeds.size();
        }

        @Override
        public long getItemId(int position) {
            return selectedSpeeds.get(position).hashCode();
        }

        public class ViewHolder extends RecyclerView.ViewHolder {
            Chip chip;

            ViewHolder(Chip itemView) {
                super(itemView);
                chip = itemView;
            }
        }
    }
}
