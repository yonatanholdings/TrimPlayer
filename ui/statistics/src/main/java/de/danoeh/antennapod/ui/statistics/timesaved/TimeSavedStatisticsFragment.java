package de.danoeh.antennapod.ui.statistics.timesaved;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import java.util.Calendar;
import java.util.Locale;
import java.util.TreeMap;

import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.ui.statistics.DemoStats;
import de.danoeh.antennapod.ui.statistics.R;
import de.danoeh.antennapod.ui.statistics.StatisticsViewModel;
import de.danoeh.antennapod.ui.statistics.editorial.EditorialTheme;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class TimeSavedStatisticsFragment extends Fragment {

    private TextView totalSavedValue;
    private TextView periodTodayValue;
    private TextView periodWeekValue;
    private TextView periodMonthValue;
    private View stackSpeed;
    private View stackSilence;
    private View stackAds;
    private View stackIntro;
    private View stackOutro;
    private TextView stackedCaption;
    private ProgressBar barIntro;
    private ProgressBar barOutro;
    private ProgressBar barAds;
    private ProgressBar barSilence;
    private ProgressBar barSpeed;
    private TextView valueIntro;
    private TextView valueOutro;
    private TextView valueAds;
    private TextView valueSilence;
    private TextView valueSpeed;
    private LinearLayout yearlyContainer;
    private TextView emptyState;
    private TextView categorySubheader;

    /** Latest all-time stats from the ViewModel; the §03 year list is built from
     *  this, and tapping a year drills the §01+§02 by-type breakdown into the
     *  selected year via a separate async query. */
    private DBReader.SkipStatistics allTimeStats;
    /** Currently-selected year (null = "all time" view). */
    private Integer selectedYear;
    /** Disposable for the in-flight per-year breakdown query, so re-selecting
     *  before the previous response cancels it. */
    private Disposable yearQueryDisposable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_time_saved_statistics, container, false);

        totalSavedValue   = root.findViewById(R.id.total_saved_value);
        periodTodayValue  = root.findViewById(R.id.period_today_value);
        periodWeekValue   = root.findViewById(R.id.period_week_value);
        periodMonthValue  = root.findViewById(R.id.period_month_value);
        stackSpeed        = root.findViewById(R.id.stack_speed);
        stackSilence      = root.findViewById(R.id.stack_silence);
        stackAds          = root.findViewById(R.id.stack_ads);
        stackIntro        = root.findViewById(R.id.stack_intro);
        stackOutro        = root.findViewById(R.id.stack_outro);
        stackedCaption    = root.findViewById(R.id.stacked_caption);
        barIntro          = root.findViewById(R.id.bar_intro);
        barOutro          = root.findViewById(R.id.bar_outro);
        barAds            = root.findViewById(R.id.bar_ads);
        barSilence        = root.findViewById(R.id.bar_silence);
        barSpeed          = root.findViewById(R.id.bar_speed);
        valueIntro        = root.findViewById(R.id.value_intro);
        valueOutro        = root.findViewById(R.id.value_outro);
        valueAds          = root.findViewById(R.id.value_ads);
        valueSilence      = root.findViewById(R.id.value_silence);
        valueSpeed        = root.findViewById(R.id.value_speed);
        yearlyContainer   = root.findViewById(R.id.yearly_container);
        categorySubheader = root.findViewById(R.id.category_subheader);
        emptyState        = root.findViewById(R.id.empty_state);

        // Apply editorial serif typeface to the hero + period numerals.
        totalSavedValue.setTypeface(EditorialTheme.getSerif(requireContext()));
        periodTodayValue.setTypeface(EditorialTheme.getSerif(requireContext()));
        periodWeekValue.setTypeface(EditorialTheme.getSerif(requireContext()));
        periodMonthValue.setTypeface(EditorialTheme.getSerif(requireContext()));

        new ViewModelProvider(requireParentFragment())
                .get(StatisticsViewModel.class)
                .skip()
                .observe(getViewLifecycleOwner(), s -> {
                    if (s != null) {
                        showStatistics(s);
                    } else {
                        emptyState.setVisibility(View.VISIBLE);
                    }
                });
        return root;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // The year-breakdown subscription is owned by this fragment, not the
        // ViewModel — dispose so its main-thread callback can't fire on a
        // destroyed view.
        if (yearQueryDisposable != null) {
            yearQueryDisposable.dispose();
            yearQueryDisposable = null;
        }
    }

    private void showStatistics(DBReader.SkipStatistics stats) {
        Context ctx = getContext();
        if (ctx == null) return;
        allTimeStats = stats;
        emptyState.setVisibility(stats.totalMs <= 0 ? View.VISIBLE : View.GONE);

        totalSavedValue.setText(formatHumanDuration(stats.totalMs));
        periodTodayValue.setText(formatHumanDuration(stats.todayMs));
        periodWeekValue.setText(formatHumanDuration(stats.weekMs));
        periodMonthValue.setText(formatHumanDuration(stats.monthMs));

        // Render the type breakdown — either the all-time view, or, if the user
        // had a year selected before the data refresh, re-apply that selection.
        if (selectedYear != null) {
            loadYearBreakdownAndApply(selectedYear);
        } else {
            applyTypeBreakdown(stats.totalMs, stats.introMs, stats.outroMs, stats.adMs,
                    stats.silenceMs, stats.speedMs, "ALL-TIME");
        }

        bindYearlyHistory(stats, ctx);
    }

    /** Push a set of per-type totals into the §01 stacked bar + §02 per-type
     *  rows + §01 subheader. The {@code scope} label appears in "<scope> · BY
     *  CATEGORY" — e.g. "ALL-TIME" or "YEAR 2025". */
    private void applyTypeBreakdown(long totalMs, long introMs, long outroMs, long adMs,
                                     long silenceMs, long speedMs, String scope) {
        categorySubheader.setText(scope + " · BY CATEGORY");

        // Stacked bar segments by share of total
        long stackTotal = Math.max(1, totalMs);
        setStackWeight(stackSpeed,   speedMs,   stackTotal);
        setStackWeight(stackSilence, silenceMs, stackTotal);
        setStackWeight(stackAds,     adMs,      stackTotal);
        setStackWeight(stackIntro,   introMs,   stackTotal);
        setStackWeight(stackOutro,   outroMs,   stackTotal);
        if (totalMs <= 0) {
            stackedCaption.setText("");
        } else {
            stackedCaption.setText(String.format(Locale.getDefault(),
                    "SPEED %d%% · SILENCE %d%% · ADS %d%% · INTROS %d%% · OUTROS %d%%",
                    pct(speedMs, stackTotal),
                    pct(silenceMs, stackTotal),
                    pct(adMs, stackTotal),
                    pct(introMs, stackTotal),
                    pct(outroMs, stackTotal)));
        }

        // Per-type bars — scale to leading category so it hits the bar's edge.
        long maxType = Math.max(1, Math.max(introMs,
                Math.max(outroMs, Math.max(adMs,
                        Math.max(silenceMs, speedMs)))));
        setTypeRow(barSpeed,   valueSpeed,   speedMs,   maxType);
        setTypeRow(barSilence, valueSilence, silenceMs, maxType);
        setTypeRow(barAds,     valueAds,     adMs,      maxType);
        setTypeRow(barIntro,   valueIntro,   introMs,   maxType);
        setTypeRow(barOutro,   valueOutro,   outroMs,   maxType);
    }

    /** Drill the by-type breakdown into the given calendar year. Async query;
     *  the §01 subheader briefly reads "YEAR <n> · LOADING…" while in flight. */
    private void loadYearBreakdownAndApply(int year) {
        if (DemoStats.ENABLED && allTimeStats != null) {
            // Scale the all-time breakdown by the selected year's share of the
            // monthly history so the demo drill-down looks plausible.
            long yearTotal = 0;
            for (DBReader.MonthlySkipItem m : allTimeStats.monthly) {
                if (m.year == year) yearTotal += m.totalMs;
            }
            double frac = allTimeStats.totalMs > 0
                    ? (double) yearTotal / allTimeStats.totalMs : 0;
            applyTypeBreakdown(
                    yearTotal,
                    (long) (allTimeStats.introMs   * frac),
                    (long) (allTimeStats.outroMs   * frac),
                    (long) (allTimeStats.adMs      * frac),
                    (long) (allTimeStats.silenceMs * frac),
                    (long) (allTimeStats.speedMs   * frac),
                    "YEAR " + year);
            return;
        }
        long from = startOfYear(year);
        long to = startOfYear(year + 1) - 1;
        categorySubheader.setText("YEAR " + year + " · LOADING…");
        if (yearQueryDisposable != null) yearQueryDisposable.dispose();
        yearQueryDisposable = Single.fromCallable(() -> DBReader.getSkipBreakdown(from, to))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(r -> applyTypeBreakdown(r.totalMs, r.introMs, r.outroMs, r.adMs,
                                r.silenceMs, r.speedMs, "YEAR " + year),
                        e -> applyTypeBreakdown(0, 0, 0, 0, 0, 0, "YEAR " + year));
    }

    private static long startOfYear(int year) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, Calendar.JANUARY, 1, 0, 0, 0);
        return c.getTimeInMillis();
    }

    /** Set each stacked-bar segment's layout weight to its share of total (in
     *  thousandths so the parent's weightSum=1000 produces clean percentages).
     *  Zero-share segments collapse to 0 width via weight=0. */
    private static int pct(long part, long total) {
        return (int) Math.round(100.0 * part / total);
    }

    private static void setStackWeight(View segment, long ms, long total) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) segment.getLayoutParams();
        lp.weight = total > 0 ? (float) ms / total * 1000f : 0f;
        segment.setLayoutParams(lp);
    }

    private void setTypeRow(ProgressBar bar, TextView value, long ms, long maxMs) {
        bar.setProgress(maxMs > 0 ? (int) (ms * 1000 / maxMs) : 0);
        value.setText(ms > 0 ? formatHumanDuration(ms) : "—");
    }

    /** Roll the monthly skip totals into calendar-year buckets and render one row
     *  per year, descending. Aggregating like this scales gracefully past a
     *  handful of months — a 3-year listener sees 3 rows instead of 36. */
    private void bindYearlyHistory(DBReader.SkipStatistics stats, Context ctx) {
        yearlyContainer.removeAllViews();
        if (stats.monthly.isEmpty()) return;

        TreeMap<Integer, Long> byYear = new TreeMap<>();
        for (DBReader.MonthlySkipItem m : stats.monthly) {
            byYear.merge(m.year, m.totalMs, Long::sum);
        }

        long maxYear = 1;
        for (Long v : byYear.values()) maxYear = Math.max(maxYear, v);

        // Render newest year first.
        boolean first = true;
        for (Integer year : byYear.descendingKeySet()) {
            if (!first) yearlyContainer.addView(buildHairline(ctx));
            yearlyContainer.addView(buildYearlyRow(year, byYear.get(year), maxYear, ctx));
            first = false;
        }
    }

    private View buildHairline(Context ctx) {
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 0.5f));
        v.setLayoutParams(lp);
        v.setBackgroundColor(EditorialTheme.ruleFaint(ctx));
        return v;
    }

    /** One year: 4-digit label · proportional bar · total recovered. Tappable
     *  to drill the by-type breakdown into that year (or to deselect when the
     *  already-selected year is tapped again). Selected row paints in vermilion. */
    private View buildYearlyRow(int year, long totalMs, long maxMs, Context ctx) {
        boolean isSelected = selectedYear != null && selectedYear == year;
        int accent = isSelected ? EditorialTheme.vermilion(ctx) : EditorialTheme.ink(ctx);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10));
        row.setGravity(Gravity.CENTER_VERTICAL);
        // Standard ripple feedback on touch.
        android.util.TypedValue tv = new android.util.TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);
        row.setClickable(true);
        row.setOnClickListener(v -> onYearTap(year));

        TextView labelView = new TextView(ctx);
        labelView.setText(String.valueOf(year));
        labelView.setTextSize(15);
        labelView.setTextColor(accent);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelView.setLayoutParams(labelLp);
        row.addView(labelView);

        ProgressBar bar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress(maxMs > 0 ? (int) (totalMs * 1000 / maxMs) : 0);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(EditorialTheme.ruleFaint(ctx)));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(ctx, 80), dp(ctx, 3));
        barLp.setMarginEnd(dp(ctx, 12));
        bar.setLayoutParams(barLp);
        row.addView(bar);

        TextView valueView = new TextView(ctx);
        valueView.setText(formatHumanDuration(totalMs));
        valueView.setTextSize(15);
        valueView.setTextColor(accent);
        valueView.setTypeface(EditorialTheme.getSerif(ctx));
        valueView.setGravity(Gravity.END);
        // Fixed-width value column so the bar's left edge is constant across rows
        // — otherwise "8h 24m" vs "47h 18m" would shift the bar position.
        valueView.setMinWidth(dp(ctx, 72));
        valueView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(valueView);
        return row;
    }

    /** Year-row tap: toggle selection. Tapping the active year deselects back
     *  to all-time. The by-type sections refresh async; the year list rebuilds
     *  synchronously so the selection visual updates immediately. */
    private void onYearTap(int year) {
        Context ctx = getContext();
        if (ctx == null || allTimeStats == null) return;
        if (selectedYear != null && selectedYear == year) {
            selectedYear = null;
            if (yearQueryDisposable != null) yearQueryDisposable.dispose();
            applyTypeBreakdown(allTimeStats.totalMs, allTimeStats.introMs,
                    allTimeStats.outroMs, allTimeStats.adMs,
                    allTimeStats.silenceMs, allTimeStats.speedMs, "ALL-TIME");
        } else {
            selectedYear = year;
            loadYearBreakdownAndApply(year);
        }
        bindYearlyHistory(allTimeStats, ctx);
    }

    private static int dp(Context ctx, float dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }

    /** Human-readable duration: Nd Mh / Nh Mm / Nm / 0m. Drops smaller units
     *  once a larger one is present so values stay short on the editorial layout. */
    private static String formatHumanDuration(long ms) {
        long totalMin = ms / 60_000L;
        if (totalMin <= 0) return "0m";
        long minutes = totalMin % 60;
        long totalHr = totalMin / 60;
        long hours = totalHr % 24;
        long days = totalHr / 24;
        if (days > 0) {
            return hours > 0 ? days + "d " + hours + "h" : days + "d";
        }
        if (hours > 0) {
            return minutes > 0 ? hours + "h " + minutes + "m" : hours + "h";
        }
        return minutes + "m";
    }
}
