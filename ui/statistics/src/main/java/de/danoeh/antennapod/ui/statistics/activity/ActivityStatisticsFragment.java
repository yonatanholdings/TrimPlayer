package de.danoeh.antennapod.ui.statistics.activity;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.Locale;

import de.danoeh.antennapod.event.StatisticsEvent;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.ui.statistics.R;
import de.danoeh.antennapod.ui.statistics.editorial.DayMultiplesView;
import de.danoeh.antennapod.ui.statistics.editorial.EditorialTheme;
import de.danoeh.antennapod.ui.statistics.editorial.HourBarsView;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class ActivityStatisticsFragment extends Fragment {
    private static final String TAG = "ActivityStatsFragment";
    private Disposable disposable;

    private TextView countStarted;
    private TextView countFinished;
    private TextView countInProgress;
    private TextView countCompletedPct;
    private HourBarsView hourBars;
    private DayMultiplesView dayMultiples;
    private TextView savedTotal;
    private TextView savedPctLabel;
    private TextView savedSpeedVal;
    private TextView savedSpeedDetail;
    private TextView savedSilenceVal;
    private TextView savedIntrosVal;
    private ProgressBar barSpeed;
    private ProgressBar barSilence;
    private ProgressBar barIntros;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_activity_statistics, container, false);

        countStarted      = root.findViewById(R.id.count_started);
        countFinished     = root.findViewById(R.id.count_finished);
        countInProgress   = root.findViewById(R.id.count_inprogress);
        countCompletedPct = root.findViewById(R.id.count_completed_pct);
        hourBars          = root.findViewById(R.id.hour_bars);
        dayMultiples      = root.findViewById(R.id.day_multiples);
        savedTotal        = root.findViewById(R.id.saved_total);
        savedPctLabel     = root.findViewById(R.id.saved_pct_label);
        savedSpeedVal     = root.findViewById(R.id.saved_speed_val);
        savedSpeedDetail  = root.findViewById(R.id.saved_speed_detail);
        savedSilenceVal   = root.findViewById(R.id.saved_silence_val);
        savedIntrosVal    = root.findViewById(R.id.saved_intros_val);
        barSpeed          = root.findViewById(R.id.bar_speed);
        barSilence        = root.findViewById(R.id.bar_silence);
        barIntros         = root.findViewById(R.id.bar_intros);

        countStarted.setTypeface(EditorialTheme.getSerif(requireContext()));
        countFinished.setTypeface(EditorialTheme.getSerif(requireContext()));
        countInProgress.setTypeface(EditorialTheme.getSerif(requireContext()));
        countCompletedPct.setTypeface(EditorialTheme.getSerif(requireContext()));
        savedTotal.setTypeface(EditorialTheme.getSerif(requireContext()));

        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        refresh();
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
        if (disposable != null) disposable.dispose();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onStatisticsEvent(StatisticsEvent event) {
        refresh();
    }

    private void refresh() {
        if (disposable != null) disposable.dispose();
        disposable = Observable.fromCallable(DBReader::getEditorialStats)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::bind, e -> Log.e(TAG, Log.getStackTraceString(e)));
    }

    private void bind(DBReader.EditorialStats s) {
        countStarted.setText(String.valueOf(s.episodesStarted));
        countFinished.setText(String.valueOf(s.episodesCompleted));
        countInProgress.setText(String.valueOf(s.episodesInProgress));

        int pct = s.episodesStarted > 0
                ? (int) Math.round(100.0 * s.episodesCompleted / s.episodesStarted) : 0;
        countCompletedPct.setText(pct + "%");

        hourBars.setData(s.byHour);
        dayMultiples.setData(s.byDay);

        // Time saved section
        savedTotal.setText(fmtHours(s.totalSavedMs));

        if (s.totalPlayedMs > 0) {
            int savedPct = (int) Math.round(100.0 * s.totalSavedMs / (s.totalPlayedMs + s.totalSavedMs));
            savedPctLabel.setText(savedPct + "% OF AUDIO");
        }

        long maxSaved = Math.max(1, Math.max(s.savedSpeedMs, Math.max(s.savedSilenceMs, s.savedIntrosMs)));
        savedSpeedVal.setText(fmtHours(s.savedSpeedMs));
        savedSilenceVal.setText(fmtHours(s.savedSilenceMs));
        savedIntrosVal.setText(fmtHours(s.savedIntrosMs));
        int speedPct = s.totalSavedMs > 0 ? (int) Math.round(100.0 * s.savedSpeedMs / s.totalSavedMs) : 0;
        savedSpeedDetail.setText(speedPct + "% of total time saved");

        barSpeed.setProgress((int) (s.savedSpeedMs * 1000 / maxSaved));
        barSilence.setProgress((int) (s.savedSilenceMs * 1000 / maxSaved));
        barIntros.setProgress((int) (s.savedIntrosMs * 1000 / maxSaved));
    }

    private static String fmtHours(long ms) {
        long h = ms / 3_600_000L;
        long m = (ms % 3_600_000L) / 60_000L;
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }
}
