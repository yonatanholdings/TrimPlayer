package de.danoeh.antennapod.ui.statistics.overview;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.widget.ViewPager2;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.ui.statistics.R;
import de.danoeh.antennapod.ui.statistics.StatisticsFragment;
import de.danoeh.antennapod.ui.statistics.StatisticsViewModel;
import de.danoeh.antennapod.ui.statistics.editorial.EditorialTheme;
import de.danoeh.antennapod.ui.statistics.editorial.HeatmapView;
import de.danoeh.antennapod.ui.statistics.editorial.SparklineView;

public class OverviewStatisticsFragment extends Fragment {

    private TextView heroHours;
    private TextView heroMinutes;
    private TextView hangTrimmed;
    private TextView hangFinished;
    private TextView hangStreak;
    private SparklineView sparkline;
    private HeatmapView heatmap;
    private TextView tocSubHrs;
    private TextView tocActPct;
    private TextView tocYearsHrs;
    private TextView tocSavedHrs;
    private TextView mastVol;
    private TextView heatmapDetail;
    /** Last bound editorial stats — needed by heatmap tap to look up per-cell ms. */
    private DBReader.EditorialStats currentStats;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_overview_statistics, container, false);

        heroHours = root.findViewById(R.id.hero_hours);
        heroMinutes = root.findViewById(R.id.hero_minutes);
        hangTrimmed = root.findViewById(R.id.hang_trimmed_val);
        hangFinished = root.findViewById(R.id.hang_finished_val);
        hangStreak = root.findViewById(R.id.hang_streak_val);
        sparkline = root.findViewById(R.id.hero_sparkline);
        heatmap = root.findViewById(R.id.heatmap);
        tocSubHrs = root.findViewById(R.id.toc_sub_hrs);
        tocActPct = root.findViewById(R.id.toc_act_pct);
        tocYearsHrs = root.findViewById(R.id.toc_years_hrs);
        tocSavedHrs = root.findViewById(R.id.toc_saved_hrs);
        mastVol = root.findViewById(R.id.masthead_vol);
        heatmapDetail = root.findViewById(R.id.heatmap_detail);

        // Tap a heatmap cell → reveal the date + listening time underneath.
        heatmap.setOnCellClickListener(this::onHeatmapCellTap);

        // Apply serif typeface to numerals
        heroHours.setTypeface(EditorialTheme.getSerif(requireContext()));
        hangTrimmed.setTypeface(EditorialTheme.getSerif(requireContext()));
        hangFinished.setTypeface(EditorialTheme.getSerif(requireContext()));
        hangStreak.setTypeface(EditorialTheme.getSerif(requireContext()));

        // masthead issue number from year
        int year = Calendar.getInstance().get(Calendar.YEAR);
        int issue = Calendar.getInstance().get(Calendar.MONTH) + 1;
        mastVol.setText(String.format(Locale.getDefault(), "VOL. %02d · ISSUE %02d", year - 2020, issue));

        // TOC row taps navigate to sibling tabs
        wireToc(root.findViewById(R.id.toc_subscriptions), StatisticsFragment.POS_SUBSCRIPTIONS);
        wireToc(root.findViewById(R.id.toc_activity), StatisticsFragment.POS_ACTIVITY);
        wireToc(root.findViewById(R.id.toc_years), StatisticsFragment.POS_YEARS);
        wireToc(root.findViewById(R.id.toc_saved), StatisticsFragment.POS_TIME_SAVED);

        new ViewModelProvider(requireParentFragment())
                .get(StatisticsViewModel.class)
                .editorial()
                .observe(getViewLifecycleOwner(), s -> { if (s != null) bind(s); });

        return root;
    }

    private void wireToc(View row, int tabPos) {
        row.setOnClickListener(v -> {
            Fragment parent = getParentFragment();
            if (parent != null) {
                ViewPager2 pager = parent.getView() != null
                        ? parent.getView().findViewById(R.id.viewpager) : null;
                if (pager != null) pager.setCurrentItem(tabPos, true);
            }
        });
    }

    /** Heatmap cell tap → format "MMM DD · Xh Ym" (or "MMM DD · NO LISTENING") and
     *  show in the detail row. Date computed from the cached `heatmapStartMs` so
     *  no DB lookup is needed. */
    private void onHeatmapCellTap(int weekIdx, int dayIdx) {
        if (currentStats == null) return;
        long dayMs = currentStats.heatmapStartMs + (weekIdx * 7L + dayIdx) * 86_400_000L;
        java.text.SimpleDateFormat dateFmt = new java.text.SimpleDateFormat("MMM d", Locale.getDefault());
        String date = dateFmt.format(new Date(dayMs)).toUpperCase(Locale.getDefault());
        long listenedMs = currentStats.heatmapMs[weekIdx][dayIdx];
        if (listenedMs <= 0) {
            heatmapDetail.setText(date + " · NO LISTENING");
            return;
        }
        long minutes = listenedMs / 60_000L;
        long hours = minutes / 60;
        long mins = minutes % 60;
        String hm = hours > 0 ? hours + "h " + mins + "m" : mins + "m";
        heatmapDetail.setText(date + " · " + hm);
    }

    private void bind(DBReader.EditorialStats s) {
        currentStats = s;
        // Year-to-date — show days if ≥ 24 h, else hours
        long ytdMs = 0;
        int curYear = Calendar.getInstance().get(Calendar.YEAR);
        for (DBReader.EditorialStats.YearItem y : s.yearly) {
            if (y.year == curYear) { ytdMs = Math.round(y.hrs * 3_600_000f); break; }
        }
        long ytdHours = ytdMs / 3_600_000L;
        long ytdDays  = ytdHours / 24;
        long remHours = ytdHours % 24;
        long ytdMins  = (ytdMs % 3_600_000L) / 60_000L;
        if (ytdDays > 0) {
            heroHours.setText(ytdDays + "d");
            heroMinutes.setText(String.format(Locale.getDefault(),
                    "%d days, %d hours of audio.", ytdDays, remHours));
        } else {
            heroHours.setText(String.valueOf(ytdHours));
            heroMinutes.setText(String.format(Locale.getDefault(),
                    "and %d %s.", ytdMins, ytdMins == 1 ? "minute" : "minutes"));
        }

        // Hang stats
        hangTrimmed.setText(formatHM(s.totalSavedMs));
        hangFinished.setText(String.valueOf(s.episodesCompleted));
        hangStreak.setText(String.valueOf(s.streakDays));

        // Sparkline
        sparkline.setData(s.weekly);

        // Heatmap
        heatmap.setData(s.heatmap);

        // TOC values
        float totalHrs = s.totalPlayedMs / 3_600_000f;
        long totalDays = (long) (totalHrs / 24);
        tocYearsHrs.setText(totalDays > 0 ? totalDays + "d" : Math.round(totalHrs) + "h");
        tocSavedHrs.setText(Math.round(s.totalSavedMs / 3_600_000f) + "h");

        float subHrs = 0;
        for (DBReader.EditorialStats.ShowItem sh : s.shows) subHrs += sh.hrs;
        tocSubHrs.setText(Math.round(subHrs) + "h");

        int pct = s.episodesStarted > 0
                ? (int) Math.round(100.0 * s.episodesCompleted / s.episodesStarted) : 0;
        tocActPct.setText(pct + "%");
    }

    private static String formatHM(long ms) {
        long h = ms / 3_600_000L;
        long m = (ms % 3_600_000L) / 60_000L;
        if (h > 0) return h + "h";
        return m + "m";
    }
}
