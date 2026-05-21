package de.danoeh.antennapod.ui.statistics.activity;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import java.util.Locale;

import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.ui.statistics.R;
import de.danoeh.antennapod.ui.statistics.StatisticsViewModel;
import de.danoeh.antennapod.ui.statistics.editorial.DayMultiplesView;
import de.danoeh.antennapod.ui.statistics.editorial.EditorialTheme;
import de.danoeh.antennapod.ui.statistics.editorial.HourBarsView;

public class ActivityStatisticsFragment extends Fragment {
    private TextView countStarted;
    private TextView countFinished;
    private TextView countInProgress;
    private TextView countAbandoned;
    private TextView countCompletedPct;
    private HourBarsView hourBars;
    private TextView hourDetail;
    private DayMultiplesView dayMultiples;
    private TextView dayDetail;
    /** Cached last-bound stats so chart taps can look up per-bucket values. */
    private DBReader.EditorialStats currentStats;
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
        countAbandoned    = root.findViewById(R.id.count_abandoned);
        countCompletedPct = root.findViewById(R.id.count_completed_pct);
        hourBars          = root.findViewById(R.id.hour_bars);
        hourDetail        = root.findViewById(R.id.hour_detail);
        dayMultiples      = root.findViewById(R.id.day_multiples);
        dayDetail         = root.findViewById(R.id.day_detail);

        hourBars.setOnHourClickListener(this::onHourTap);
        dayMultiples.setOnDayClickListener(this::onDayTap);
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
        countAbandoned.setTypeface(EditorialTheme.getSerif(requireContext()));
        savedTotal.setTypeface(EditorialTheme.getSerif(requireContext()));

        new ViewModelProvider(requireParentFragment())
                .get(StatisticsViewModel.class)
                .editorial()
                .observe(getViewLifecycleOwner(), s -> { if (s != null) bind(s); });

        return root;
    }

    /** Hour-of-day tap → "9PM · 2H 14M" or "9PM · NO LISTENING". Hour labels in
     *  12-hour clock to match how the user thinks about their listening. */
    private void onHourTap(int hour) {
        if (currentStats == null) return;
        long minutes = currentStats.byHour[hour];
        String hourLabel;
        if (hour == 0) hourLabel = "12AM";
        else if (hour < 12) hourLabel = hour + "AM";
        else if (hour == 12) hourLabel = "12PM";
        else hourLabel = (hour - 12) + "PM";
        hourDetail.setText(hourLabel + " · " + formatMinutes(minutes));
    }

    /** Day-of-week tap → "FRIDAY · 8H 20M". Maps 0=Sun … 6=Sat to full names. */
    private void onDayTap(int day) {
        if (currentStats == null) return;
        String[] names = {"SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY",
                          "THURSDAY", "FRIDAY", "SATURDAY"};
        long minutes = currentStats.byDay[day];
        dayDetail.setText(names[day] + " · " + formatMinutes(minutes));
    }

    private static String formatMinutes(long minutes) {
        if (minutes <= 0) return "NO LISTENING";
        long h = minutes / 60;
        long m = minutes % 60;
        if (h > 0) return h + "H " + m + "M";
        return m + "M";
    }

    private void bind(DBReader.EditorialStats s) {
        currentStats = s;
        countStarted.setText(String.valueOf(s.episodesStarted));
        countFinished.setText(String.valueOf(s.episodesCompleted));
        countInProgress.setText(String.valueOf(s.episodesInProgress));
        countAbandoned.setText(String.valueOf(s.episodesAbandoned));

        int pct = s.episodesStarted > 0
                ? (int) Math.round(100.0 * s.episodesCompleted / s.episodesStarted) : 0;
        countCompletedPct.setText(pct + "% COMPLETION");

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
