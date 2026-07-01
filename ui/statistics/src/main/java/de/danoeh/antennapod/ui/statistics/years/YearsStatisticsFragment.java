package de.danoeh.antennapod.ui.statistics.years;

import android.content.Context;
import android.os.Bundle;
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
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.StatisticsItem;
import de.danoeh.antennapod.ui.statistics.DemoStats;
import de.danoeh.antennapod.ui.statistics.R;
import de.danoeh.antennapod.ui.statistics.StatisticsViewModel;
import de.danoeh.antennapod.ui.statistics.editorial.EditorialTheme;
import de.danoeh.antennapod.ui.statistics.editorial.StreamgraphView;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class YearsStatisticsFragment extends Fragment {

    private TextView allTimeHours;
    private TextView allTimeDays;
    private StreamgraphView streamgraph;
    private LinearLayout yearRows;
    private LinearLayout yearDrilldown;
    private LinearLayout yearDrilldownRows;
    private TextView yearDrilldownYear;
    private Integer selectedYear;
    private Disposable yearQueryDisposable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_years_editorial, container, false);
        allTimeHours       = root.findViewById(R.id.all_time_hours);
        allTimeDays        = root.findViewById(R.id.all_time_days);
        streamgraph        = root.findViewById(R.id.streamgraph);
        yearRows           = root.findViewById(R.id.year_rows);
        yearDrilldown      = root.findViewById(R.id.year_drilldown);
        yearDrilldownRows  = root.findViewById(R.id.year_drilldown_rows);
        yearDrilldownYear  = root.findViewById(R.id.year_drilldown_year);
        allTimeHours.setTypeface(EditorialTheme.getSerif(requireContext()));

        new ViewModelProvider(requireParentFragment())
                .get(StatisticsViewModel.class)
                .editorial()
                .observe(getViewLifecycleOwner(), s -> { if (s != null) bind(s); });

        return root;
    }

    private void bind(DBReader.EditorialStats s) {
        if (s.yearly.isEmpty()) {
            return;
        }

        float totalHrs = 0;
        for (DBReader.EditorialStats.YearItem y : s.yearly) {
            totalHrs += y.hrs;
        }
        long totalHoursRounded = Math.round(totalHrs);
        long totalDays = totalHoursRounded / 24;
        long remHours = totalHoursRounded % 24;
        allTimeHours.setText(String.format(Locale.getDefault(), "%dd", totalDays));
        allTimeDays.setText(String.format(Locale.getDefault(),
                "%d days, %d hours of audio.", totalDays, remHours));

        streamgraph.setData(s.yearly);

        // Build per-year rows (descending)
        yearRows.removeAllViews();
        List<DBReader.EditorialStats.YearItem> desc = new java.util.ArrayList<>(s.yearly);
        Collections.reverse(desc);
        float maxYearHrs = 0.01f;
        for (DBReader.EditorialStats.YearItem y : desc) if (y.hrs > maxYearHrs) maxYearHrs = y.hrs;

        for (int i = 0; i < desc.size(); i++) {
            DBReader.EditorialStats.YearItem cur = desc.get(i);
            float prevHrs = i < desc.size() - 1 ? desc.get(i + 1).hrs : -1;
            View row = buildYearRow(requireContext(), cur, prevHrs, maxYearHrs);
            yearRows.addView(row);
            // divider
            View div = new View(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            lp.setMarginStart(dpToPx(24));
            lp.setMarginEnd(dpToPx(24));
            div.setLayoutParams(lp);
            div.setBackgroundColor(EditorialTheme.ruleFaint(requireContext()));
            yearRows.addView(div);
        }
    }

    private View buildYearRow(Context ctx, DBReader.EditorialStats.YearItem y,
                               float prevHrs, float maxYearHrs) {
        boolean isSelected = selectedYear != null && selectedYear == y.year;
        int accent = isSelected ? EditorialTheme.vermilion(ctx) : EditorialTheme.ink(ctx);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dpToPx(24), dpToPx(12), dpToPx(24), dpToPx(12));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.util.TypedValue tv = new android.util.TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);
        row.setClickable(true);
        row.setOnClickListener(v -> onYearTap(y.year));

        // Year label
        TextView tvYear = new TextView(ctx);
        tvYear.setText(String.valueOf(y.year));
        tvYear.setTextSize(15);
        tvYear.setTextColor(accent);
        tvYear.setMinWidth(dpToPx(48));
        row.addView(tvYear);

        // Bar
        ProgressBar bar = new ProgressBar(ctx, null,
                android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(0,
                dpToPx(3), 1f);
        bLp.setMarginStart(dpToPx(8));
        bLp.setMarginEnd(dpToPx(8));
        bar.setLayoutParams(bLp);
        bar.setMax(1000);
        bar.setProgress((int) (y.hrs / maxYearHrs * 1000));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(EditorialTheme.vermilion(ctx)));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(EditorialTheme.ruleFaint(ctx)));
        row.addView(bar);

        // Hours
        TextView tvHrs = new TextView(ctx);
        long yearHoursRounded = Math.round(y.hrs);
        long days = yearHoursRounded / 24;
        long hrs  = yearHoursRounded % 24;
        tvHrs.setText(days > 0
                ? String.format(Locale.getDefault(), "%dd %dh", days, hrs)
                : String.format(Locale.getDefault(), "%dh", yearHoursRounded));
        tvHrs.setTextSize(16);
        tvHrs.setTextColor(accent);
        tvHrs.setTypeface(EditorialTheme.getSerif(ctx));
        tvHrs.setMinWidth(dpToPx(48));
        tvHrs.setGravity(android.view.Gravity.END);
        row.addView(tvHrs);

        // YoY delta
        TextView tvDelta = new TextView(ctx);
        tvDelta.setTextSize(11);
        tvDelta.setMinWidth(dpToPx(48));
        tvDelta.setGravity(android.view.Gravity.END);
        if (prevHrs < 0) {
            tvDelta.setText("first");
            tvDelta.setTextColor(EditorialTheme.inkMuted(ctx));
        } else if (prevHrs == 0) {
            tvDelta.setText("");
        } else {
            int delta = Math.round((y.hrs - prevHrs) / prevHrs * 100);
            if (delta > 0) {
                tvDelta.setText("▲ " + delta + "%");
                tvDelta.setTextColor(0xFF3a7a3a);
            } else {
                tvDelta.setText("▼ " + Math.abs(delta) + "%");
                tvDelta.setTextColor(EditorialTheme.vermilion(ctx));
            }
        }
        row.addView(tvDelta);

        return row;
    }

    /** Tap-toggle on a year row. Selected year drives the §02 top-shows
     *  drill-down; re-tapping deselects and collapses the section. */
    private void onYearTap(int year) {
        if (selectedYear != null && selectedYear == year) {
            selectedYear = null;
            if (yearQueryDisposable != null) {
                yearQueryDisposable.dispose();
            }
            yearDrilldown.setVisibility(View.GONE);
        } else {
            selectedYear = year;
            loadTopShowsForYear(year);
        }
        // Re-render year list so selection visual updates.
        DBReader.EditorialStats s = new ViewModelProvider(requireParentFragment())
                .get(StatisticsViewModel.class).editorial().getValue();
        if (s != null) {
            bind(s);
        }
    }

    /** Async: fetch top shows for the given year and render up to 5 rows in
     *  the §02 drill-down section. Brief "LOADING…" caption while in flight. */
    private void loadTopShowsForYear(int year) {
        yearDrilldown.setVisibility(View.VISIBLE);
        yearDrilldownYear.setText(year + " · loading…");
        yearDrilldownRows.removeAllViews();
        if (DemoStats.ENABLED) {
            renderTopShows(fakeTopShowsForYear(year), year);
            return;
        }
        long from = startOfYear(year);
        long to = startOfYear(year + 1) - 1;
        if (yearQueryDisposable != null) {
            yearQueryDisposable.dispose();
        }
        yearQueryDisposable = Single
                .fromCallable(() -> DBReader.getStatistics(false, from, to))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> renderTopShows(result.feedTime, year),
                        e -> yearDrilldownYear.setText(year + " · unavailable"));
    }

    /** Synthetic top-5 list for demo screenshots — different mix per year so
     *  drilling into different years feels real, not copy-pasted. */
    private List<StatisticsItem> fakeTopShowsForYear(int year) {
        String[][] perYear = {
                // year offset (year - 2022) -> show titles
                {"The Daily", "99% Invisible", "Reply All", "Radiolab", "Planet Money"},
                {"The Daily", "Hard Fork", "Acquired", "99% Invisible", "Conan O'Brien Needs a Friend"},
                {"Hard Fork", "Lex Fridman Podcast", "The Daily", "Acquired", "Search Engine"},
                {"Lex Fridman Podcast", "Acquired", "Hard Fork", "Search Engine", "99% Invisible"},
                {"Hard Fork", "Acquired", "The Daily", "Lex Fridman Podcast", "Search Engine"},
        };
        int[][] perYearHrs = {
                {18, 14, 11,  9,  7},
                {28, 22, 18, 14, 11},
                {34, 28, 22, 17, 13},
                {41, 36, 28, 24, 19},
                {24, 19, 15, 12,  9},
        };
        int idx = Math.max(0, Math.min(perYear.length - 1, year - 2022));
        String[] titles = perYear[idx];
        int[] hrs = perYearHrs[idx];

        List<StatisticsItem> items = new java.util.ArrayList<>(titles.length);
        for (int i = 0; i < titles.length; i++) {
            de.danoeh.antennapod.model.feed.Feed f =
                    new de.danoeh.antennapod.model.feed.Feed("", null, titles[i]);
            long timePlayedSec = hrs[i] * 3600L;
            items.add(new StatisticsItem(f, timePlayedSec, timePlayedSec,
                    50, 30, 0, 0, false, timePlayedSec / 8));
        }
        return items;
    }

    /** Render the top 5 shows for the selected year. Empty state if none. */
    private void renderTopShows(List<StatisticsItem> items, int year) {
        Context ctx = requireContext();
        yearDrilldownYear.setText(String.valueOf(year));
        yearDrilldownRows.removeAllViews();
        List<StatisticsItem> sorted = new java.util.ArrayList<>(items);
        Collections.sort(sorted, (a, b) -> Long.compare(b.timePlayed, a.timePlayed));
        long maxPlayed = sorted.isEmpty() ? 1 : Math.max(1, sorted.get(0).timePlayed);

        int count = 0;
        for (StatisticsItem it : sorted) {
            if (it.timePlayed <= 0) {
                continue;
            }
            if (count >= 5) {
                break;
            }
            yearDrilldownRows.addView(buildTopShowRow(ctx, it, maxPlayed));
            count++;
        }
        if (count == 0) {
            TextView empty = new TextView(ctx);
            empty.setText("No listening recorded for this year.");
            empty.setTextSize(13);
            empty.setTextColor(EditorialTheme.inkMuted(ctx));
            empty.setTypeface(null, android.graphics.Typeface.ITALIC);
            empty.setPadding(0, dpToPx(8), 0, dpToPx(8));
            yearDrilldownRows.addView(empty);
        }
    }

    /** One top-show row inside the year drill-down: title · bar · serif hours. */
    private View buildTopShowRow(Context ctx, StatisticsItem it, long maxPlayedSec) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dpToPx(8), 0, dpToPx(8));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView title = new TextView(ctx);
        title.setText(it.feed != null ? it.feed.getTitle() : "—");
        title.setTextSize(14);
        title.setTextColor(EditorialTheme.ink(ctx));
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        row.addView(title);

        ProgressBar bar = new ProgressBar(ctx, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress(maxPlayedSec > 0 ? (int) (it.timePlayed * 1000 / maxPlayedSec) : 0);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(EditorialTheme.vermilion(ctx)));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(EditorialTheme.ruleFaint(ctx)));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dpToPx(64), dpToPx(3));
        barLp.setMarginEnd(dpToPx(12));
        bar.setLayoutParams(barLp);
        row.addView(bar);

        TextView hrs = new TextView(ctx);
        long h = it.timePlayed / 3600;
        long m = (it.timePlayed % 3600) / 60;
        hrs.setText(h > 0
                ? String.format(Locale.getDefault(), "%dh %dm", h, m)
                : String.format(Locale.getDefault(), "%dm", m));
        hrs.setTextSize(14);
        hrs.setTextColor(EditorialTheme.ink(ctx));
        hrs.setTypeface(EditorialTheme.getSerif(ctx));
        hrs.setGravity(android.view.Gravity.END);
        hrs.setMinWidth(dpToPx(56));
        row.addView(hrs);

        return row;
    }

    private static long startOfYear(int year) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, Calendar.JANUARY, 1, 0, 0, 0);
        return c.getTimeInMillis();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (yearQueryDisposable != null) {
            yearQueryDisposable.dispose();
            yearQueryDisposable = null;
        }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
