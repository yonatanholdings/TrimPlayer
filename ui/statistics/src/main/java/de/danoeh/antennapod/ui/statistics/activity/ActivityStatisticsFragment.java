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
    /** Day-of-week the Time Saved card is filtered to, or -1 for all-time. */
    private int selectedDay = -1;
    private TextView savedSectionLabel;
    private TextView savedTotal;
    private TextView savedPctLabel;
    private TextView savedSpeedVal;
    private TextView savedSpeedDetail;
    private TextView savedSilenceVal;
    private TextView savedAdsVal;
    private TextView savedIntrosVal;
    private TextView savedOutrosVal;
    private ProgressBar barSpeed;
    private ProgressBar barSilence;
    private ProgressBar barAds;
    private ProgressBar barIntros;
    private ProgressBar barOutros;

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
        savedSectionLabel = root.findViewById(R.id.saved_section_label);
        savedTotal        = root.findViewById(R.id.saved_total);
        savedPctLabel     = root.findViewById(R.id.saved_pct_label);
        savedSpeedVal     = root.findViewById(R.id.saved_speed_val);
        savedSpeedDetail  = root.findViewById(R.id.saved_speed_detail);
        savedSilenceVal   = root.findViewById(R.id.saved_silence_val);
        savedAdsVal       = root.findViewById(R.id.saved_ads_val);
        savedIntrosVal    = root.findViewById(R.id.saved_intros_val);
        savedOutrosVal    = root.findViewById(R.id.saved_outros_val);
        barSpeed          = root.findViewById(R.id.bar_speed);
        barSilence        = root.findViewById(R.id.bar_silence);
        barAds            = root.findViewById(R.id.bar_ads);
        barIntros         = root.findViewById(R.id.bar_intros);
        barOutros         = root.findViewById(R.id.bar_outros);

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

    /** Hour-of-day tap → "9pm · 2h 14m" or "9pm · no listening". Hour labels in
     *  12-hour clock to match how the user thinks about their listening. */
    private void onHourTap(int hour) {
        if (currentStats == null) {
            return;
        }
        long minutes = currentStats.byHour[hour];
        String hourLabel;
        if (hour == 0) hourLabel = "12am";
        else if (hour < 12) hourLabel = hour + "am";
        else if (hour == 12) hourLabel = "12pm";
        else hourLabel = (hour - 12) + "pm";
        hourDetail.setText(hourLabel + " · " + formatMinutes(minutes));
    }

    /** Day-of-week tap → "Friday · 8h 20m", and filters the Time Saved card to
     *  that weekday. Tapping the same day again clears the filter. */
    private void onDayTap(int day) {
        if (currentStats == null) {
            return;
        }
        String[] names = {"Sunday", "Monday", "Tuesday", "Wednesday",
                          "Thursday", "Friday", "Saturday"};
        long minutes = currentStats.byDay[day];
        dayDetail.setText(names[day] + " · " + formatMinutes(minutes));

        selectedDay = (selectedDay == day) ? -1 : day;
        renderSavedCard(currentStats);
    }

    private static String formatMinutes(long minutes) {
        if (minutes <= 0) return "no listening";
        long h = minutes / 60;
        long m = minutes % 60;
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }

    private void bind(DBReader.EditorialStats s) {
        currentStats = s;
        countStarted.setText(String.valueOf(s.episodesStarted));
        countFinished.setText(String.valueOf(s.episodesCompleted));
        countInProgress.setText(String.valueOf(s.episodesInProgress));
        countAbandoned.setText(String.valueOf(s.episodesAbandoned));

        int pct = s.episodesStarted > 0
                ? (int) Math.round(100.0 * s.episodesCompleted / s.episodesStarted) : 0;
        countCompletedPct.setText(pct + "% completion");

        hourBars.setData(s.byHour);
        dayMultiples.setData(s.byDay);

        renderSavedCard(s);
    }

    /** Renders the Time Saved card from either all-time totals or the
     *  per-day-of-week breakdown when {@link #selectedDay} is set. */
    private void renderSavedCard(DBReader.EditorialStats s) {
        long speed;
        long silence;
        long ads;
        long intros;
        long outros;
        long played;
        String sectionLabel;
        if (selectedDay >= 0) {
            long[] row = s.byDaySaved[selectedDay];
            speed = row[0];
            silence = row[1];
            ads = row[2];
            intros = row[3];
            outros = row[4];
            // byDay holds NET listening minutes for that weekday; use it as
            // the "played" base so the % stays comparable to the all-time view.
            played = s.byDay[selectedDay] * 60_000L;
            String[] caps = {"SUNDAYS", "MONDAYS", "TUESDAYS", "WEDNESDAYS",
                             "THURSDAYS", "FRIDAYS", "SATURDAYS"};
            sectionLabel = "Time saved · " + caps[selectedDay];
        } else {
            speed = s.savedSpeedMs;
            silence = s.savedSilenceMs;
            ads = s.savedAdsMs;
            intros = s.savedIntrosMs;
            outros = s.savedOutrosMs;
            played = s.totalPlayedMs;
            sectionLabel = "Time saved";
        }
        long total = speed + silence + ads + intros + outros;

        savedSectionLabel.setText(sectionLabel);
        savedTotal.setText(fmtHours(total));

        if (played + total > 0) {
            int savedPct = (int) Math.round(100.0 * total / (played + total));
            savedPctLabel.setText(savedPct + "% of audio");
        } else {
            savedPctLabel.setText("");
        }

        long maxSaved = Math.max(1, Math.max(speed,
                Math.max(silence, Math.max(ads, Math.max(intros, outros)))));
        savedSpeedVal.setText(fmtHours(speed));
        savedSilenceVal.setText(fmtHours(silence));
        savedAdsVal.setText(fmtHours(ads));
        savedIntrosVal.setText(fmtHours(intros));
        savedOutrosVal.setText(fmtHours(outros));
        int speedPct = total > 0 ? (int) Math.round(100.0 * speed / total) : 0;
        savedSpeedDetail.setText(speedPct + "% of total time saved");

        barSpeed.setProgress((int) (speed * 1000 / maxSaved));
        barSilence.setProgress((int) (silence * 1000 / maxSaved));
        barAds.setProgress((int) (ads * 1000 / maxSaved));
        barIntros.setProgress((int) (intros * 1000 / maxSaved));
        barOutros.setProgress((int) (outros * 1000 / maxSaved));
    }

    private static String fmtHours(long ms) {
        long h = ms / 3_600_000L;
        long m = (ms % 3_600_000L) / 60_000L;
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }
}
